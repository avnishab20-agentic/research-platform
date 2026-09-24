package com.comeback.researchplatform.agentservice.researcher;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.config.ResearcherProperties;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import com.comeback.researchplatform.agentservice.util.UrlHost;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.ResearchSubtask;
import com.comeback.researchplatform.common.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private final RunUsageGuard runUsageGuard;
    private final RunActivityLog activity;

    public ResearcherService(ChatClient.Builder chatClientBuilder, RetrievalClient retrievalClient,
                              PassageStore passageStore, ResearcherProperties props, FixtureIO fixtureIO,
                              RunUsageGuard runUsageGuard, RunActivityLog activity) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalClient = retrievalClient;
        this.passageStore = passageStore;
        this.props = props;
        this.fixtureIO = fixtureIO;
        this.runUsageGuard = runUsageGuard;
        this.activity = activity;
    }

    private void say(ResearchSubtask subtask, String message) {
        activity.record(subtask.runId(), subtask.nodeId(), "RESEARCHER", message);
    }

    public ResearchFinding research(ResearchSubtask subtask) {
        Instant start = Instant.now();
        Budget budget = new Budget(props.tokenBudget());

        // Step 1: generate search queries.
        List<String> queries = generateQueries(subtask.runId(), subtask.subQuestion(), budget);
        if (queries.isEmpty() || overBudget(start)) {
            return partial(subtask, "Could not generate search queries in time.", List.of());
        }
        say(subtask, "Searching the web for: " + String.join(" · ", queries));

        // Steps 2-3: search, dedupe, tier filtering happens server-side via minTier.
        List<SearchResult> results = search(subtask.runId(), queries);
        if (overBudget(start)) {
            return partial(subtask, "Search budget/time exceeded before extraction.", List.of());
        }
        say(subtask, "Found " + results.size() + " results; opening the top "
                + Math.min(results.size(), props.maxDocumentsToExtract()) + " pages");

        // Step 4: extract the clean text of the top documents.
        List<ExtractedDocument> documents = extractTopDocuments(results);
        List<ExtractedDocument> usable = new ArrayList<>();
        Set<String> siteNames = new LinkedHashSet<>();
        for (ExtractedDocument doc : documents) {
            if (doc.isUsable()) {
                usable.add(doc);
                siteNames.add(UrlHost.of(doc.url()));
            }
        }
        if (!usable.isEmpty()) {
            say(subtask, "Read " + usable.size() + " of " + documents.size() + " pages: "
                    + String.join(", ", siteNames));
        }
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
        String answer = synthesizeAnswer(subtask.runId(), subtask.subQuestion(), passages, budget);
        boolean unanswerable = answer != null && answer.trim().equals(UNANSWERABLE);
        boolean partial = overBudget(start) || budget.exceeded() || answer == null || unanswerable;

        // Step 7: confidence from the best tier actually cited -- code, not
        // another LLM call. Tier 1-2 support = high confidence; tier 3-4 only
        // = downgraded, per PLAN's "downgrade if only tier 3-4 support". Zero
        // regardless of tier when the model declined to answer -- a source
        // being trustworthy says nothing about an answer that wasn't given.
        Set<String> citedUrls = new HashSet<>();
        for (Document passage : passages) {
            citedUrls.add((String) passage.getMetadata().get("sourceUrl"));
        }
        List<SourceRef> sources = new ArrayList<>();
        for (ExtractedDocument doc : usable) {
            SourceRef source = new SourceRef(doc.url(), doc.tier());
            if (citedUrls.contains(doc.url()) && !sources.contains(source)) {
                sources.add(source);
            }
        }
        double confidence = unanswerable ? 0.0 : confidenceFor(sources);

        String finalAnswer;
        if (answer == null) {
            finalAnswer = "Unable to synthesize an answer within budget.";
            say(subtask, "Couldn't find an answer in what it read");
        } else if (unanswerable) {
            finalAnswer = "The retrieved passages did not contain an answer to this question.";
            say(subtask, "Couldn't find an answer in what it read");
        } else {
            finalAnswer = answer;
            say(subtask, "Wrote an answer from the " + passages.size() + " most relevant passages across "
                    + sources.size() + (sources.size() == 1 ? " source" : " sources"));
        }

        return new ResearchFinding(
                subtask.runId(), subtask.nodeId(), subtask.subQuestion(),
                finalAnswer, sources, confidence, partial ? STATUS_PARTIAL : STATUS_COMPLETE);
    }

    private List<String> generateQueries(UUID runId, String subQuestion, Budget budget) {
        if (budget.exceeded() || !runUsageGuard.tryLlmCall(runId)) {
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
            fixtureIO.recordChat(system + "\n---\n" + user, response);
            String text = response.getResult().getOutput().getText();
            if (text == null) {
                return List.of();
            }
            // One query per line; skip blank lines and stop at the configured maximum.
            List<String> queries = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String query = line.trim();
                if (!query.isEmpty() && queries.size() < props.maxSearchQueries()) {
                    queries.add(query);
                }
            }
            return queries;
        } catch (Exception e) {
            log.warn("Query generation failed for sub-question '{}'", subQuestion, e);
            return List.of();
        }
    }

    private List<SearchResult> search(UUID runId, List<String> queries) {
        if (!runUsageGuard.trySearch(runId)) {
            return List.of();
        }
        try {
            List<SearchResult> results = retrievalClient.search(queries, props.maxDocumentsToExtract() * 2, null, 4)
                    .results();
            // Two queries often find the same page. Keep the first copy of each URL, in order.
            List<SearchResult> unique = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            for (SearchResult result : results) {
                if (seenUrls.add(result.url())) {
                    unique.add(result);
                }
            }
            return unique;
        } catch (Exception e) {
            log.warn("Search failed for queries {}", queries, e);
            return List.of();
        }
    }

    private List<ExtractedDocument> extractTopDocuments(List<SearchResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (SearchResult result : results) {
            if (urls.size() == props.maxDocumentsToExtract()) {
                break;
            }
            urls.add(result.url());
        }
        try {
            return retrievalClient.extract(urls).documents();
        } catch (Exception e) {
            log.warn("Extract failed for {} urls", urls.size(), e);
            return List.of();
        }
    }

    private String synthesizeAnswer(UUID runId, String subQuestion, List<Document> passages, Budget budget) {
        if (budget.exceeded() || !runUsageGuard.tryLlmCall(runId)) {
            return null;
        }
        List<String> labelledPassages = new ArrayList<>();
        for (Document passage : passages) {
            labelledPassages.add("Source: " + passage.getMetadata().get("sourceUrl") + "\n" + passage.getText());
        }
        String context = String.join("\n\n---\n\n", labelledPassages);
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
            fixtureIO.recordChat(system + "\n---\n" + user, response);
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
        // Lower tier number = more trusted source, so the best tier is the smallest one.
        int bestTier = 4;
        for (SourceRef source : sources) {
            bestTier = Math.min(bestTier, source.tier());
        }
        return switch (bestTier) {
            case 1 -> 0.95;
            case 2 -> 0.85;
            case 3 -> 0.6;
            default -> 0.4;
        };
    }

    // Package-visible so ResearcherServiceTest can exercise the wall-clock
    // guardrail directly, same reasoning as confidenceFor().
    boolean overBudget(Instant start) {
        return Duration.between(start, Instant.now()).compareTo(props.wallClockBudget()) > 0;
    }

    private ResearchFinding partial(ResearchSubtask subtask, String reason, List<SourceRef> sources) {
        log.info("Sub-question '{}' finished PARTIAL: {}", subtask.subQuestion(), reason);
        say(subtask, "Stopped early: " + reason);
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
