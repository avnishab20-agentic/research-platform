package com.comeback.researchplatform.agentservice.researcher;

import com.comeback.researchplatform.agentservice.config.ResearcherProperties;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.ResearchSubtask;
import com.comeback.researchplatform.common.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * One sub-question, start to finish -- PLAN's 8-step researcher loop:
 * generate queries, search, filter, extract, RAG-retrieve grounding
 * passages, synthesize an answer, self-assess confidence, emit a finding.
 * <p>
 * A budget breach (wall clock or token count) never throws -- it short-
 * circuits to a PARTIAL finding with whatever was gathered so far. "A
 * failed run is published, never silently dropped" (CLAUDE.md).
 */
@Service
@Profile("RESEARCHER")
public class ResearcherService {

    private static final Logger log = LoggerFactory.getLogger(ResearcherService.class);
    private static final String STATUS_COMPLETE = "COMPLETE";
    private static final String STATUS_PARTIAL = "PARTIAL";
    // A fixed marker rather than sniffing free-form prose for phrases like
    // "cannot be answered" -- deterministic, and it's what let a real bug
    // slip through live: the model correctly declining to answer, but
    // confidenceFor() still scoring it 0.95 because it only ever looked at
    // source tier, never at whether an answer was actually given.
    private static final String UNANSWERABLE = "UNANSWERABLE";

    private final ChatClient chatClient;
    private final RetrievalClient retrievalClient;
    private final PassageStore passageStore;
    private final ResearcherProperties props;
    private final FixtureIO fixtureIO;
    private final boolean recordMode;

    public ResearcherService(ChatClient.Builder chatClientBuilder, RetrievalClient retrievalClient,
                              PassageStore passageStore, ResearcherProperties props, FixtureIO fixtureIO,
                              @Value("${fixtures.record-mode:false}") boolean recordMode) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalClient = retrievalClient;
        this.passageStore = passageStore;
        this.props = props;
        this.fixtureIO = fixtureIO;
        this.recordMode = recordMode;
    }

    // Inline, not a ChatModel decorator -- found live that wrapping ChatModel
    // as a @Primary bean broke Spring AI's internal OpenAiChatOptions casting
    // (see docs/progress for the ClassCastException this caused). A plain
    // post-call side effect at the two spots that already hold a real
    // ChatResponse has no such risk -- same pattern HttpRetrievalClient's
    // recording already uses successfully.
    private void recordChat(String promptText, ChatResponse response) {
        if (recordMode) {
            fixtureIO.record("chat-responses.json", FixtureIO.keyFor(promptText),
                    response.getResult().getOutput().getText());
        }
    }

    public ResearchFinding research(ResearchSubtask subtask) {
        Instant start = Instant.now();
        Budget budget = new Budget(props.tokenBudget());

        // Step 1: generate search queries.
        List<String> queries = generateQueries(subtask.subQuestion(), budget);
        if (queries.isEmpty() || overBudget(start)) {
            return partial(subtask, "Could not generate search queries in time.", List.of());
        }

        // Steps 2-3: search, dedupe, tier filtering happens server-side via minTier.
        List<SearchResult> results = search(queries);
        if (overBudget(start)) {
            return partial(subtask, "Search budget/time exceeded before extraction.", List.of());
        }

        // Step 4: extract the clean text of the top documents.
        List<ExtractedDocument> documents = extractTopDocuments(results);
        List<ExtractedDocument> usable = documents.stream()
                .filter(d -> "OK".equals(d.status()) && d.text() != null && !d.text().isBlank())
                .toList();
        if (usable.isEmpty() || overBudget(start)) {
            return partial(subtask, "No extractable sources found for this sub-question.", List.of());
        }

        // Step 5: RAG -- index each document's chunks, then retrieve the passages
        // closest to the sub-question. Same PassageStore the Critic will use later.
        for (ExtractedDocument doc : usable) {
            passageStore.index(subtask.runId(), doc.url(), doc.text());
        }
        List<Document> passages = passageStore.retrieveTopK(
                subtask.runId(), subtask.subQuestion(), props.passagesPerQuery());
        if (passages.isEmpty() || overBudget(start)) {
            return partial(subtask, "No relevant passages retrieved for this sub-question.", List.of());
        }

        // Step 6: synthesize an answer grounded only in the retrieved passages.
        String answer = synthesizeAnswer(subtask.subQuestion(), passages, budget);
        boolean unanswerable = answer != null && answer.trim().equals(UNANSWERABLE);
        boolean partial = overBudget(start) || budget.exceeded() || answer == null || unanswerable;

        // Step 7: confidence from the best tier actually cited -- code, not
        // another LLM call. Tier 1-2 support = high confidence; tier 3-4 only
        // = downgraded, per PLAN's "downgrade if only tier 3-4 support". Zero
        // regardless of tier when the model declined to answer -- a source
        // being trustworthy says nothing about an answer that wasn't given.
        Set<String> citedUrls = passages.stream()
                .map(p -> (String) p.getMetadata().get("sourceUrl"))
                .collect(Collectors.toSet());
        List<SourceRef> sources = usable.stream()
                .filter(d -> citedUrls.contains(d.url()))
                .map(d -> new SourceRef(d.url(), d.tier()))
                .distinct()
                .toList();
        double confidence = unanswerable ? 0.0 : confidenceFor(sources);

        String finalAnswer;
        if (answer == null) {
            finalAnswer = "Unable to synthesize an answer within budget.";
        } else if (unanswerable) {
            finalAnswer = "The retrieved passages did not contain an answer to this question.";
        } else {
            finalAnswer = answer;
        }

        return new ResearchFinding(
                subtask.runId(), subtask.nodeId(), subtask.subQuestion(),
                finalAnswer, sources, confidence, partial ? STATUS_PARTIAL : STATUS_COMPLETE);
    }

    private List<String> generateQueries(String subQuestion, Budget budget) {
        if (budget.exceeded()) {
            return List.of();
        }
        String system = "You generate web search queries. Reply with one query per line, "
                + "no numbering, no extra text.";
        String user = "Generate " + props.maxSearchQueries()
                + " diverse search queries to research this question: " + subQuestion;
        try {
            ChatResponse response = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .chatResponse();
            budget.record(response);
            recordChat(system + "\n---\n" + user, response);
            String text = response.getResult().getOutput().getText();
            if (text == null) {
                return List.of();
            }
            return Arrays.stream(text.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(props.maxSearchQueries())
                    .toList();
        } catch (Exception e) {
            log.warn("Query generation failed for sub-question '{}'", subQuestion, e);
            return List.of();
        }
    }

    private List<SearchResult> search(List<String> queries) {
        try {
            return retrievalClient.search(queries, props.maxDocumentsToExtract() * 2, null, 4)
                    .results().stream()
                    .collect(Collectors.toMap(SearchResult::url, r -> r, (a, b) -> a, LinkedHashMap::new))
                    .values().stream()
                    .toList();
        } catch (Exception e) {
            log.warn("Search failed for queries {}", queries, e);
            return List.of();
        }
    }

    private List<ExtractedDocument> extractTopDocuments(List<SearchResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        List<String> urls = results.stream()
                .map(SearchResult::url)
                .limit(props.maxDocumentsToExtract())
                .toList();
        try {
            return retrievalClient.extract(urls).documents();
        } catch (Exception e) {
            log.warn("Extract failed for {} urls", urls.size(), e);
            return List.of();
        }
    }

    private String synthesizeAnswer(String subQuestion, List<Document> passages, Budget budget) {
        if (budget.exceeded()) {
            return null;
        }
        String context = passages.stream()
                .map(p -> "Source: " + p.getMetadata().get("sourceUrl") + "\n" + p.getText())
                .collect(Collectors.joining("\n\n---\n\n"));
        String system = "Answer the research question using only the provided passages. "
                + "Do not use outside knowledge. If the passages don't answer the "
                + "question, reply with exactly this and nothing else: " + UNANSWERABLE;
        String user = "Question: " + subQuestion + "\n\nPassages:\n" + context;
        try {
            ChatResponse response = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .chatResponse();
            budget.record(response);
            recordChat(system + "\n---\n" + user, response);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("Answer synthesis failed for sub-question '{}'", subQuestion, e);
            return null;
        }
    }

    // Package-visible (not private) so ResearcherServiceTest can exercise this
    // pure decision logic directly -- ChatClient/RetrievalClient/PassageStore
    // are all real Spring AI/HTTP client fluent APIs, expensive and brittle to
    // mock end-to-end for what's actually being tested here: the arithmetic.
    double confidenceFor(List<SourceRef> sources) {
        if (sources.isEmpty()) {
            return 0.0;
        }
        int bestTier = sources.stream().mapToInt(SourceRef::tier).min().orElse(4);
        return switch (bestTier) {
            case 1 -> 0.95;
            case 2 -> 0.85;
            case 3 -> 0.6;
            default -> 0.4;
        };
    }

    private boolean overBudget(Instant start) {
        return Duration.between(start, Instant.now()).compareTo(props.wallClockBudget()) > 0;
    }

    private ResearchFinding partial(ResearchSubtask subtask, String reason, List<SourceRef> sources) {
        log.info("Sub-question '{}' finished PARTIAL: {}", subtask.subQuestion(), reason);
        return new ResearchFinding(subtask.runId(), subtask.nodeId(), subtask.subQuestion(),
                reason, sources, 0.0, STATUS_PARTIAL);
    }

    /** Tracks cumulative token usage across a subtask's LLM calls. DeepSeek's
     *  reasoning tokens count against this same budget -- a reasoning model
     *  can spend a surprising share of it just "thinking". */
    private static final class Budget {
        private final int limit;
        private int spent;

        Budget(int limit) {
            this.limit = limit;
        }

        void record(ChatResponse response) {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Integer total = response.getMetadata().getUsage().getTotalTokens();
                if (total != null) {
                    spent += total;
                }
            }
        }

        boolean exceeded() {
            return spent >= limit;
        }
    }
}
