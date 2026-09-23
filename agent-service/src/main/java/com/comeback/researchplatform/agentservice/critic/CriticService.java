package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.common.GuardrailProperties;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.common.ClaimKind;
import com.comeback.researchplatform.common.Verdict;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
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
    private final FixtureIO fixtureIO;
    private final boolean recordMode;
    private final ClaimCorrector claimCorrector;
    private final RunActivityLog activity;
    private final GuardrailProperties guardrails;

    public CriticService(ChatClient.Builder chatClientBuilder, RetrievalClient retrievalClient,
                          PassageStore passageStore, JdbcTemplate jdbc, CriticProperties props,
                          RunUsageGuard runUsageGuard, FixtureIO fixtureIO,
                          @Value("${fixtures.record-mode:false}") boolean recordMode,
                          ClaimCorrector claimCorrector, RunActivityLog activity,
                          GuardrailProperties guardrails) {
        this.claimCorrector = claimCorrector;
        this.activity = activity;
        this.guardrails = guardrails;
        this.chatClient = chatClientBuilder.build();
        this.retrievalClient = retrievalClient;
        this.passageStore = passageStore;
        this.jdbc = jdbc;
        this.props = props;
        this.runUsageGuard = runUsageGuard;
        this.fixtureIO = fixtureIO;
        this.recordMode = recordMode;
    }

    // Same pattern as ResearcherService.recordChat -- replay is already handled
    // globally by FixtureChatModel, this only covers the record-mode write side.
    private void recordChat(String promptText, ChatResponse response) {
        if (recordMode) {
            fixtureIO.record("chat-responses.json", FixtureIO.keyFor(promptText),
                    response.getResult().getOutput().getText());
        }
    }

    @Transactional
    public void verify(UUID runId) {
        List<ClaimRow> claims = loadClaims(runId);
        if (claims.isEmpty()) {
            // Nothing was checked, so nothing is verified -- an empty report is a failed
            // run, published as such rather than dressed up as a pass.
            jdbc.update("UPDATE runs SET status = 'UNVERIFIED', updated_at = now() WHERE id = ?", runId);
            log.info("Run {} has no claims to verify; marked UNVERIFIED", runId);
            return;
        }

        List<ClaimRow> verifiable = claims.stream()
                .filter(c -> ClaimKind.valueOf(c.kind()) != ClaimKind.INFERENCE)
                .toList();
        say(runId, "Re-opening " + claims.stream().map(ClaimRow::sourceUrl).distinct().count()
                + " sources to check " + verifiable.size() + " statements");
        refetchSources(runId, claims);

        Map<UUID, Verdict> verdicts = new HashMap<>();
        Map<UUID, String> evidenceByClaimId = new HashMap<>();
        gradeAll(runId, verifiable, verdicts, evidenceByClaimId);
        say(runId, "First check: " + tally(verifiable, verdicts));

        // PLAN's round 2: re-research the failed claims only. Deviation from
        // PLAN's gate: every failed claim is attempted (up to maxCorrections),
        // not only when the ratio is already over threshold -- one wrong
        // sentence left standing is still a wrong sentence on the page.
        Map<UUID, ClaimRow> current = new LinkedHashMap<>();
        verifiable.forEach(c -> current.put(c.id(), c));
        Set<UUID> removed = new HashSet<>();
        int revisedCount = 0;
        if (guardrails.run().maxCriticRounds() > 1) {
            List<ClaimRow> failed = verifiable.stream()
                    .filter(c -> isFailure(verdicts.get(c.id())))
                    .limit(props.maxCorrections())
                    .toList();
            if (!failed.isEmpty()) {
                say(runId, failed.size() + (failed.size() == 1 ? " statement" : " statements")
                        + " didn't hold up. Going back to the web to fix "
                        + (failed.size() == 1 ? "it" : "them"));
            }
            List<ClaimRow> revised = new ArrayList<>();
            for (ClaimRow claim : failed) {
                say(runId, "Fixing: “" + claim.text() + "”");
                ClaimCorrector.Outcome outcome = claimCorrector.correct(runId, claim, verdicts.get(claim.id()));
                switch (outcome.kind()) {
                    case REVISED -> {
                        revised.add(outcome.claim());
                        current.put(claim.id(), outcome.claim());
                        verdicts.remove(claim.id());
                        evidenceByClaimId.remove(claim.id());
                    }
                    case REMOVED -> removed.add(claim.id());
                    case UNCHANGED -> { }
                }
            }
            if (!revised.isEmpty()) {
                // Independent re-grade: the same grading call as round one, not
                // the corrector vouching for its own rewrite.
                gradeAll(runId, revised, verdicts, evidenceByClaimId);
                revisedCount = revised.size();
                say(runId, "Re-checked the " + revised.size() + " rewritten "
                        + (revised.size() == 1 ? "statement" : "statements") + ": " + tally(revised, verdicts));
            }
        }

        for (ClaimRow claim : current.values()) {
            Verdict verdict = verdicts.getOrDefault(claim.id(), Verdict.UNREACHABLE);
            jdbc.update("INSERT INTO claim_verdicts (claim_id, verdict, evidence_passage) VALUES (?, ?, ?)",
                    claim.id(), verdict.name(), evidenceByClaimId.get(claim.id()));
        }

        // Ratio over what the reader actually sees: removed claims are gone
        // from the answer, so they no longer count against it. They stay in
        // the report (as "removed") so the removal itself is visible.
        List<Verdict> published = current.keySet().stream()
                .filter(id -> !removed.contains(id))
                .map(id -> verdicts.getOrDefault(id, Verdict.UNREACHABLE))
                .toList();
        double ratio = unsupportedRatio(published);
        // Above threshold still publishes, banner-marked, per CLAUDE.md's "a
        // failed run is published, never silently dropped".
        String status = ratio > props.unsupportedRatioThreshold() ? "UNVERIFIED" : "VERIFIED";
        jdbc.update("UPDATE runs SET status = ?, updated_at = now() WHERE id = ?", status, runId);
        long confirmed = published.stream().filter(v -> v == Verdict.SUPPORTED).count();
        say(runId, "Done: " + confirmed + " of " + published.size() + " statements in the answer are confirmed"
                + (revisedCount > 0 ? ", " + revisedCount + " rewritten" : "")
                + (removed.isEmpty() ? "" : ", " + removed.size() + " removed"));
        log.info("Run {} verified: {} claims graded, {} revised, {} removed, unsupported_ratio={}, status={}",
                runId, verifiable.size(), revisedCount, removed.size(), ratio, status);
    }

    private static boolean isFailure(Verdict v) {
        return v == Verdict.UNSUPPORTED || v == Verdict.CONTRADICTED;
    }

    // Package-visible so CriticServiceTest can pin the wording's arithmetic.
    static String tally(List<ClaimRow> claims, Map<UUID, Verdict> verdicts) {
        Map<Verdict, Long> counts = claims.stream().collect(Collectors.groupingBy(
                c -> verdicts.getOrDefault(c.id(), Verdict.UNREACHABLE), () -> new EnumMap<>(Verdict.class),
                Collectors.counting()));
        long failed = counts.getOrDefault(Verdict.UNSUPPORTED, 0L) + counts.getOrDefault(Verdict.CONTRADICTED, 0L);
        return counts.getOrDefault(Verdict.SUPPORTED, 0L) + " confirmed, "
                + counts.getOrDefault(Verdict.PARTIAL, 0L) + " partly confirmed, "
                + failed + " failed, "
                + counts.getOrDefault(Verdict.UNREACHABLE, 0L) + " couldn't be checked";
    }

    private void say(UUID runId, String message) {
        activity.record(runId, null, "CRITIC", message);
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

        String system = "You are a fact-checker. For each numbered claim, decide whether the "
                + "evidence passages support it: SUPPORTED (fully backed), PARTIAL "
                + "(backed but missing detail or nuance), UNSUPPORTED (evidence doesn't "
                + "address the claim), or CONTRADICTED (evidence directly disagrees). "
                + "For each claim, quote the exact sentence from its evidence that most "
                + "influenced your decision -- copy it verbatim, don't paraphrase.";
        String user = prompt.toString();
        try {
            // responseEntity, not entity -- same reasoning as WriterService.extractClaims:
            // one call gets both the parsed grades and the raw text recordChat needs.
            ResponseEntity<ChatResponse, List<ClaimGrade>> result = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .responseEntity(new ParameterizedTypeReference<List<ClaimGrade>>() {});
            recordChat(system + "\n---\n" + user, result.response());
            List<ClaimGrade> grades = result.entity();
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
