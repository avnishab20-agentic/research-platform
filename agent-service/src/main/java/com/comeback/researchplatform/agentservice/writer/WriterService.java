package com.comeback.researchplatform.agentservice.writer;

import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ClaimsReady;
import com.comeback.researchplatform.common.ResearchFinding;
import com.comeback.researchplatform.common.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public WriterService(ChatClient.Builder chatClientBuilder, JdbcTemplate jdbc,
                          ObjectMapper objectMapper, KafkaTemplate<String, Object> kafkaTemplate,
                          RunUsageGuard runUsageGuard) {
        this.chatClient = chatClientBuilder.build();
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.runUsageGuard = runUsageGuard;
    }

    @Transactional
    public void write(UUID runId) {
        List<ResearchFinding> findings = loadFindings(runId);
        int claimsWritten = 0;

        for (ResearchFinding finding : findings) {
            if (finding.confidence() <= 0 || finding.sources().isEmpty()) {
                // Nothing to extract -- an UNANSWERABLE or source-less finding
                // has no checkable claims, not even a low-confidence one.
                continue;
            }

            Map<String, UUID> sourceIdByUrl = upsertSources(runId, finding.sources());
            List<ExtractedClaim> extracted = extractClaims(finding);

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
        }

        jdbc.update("UPDATE runs SET status = 'CLAIMS_READY', updated_at = now() WHERE id = ?", runId);
        log.info("Run {} written: {} claims from {} findings", runId, claimsWritten, findings.size());
        kafkaTemplate.send(KafkaTopics.CLAIMS_READY, runId.toString(), new ClaimsReady(runId));
    }

    private List<ResearchFinding> loadFindings(UUID runId) {
        List<String> jsonFindings = jdbc.queryForList(
                "SELECT finding::text FROM dag_nodes WHERE run_id = ? AND finding IS NOT NULL",
                String.class, runId);
        return jsonFindings.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ResearchFinding.class);
                    } catch (Exception e) {
                        log.warn("Could not parse a stored finding for run {}", runId, e);
                        return null;
                    }
                })
                .filter(f -> f != null)
                .toList();
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
        String sourceList = finding.sources().stream()
                .map(s -> s.url() + " (tier " + s.tier() + ")")
                .collect(Collectors.joining("\n"));
        try {
            List<ExtractedClaim> claims = chatClient.prompt()
                    .system("Break the answer below into atomic, individually-checkable claims. "
                            + "Each claim must be a single fact, figure, or quote -- not a "
                            + "compound sentence covering several facts at once. Classify each "
                            + "as FACT, FIGURE, QUOTE, or INFERENCE (your own reasoning or "
                            + "synthesis, not directly stated in any source). Tag each claim "
                            + "with exactly one source URL from the list below, chosen as the "
                            + "one it's most directly based on.")
                    .user("Answer: " + finding.answer() + "\n\nSources:\n" + sourceList)
                    .call()
                    .entity(new ParameterizedTypeReference<List<ExtractedClaim>>() {});
            return claims != null ? claims : List.of();
        } catch (Exception e) {
            log.warn("Claim extraction failed for node {}", finding.nodeId(), e);
            return List.of();
        }
    }
}
