package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The last step of a run: turn the graded claims into an answer a person can
 * read. The model sees only claims that passed (SUPPORTED/PARTIAL), numbered,
 * and must cite those numbers for every sentence it writes. Citations that
 * don't resolve to a passed claim are dropped, and so is any takeaway left
 * with none -- the conclusion can summarise checked facts, never add new ones.
 */
@Service
@Profile("CRITIC")
public class ConclusionWriter {

    private static final Logger log = LoggerFactory.getLogger(ConclusionWriter.class);

    /** What the model returns: sentences citing claims by their 1-based number. */
    record Draft(String answer, List<Integer> answerCites, List<DraftTakeaway> takeaways) {}
    record DraftTakeaway(String text, List<Integer> cites) {}

    /** What gets stored and rendered: citations resolved to claim ids. */
    record Conclusion(String answer, List<UUID> answerClaimIds, List<Takeaway> takeaways) {}
    record Takeaway(String text, List<UUID> claimIds) {}

    private final ChatClient chatClient;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RunUsageGuard runUsageGuard;
    private final RunActivityLog activity;
    private final FixtureIO fixtureIO;
    private final boolean recordMode;

    public ConclusionWriter(ChatClient.Builder chatClientBuilder, JdbcTemplate jdbc, ObjectMapper objectMapper,
                            RunUsageGuard runUsageGuard, RunActivityLog activity, FixtureIO fixtureIO,
                            @Value("${fixtures.record-mode:false}") boolean recordMode) {
        this.chatClient = chatClientBuilder.build();
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.runUsageGuard = runUsageGuard;
        this.activity = activity;
        this.fixtureIO = fixtureIO;
        this.recordMode = recordMode;
    }

    /** Never throws: a missing conclusion must not stop the run reaching its final status. */
    public void write(UUID runId, List<ClaimRow> passed) {
        if (passed.isEmpty() || !runUsageGuard.tryLlmCall(runId)) {
            return;
        }
        try {
            String question = jdbc.queryForObject("SELECT question FROM runs WHERE id = ?", String.class, runId);
            Conclusion conclusion = resolve(draft(question, passed), passed);
            if (conclusion == null) {
                return;
            }
            jdbc.update("UPDATE runs SET conclusion = ?::jsonb WHERE id = ?",
                    objectMapper.writeValueAsString(conclusion), runId);
            activity.record(runId, null, "CRITIC", "Wrote the conclusion from " + passed.size()
                    + " confirmed statements, with " + conclusion.takeaways().size() + " key takeaways");
        } catch (Exception e) {
            log.warn("Conclusion failed for run {}", runId, e);
        }
    }

    private Draft draft(String question, List<ClaimRow> passed) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < passed.size(); i++) {
            numbered.append('[').append(i + 1).append("] ").append(passed.get(i).text()).append('\n');
        }
        String system = "You write the conclusion of a research report for a general reader. You get the "
                + "question and a numbered list of fact-checked statements. Use only those statements -- "
                + "no outside knowledge. answer: 2-3 plain sentences that directly answer the question. "
                + "takeaways: 3-5 short, distinct points, each merging related statements instead of "
                + "repeating them. Every answer sentence and every takeaway must cite the numbers of the "
                + "statements it rests on (answerCites, cites), in those fields only -- never write "
                + "numbers or brackets in the text itself. Cite the few statements that best support "
                + "each point, not every related one. Keep each takeaway to one sentence. "
                + "If statements disagree, say so.";
        String user = "Question: " + question + "\n\nStatements:\n" + numbered;
        ResponseEntity<ChatResponse, Draft> result = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .responseEntity(Draft.class);
        if (recordMode) {
            fixtureIO.record("chat-responses.json", FixtureIO.keyFor(system + "\n---\n" + user),
                    result.response().getResult().getOutput().getText());
        }
        return result.entity();
    }

    // Package-visible: the citation filter is the part that keeps unchecked facts
    // out, so ConclusionWriterTest exercises it directly.
    static Conclusion resolve(Draft draft, List<ClaimRow> passed) {
        if (draft == null || draft.answer() == null || draft.answer().isBlank()) {
            return null;
        }
        List<Takeaway> takeaways = new ArrayList<>();
        for (DraftTakeaway t : Objects.requireNonNullElse(draft.takeaways(), List.<DraftTakeaway>of())) {
            List<UUID> ids = ids(t.cites(), passed);
            if (t.text() != null && !t.text().isBlank() && !ids.isEmpty()) {
                takeaways.add(new Takeaway(clean(t.text()), ids));
            }
        }
        List<UUID> answerIds = ids(draft.answerCites(), passed);
        if (answerIds.isEmpty() && takeaways.isEmpty()) {
            return null;
        }
        return new Conclusion(clean(draft.answer()), answerIds, takeaways);
    }

    // The model sometimes writes "[2,3,4]" into the prose as well as the cites field;
    // the page renders citations itself, so inline ones would show twice.
    private static final java.util.regex.Pattern INLINE_CITES = java.util.regex.Pattern.compile("\\s*\\[[\\d,\\s–-]+]");

    static String clean(String text) {
        return INLINE_CITES.matcher(text).replaceAll("").trim();
    }

    private static List<UUID> ids(List<Integer> cites, List<ClaimRow> passed) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        for (Integer n : Objects.requireNonNullElse(cites, List.<Integer>of())) {
            if (n != null && n >= 1 && n <= passed.size()) {
                out.put(passed.get(n - 1).id(), true);
            }
        }
        return List.copyOf(out.keySet());
    }
}
