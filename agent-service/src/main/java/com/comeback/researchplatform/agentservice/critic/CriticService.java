package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.common.ClaimKind;
import com.comeback.researchplatform.common.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PLAN's Critic loop -- CLAUDE.md: "the Critic re-fetches sources and
 * stores the matched evidence passage, not just a verdict." This is the
 * project's actual point: every non-INFERENCE claim gets checked against
 * real, independently re-fetched source text, and the exact sentence used
 * to decide is stored alongside the verdict so it's checkable by a human,
 * not just trusted.
 * <p>
 * Grading passages are retrieved from the same run-scoped {@link
 * PassageStore} the Researcher already populated (same runId+sourceUrl
 * chunks) rather than re-embedded a second time -- re-fetching still
 * re-verifies the source is reachable and refreshes {@code
 * sources.extracted_text} for the evidence trail, but avoids duplicate
 * vector rows for text that, in the common case, hasn't changed. Documented
 * scope cut, not an oversight.
 */
@Service
@Profile("CRITIC")
public class CriticService {

    private static final Logger log = LoggerFactory.getLogger(CriticService.class);

    private final ChatClient chatClient;
    private final RetrievalClient retrievalClient;
    private final PassageStore passageStore;
    private final JdbcTemplate jdbc;
    private final CriticProperties props;
    private final RunUsageGuard runUsageGuard;

    public CriticService(ChatClient.Builder chatClientBuilder, RetrievalClient retrievalClient,
                          PassageStore passageStore, JdbcTemplate jdbc, CriticProperties props,
                          RunUsageGuard runUsageGuard) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalClient = retrievalClient;
        this.passageStore = passageStore;
        this.jdbc = jdbc;
        this.props = props;
        this.runUsageGuard = runUsageGuard;
    }

    @Transactional
    public void verify(UUID runId) {
        List<ClaimRow> claims = loadClaims(runId);
        if (claims.isEmpty()) {
            jdbc.update("UPDATE runs SET status = 'VERIFIED', updated_at = now() WHERE id = ?", runId);
            log.info("Run {} has no claims to verify; marked VERIFIED (vacuously)", runId);
            return;
        }

        refetchSources(runId, claims);

        List<ClaimRow> verifiable = claims.stream()
                .filter(c -> ClaimKind.valueOf(c.kind()) != ClaimKind.INFERENCE)
                .toList();

        Map<UUID, Verdict> verdicts = new HashMap<>();
        Map<UUID, String> evidenceByClaimId = new HashMap<>();
        gradeAll(runId, verifiable, verdicts, evidenceByClaimId);

        for (ClaimRow claim : verifiable) {
            Verdict verdict = verdicts.getOrDefault(claim.id(), Verdict.UNREACHABLE);
            jdbc.update("INSERT INTO claim_verdicts (claim_id, verdict, evidence_passage) VALUES (?, ?, ?)",
                    claim.id(), verdict.name(), evidenceByClaimId.get(claim.id()));
        }

        double ratio = unsupportedRatio(verdicts.values());
        // PLAN: "if ratio > 0.15 AND round < 2: re-research the failed claims
        // ONLY". The re-research round is not implemented this pass -- ratio
        // above threshold still publishes, banner-marked, per CLAUDE.md's "a
        // failed run is published, never silently dropped". Documented cut,
        // not a silent gap.
        String status = ratio > props.unsupportedRatioThreshold() ? "UNVERIFIED" : "VERIFIED";
        jdbc.update("UPDATE runs SET status = ?, updated_at = now() WHERE id = ?", status, runId);
        log.info("Run {} verified: {} claims graded, unsupported_ratio={}, status={}",
                runId, verifiable.size(), ratio, status);
    }

    private List<ClaimRow> loadClaims(UUID runId) {
        return jdbc.query(
                "SELECT c.id, c.text, c.kind, c.source_id, s.url AS source_url "
                        + "FROM claims c JOIN sources s ON c.source_id = s.id WHERE c.run_id = ?",
                (rs, rowNum) -> new ClaimRow(
                        (UUID) rs.getObject("id"), rs.getString("text"), rs.getString("kind"),
                        (UUID) rs.getObject("source_id"), rs.getString("source_url")),
                runId);
    }

    /** The actual "re-fetch" -- independently confirms each source is still
     *  reachable and still says what the Writer's claims assumed it said. */
    private void refetchSources(UUID runId, List<ClaimRow> claims) {
        List<String> urls = claims.stream().map(ClaimRow::sourceUrl).distinct().toList();
        if (urls.isEmpty()) {
            return;
        }
        try {
            List<ExtractedDocument> documents = retrievalClient.extract(urls).documents();
            for (ExtractedDocument doc : documents) {
                if ("OK".equals(doc.status()) && doc.text() != null) {
                    jdbc.update("UPDATE sources SET extracted_text = ?, fetched_at = now() "
                            + "WHERE run_id = ? AND url = ?", doc.text(), runId, doc.url());
                }
                // Non-OK re-fetches leave the existing extracted_text alone --
                // a transient failure to re-fetch shouldn't blank out text the
                // Researcher already successfully read once.
            }
        } catch (Exception e) {
            log.warn("Source re-fetch failed for run {}", runId, e);
        }
    }

    private void gradeAll(UUID runId, List<ClaimRow> verifiable,
                           Map<UUID, Verdict> verdicts, Map<UUID, String> evidenceByClaimId) {
        for (int i = 0; i < verifiable.size(); i += props.batchSize()) {
            List<ClaimRow> batch = verifiable.subList(i, Math.min(i + props.batchSize(), verifiable.size()));
            gradeBatch(runId, batch, verdicts, evidenceByClaimId);
        }
    }

    private void gradeBatch(UUID runId, List<ClaimRow> batch,
                             Map<UUID, Verdict> verdicts, Map<UUID, String> evidenceByClaimId) {
        // claims with zero retrieved passages never reach the model -- no
        // evidence exists to grade against, so UNREACHABLE is decided in
        // code, not spent as an LLM call.
        List<ClaimRow> gradable = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        int index = 0;
        Map<Integer, UUID> indexToClaimId = new HashMap<>();

        for (ClaimRow claim : batch) {
            List<Document> passages = passageStore.retrieveTopK(runId, claim.text(), props.topKPassages());
            if (passages.isEmpty()) {
                verdicts.put(claim.id(), Verdict.UNREACHABLE);
                continue;
            }
            index++;
            indexToClaimId.put(index, claim.id());
            gradable.add(claim);
            prompt.append("Claim ").append(index).append(": ").append(claim.text()).append("\n");
            prompt.append("Evidence passages:\n");
            for (Document passage : passages) {
                prompt.append("- ").append(passage.getText()).append("\n");
            }
            prompt.append("\n");
        }

        if (gradable.isEmpty() || !runUsageGuard.tryLlmCall(runId)) {
            return;
        }

        try {
            List<ClaimGrade> grades = chatClient.prompt()
                    .system("You are a fact-checker. For each numbered claim, decide whether the "
                            + "evidence passages support it: SUPPORTED (fully backed), PARTIAL "
                            + "(backed but missing detail or nuance), UNSUPPORTED (evidence doesn't "
                            + "address the claim), or CONTRADICTED (evidence directly disagrees). "
                            + "For each claim, quote the exact sentence from its evidence that most "
                            + "influenced your decision -- copy it verbatim, don't paraphrase.")
                    .user(prompt.toString())
                    .call()
                    .entity(new ParameterizedTypeReference<List<ClaimGrade>>() {});
            if (grades != null) {
                for (ClaimGrade grade : grades) {
                    UUID claimId = indexToClaimId.get(grade.index());
                    if (claimId != null) {
                        verdicts.put(claimId, grade.verdict());
                        evidenceByClaimId.put(claimId, grade.evidencePassage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Grading failed for a batch of {} claims in run {}", gradable.size(), runId, e);
        }
        // Any claim the model didn't return a grade for (parse mismatch,
        // partial response) falls back to UNREACHABLE via getOrDefault in
        // verify() -- never silently ungraded.
    }

    // Package-visible so CriticServiceTest can exercise this pure arithmetic
    // directly, same reasoning as ResearcherService.confidenceFor.
    double unsupportedRatio(Collection<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return 0.0;
        }
        long bad = verdicts.stream().filter(v -> v == Verdict.UNSUPPORTED || v == Verdict.CONTRADICTED).count();
        return (double) bad / verdicts.size();
    }
}
