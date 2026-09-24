package com.comeback.researchplatform.agentservice.writer;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ClaimsReady;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.SourceRef;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a run's raw {@link ResearchFinding}s into structured {@code claims}
 * rows -- PLAN's "Writer emits structured claims, not prose" (CLAUDE.md).
 * One finding's answer becomes one section's worth of atomic claims, each
 * tagged with exactly one source. The Critic (next, not yet built) is what
 * actually verifies each claim against its source; the Writer's job is only
 * to produce checkable units, not to pre-judge whether they're true.
 */
@Service
@Profile("WRITER")
public class WriterService {

    private static final Logger log = LoggerFactory.getLogger(WriterService.class);

    private final ChatClient chatClient;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RunUsageGuard runUsageGuard;
    private final FixtureIO fixtureIO;
    private final RunActivityLog activity;

    private final WriterProperties props;

    public WriterService(ChatClient.Builder chatClientBuilder, JdbcTemplate jdbc,
                          ObjectMapper objectMapper, KafkaTemplate<String, Object> kafkaTemplate,
                          RunUsageGuard runUsageGuard, FixtureIO fixtureIO,
                          RunActivityLog activity, WriterProperties props) {
        this.props = props;
        this.activity = activity;
        this.chatClient = chatClientBuilder.build();
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.runUsageGuard = runUsageGuard;
        this.fixtureIO = fixtureIO;
    }

    @Transactional
    public void write(UUID runId) {
        List<ResearchFinding> findings = loadFindings(runId);
        int claimsWritten = 0;
        activity.record(runId, null, "WRITER", "Collected " + findings.size()
                + " research answers; breaking each into short statements tied to one source");

        // Nothing to extract from an UNANSWERABLE or source-less finding -- it has
        // no checkable claims, not even a low-confidence one.
        List<ResearchFinding> answered = new ArrayList<>();
        for (ResearchFinding finding : findings) {
            if (finding.confidence() > 0 && !finding.sources().isEmpty()) {
                answered.add(finding);
            }
        }
        // The LLM calls are independent, so they run at once (was ~90s one after
        // another). Only the calls go to worker threads; every write below stays on
        // this thread, inside this method's transaction.
        List<CompletableFuture<List<ExtractedClaim>>> pending = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, answered.size()))) {
            for (ResearchFinding finding : answered) {
                pending.add(CompletableFuture.supplyAsync(() -> extractClaims(finding), pool));
            }
        }

        for (int i = 0; i < answered.size(); i++) {
            ResearchFinding finding = answered.get(i);
            Map<String, UUID> sourceIdByUrl = upsertSources(runId, finding.sources());
            List<ExtractedClaim> extracted = pending.get(i).join();

            for (ExtractedClaim claim : extracted) {
                UUID sourceId = sourceIdByUrl.get(claim.sourceUrl());
                if (sourceId == null) {
                    // Model cited a URL outside the list it was given -- fall
                    // back to any real source for this finding rather than
                    // dropping the claim outright.
                    sourceId = sourceIdByUrl.values().iterator().next();
                }
                jdbc.update("INSERT INTO claims (run_id, node_id, section_heading, text, source_id, kind) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        runId, finding.nodeId(), finding.subQuestion(), claim.text(), sourceId, claim.kind().name());
                claimsWritten++;
            }
            activity.record(runId, finding.nodeId(), "WRITER",
                    "Wrote " + extracted.size() + " statements for “" + finding.subQuestion() + "”");
        }
        activity.record(runId, null, "WRITER", "Handed " + claimsWritten
                + " statements to the fact-checker");

        jdbc.update("UPDATE runs SET status = 'CLAIMS_READY', updated_at = now() WHERE id = ?", runId);
        log.info("Run {} written: {} claims from {} findings", runId, claimsWritten, findings.size());
        kafkaTemplate.send(KafkaTopics.CLAIMS_READY, runId.toString(), new ClaimsReady(runId));
    }

    private List<ResearchFinding> loadFindings(UUID runId) {
        List<String> jsonFindings = jdbc.queryForList(
                "SELECT finding::text FROM dag_nodes WHERE run_id = ? AND finding IS NOT NULL",
                String.class, runId);
        List<ResearchFinding> findings = new ArrayList<>();
        for (String json : jsonFindings) {
            try {
                findings.add(objectMapper.readValue(json, ResearchFinding.class));
            } catch (Exception e) {
                // Skip the one bad row; the rest of the run can still be written.
                log.warn("Could not parse a stored finding for run {}", runId, e);
            }
        }
        return findings;
    }

    /** Upsert on (run_id, url): a finding's sources may already have been
     *  registered by another finding in the same run that cited the same
     *  URL -- one row either way, per the UNIQUE(run_id, url) constraint. */
    private Map<String, UUID> upsertSources(UUID runId, List<SourceRef> sources) {
        Map<String, UUID> byUrl = new HashMap<>();
        for (SourceRef source : sources) {
            UUID id = jdbc.queryForObject(
                    "INSERT INTO sources (run_id, url, tier) VALUES (?, ?, ?) "
                            + "ON CONFLICT (run_id, url) DO UPDATE SET tier = EXCLUDED.tier "
                            + "RETURNING id",
                    UUID.class, runId, source.url(), source.tier());
            byUrl.put(source.url(), id);
        }
        return byUrl;
    }

    private List<ExtractedClaim> extractClaims(ResearchFinding finding) {
        if (!runUsageGuard.tryLlmCall(finding.runId())) {
            return List.of();
        }
        List<String> sourceLines = new ArrayList<>();
        for (SourceRef source : finding.sources()) {
            sourceLines.add(source.url() + " (tier " + source.tier() + ")");
        }
        String sourceList = String.join("\n", sourceLines);
        String system = "Break the answer below into at most " + props.maxClaimsPerFinding()
                + " atomic, individually-checkable claims -- the most important ones. Never state "
                + "the same fact twice in different words. "
                + "Each claim must be a single fact, figure, or quote -- not a "
                + "compound sentence covering several facts at once. Classify each "
                + "as FACT, FIGURE, QUOTE, or INFERENCE (your own reasoning or "
                + "synthesis, not directly stated in any source). Tag each claim "
                + "with exactly one source URL from the list below, chosen as the "
                + "one it's most directly based on.";
        String user = "Answer: " + finding.answer() + "\n\nSources:\n" + sourceList;
        try {
            // responseEntity, not entity: it hands back the raw ChatResponse alongside
            // the parsed claims, in the same call -- entity() alone discards the
            // response text needed for recordChat, and a second call would double the
            // real cost/latency just to observe what the first one already returned.
            ResponseEntity<ChatResponse, List<ExtractedClaim>> result = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .responseEntity(new ParameterizedTypeReference<List<ExtractedClaim>>() {});
            fixtureIO.recordChat(system + "\n---\n" + user, result.response());
            List<ExtractedClaim> claims = result.entity();
            if (claims == null) {
                return List.of();
            }
            // The prompt asks for the cap; this enforces it when the model overshoots.
            if (claims.size() > props.maxClaimsPerFinding()) {
                return claims.subList(0, props.maxClaimsPerFinding());
            }
            return claims;
        } catch (Exception e) {
            log.warn("Claim extraction failed for node {}", finding.nodeId(), e);
            return List.of();
        }
    }
}
