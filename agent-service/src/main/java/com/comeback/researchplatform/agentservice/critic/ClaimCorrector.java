package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.agentservice.activity.RunActivityLog;
import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.guardrail.RunUsageGuard;
import com.comeback.researchplatform.agentservice.rag.PassageStore;
import com.comeback.researchplatform.agentservice.retrieval.RetrievalClient;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import com.comeback.researchplatform.common.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PLAN's re-research round: "re-research the failed claims ONLY (not a full
 * re-run)". For one claim the Critic graded UNSUPPORTED or CONTRADICTED, go
 * back to the web with the claim itself as the query, add what it finds to
 * the run's passages, then either rewrite the claim to say only what one
 * passage says, or drop it.
 * <p>
 * This class never grades its own rewrite -- CriticService re-grades every
 * REVISED claim with the same independent grading call as round one, so the
 * model that repaired a claim is never the one that approves it.
 */
@Service
@Profile("CRITIC")
public class ClaimCorrector {

    private static final Logger log = LoggerFactory.getLogger(ClaimCorrector.class);
    private static final int NEW_PAGES_PER_CLAIM = 3;

    public enum Kind { REVISED, REMOVED, UNCHANGED }

    /** {@code claim} is the rewritten row for REVISED, the original otherwise. */
    public record Outcome(Kind kind, ClaimRow claim) {}

    /** The model's decision. action is REVISE or DROP. */
    record ClaimRevision(String action, String text, String sourceUrl) {}

    private final ChatClient chatClient;
    private final RetrievalClient retrievalClient;
    private final PassageStore passageStore;
    private final JdbcTemplate jdbc;
    private final CriticProperties props;
    private final RunUsageGuard runUsageGuard;
    private final RunActivityLog activity;
    private final FixtureIO fixtureIO;
    private final boolean recordMode;

    public ClaimCorrector(ChatClient.Builder chatClientBuilder, RetrievalClient retrievalClient,
                          PassageStore passageStore, JdbcTemplate jdbc, CriticProperties props,
                          RunUsageGuard runUsageGuard, RunActivityLog activity, FixtureIO fixtureIO,
                          @Value("${fixtures.record-mode:false}") boolean recordMode) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalClient = retrievalClient;
        this.passageStore = passageStore;
        this.jdbc = jdbc;
        this.props = props;
        this.runUsageGuard = runUsageGuard;
        this.activity = activity;
        this.fixtureIO = fixtureIO;
        this.recordMode = recordMode;
    }

    public Outcome correct(UUID runId, ClaimRow claim, Verdict firstVerdict) {
        Map<String, Integer> tierByUrl = knownTiers(runId);
        searchAgain(runId, claim, tierByUrl);

        // Only passages whose source tier is known can become a claim's
        // source -- sources.tier is NOT NULL, and guessing a tier would put a
        // made-up trust grade on the page.
        List<Document> passages = passageStore.retrieveTopK(runId, claim.text(), props.topKPassages() * 2)
                .stream()
                .filter(p -> tierByUrl.containsKey(urlOf(p)))
                .toList();
        if (passages.isEmpty()) {
            return remove(runId, claim, "no source it read says anything about this");
        }

        ClaimRevision revision = revise(runId, claim, firstVerdict, passages);
        if (revision == null || revision.action() == null) {
            say(runId, "Couldn't decide how to fix it; leaving it marked as not confirmed");
            return new Outcome(Kind.UNCHANGED, claim);
        }
        if (!"REVISE".equalsIgnoreCase(revision.action())) {
            return remove(runId, claim, "none of the sources back it up");
        }

        Set<String> offered = passages.stream().map(ClaimCorrector::urlOf).collect(Collectors.toSet());
        if (revision.text() == null || revision.text().isBlank() || !offered.contains(revision.sourceUrl())) {
            // A rewrite pointing at a URL it was never shown is exactly the
            // fabrication this project exists to catch -- don't accept it.
            say(runId, "The rewrite didn't point at a real source; leaving it marked as not confirmed");
            return new Outcome(Kind.UNCHANGED, claim);
        }

        UUID sourceId = jdbc.queryForObject(
                "INSERT INTO sources (run_id, url, tier) VALUES (?, ?, ?) "
                        + "ON CONFLICT (run_id, url) DO UPDATE SET tier = EXCLUDED.tier RETURNING id",
                UUID.class, runId, revision.sourceUrl(), tierByUrl.get(revision.sourceUrl()));
        jdbc.update("UPDATE claims SET original_text = text, text = ?, source_id = ?, correction = 'REVISED' "
                + "WHERE id = ?", revision.text(), sourceId, claim.id());
        say(runId, "Rewrote it as “" + revision.text() + "”, based on " + host(revision.sourceUrl()));
        return new Outcome(Kind.REVISED,
                new ClaimRow(claim.id(), revision.text(), claim.kind(), sourceId, revision.sourceUrl()));
    }

    private Map<String, Integer> knownTiers(UUID runId) {
        Map<String, Integer> tiers = new HashMap<>();
        jdbc.query("SELECT url, tier FROM sources WHERE run_id = ?",
                rs -> { tiers.put(rs.getString("url"), rs.getInt("tier")); }, runId);
        return tiers;
    }

    /** Adds up to NEW_PAGES_PER_CLAIM pages the run hasn't seen to the
     *  run's passages. Best effort: a failed search (including a fixture-
     *  mode miss) just means the rewrite works from what's already indexed. */
    private void searchAgain(UUID runId, ClaimRow claim, Map<String, Integer> tierByUrl) {
        if (!runUsageGuard.trySearch(runId)) {
            say(runId, "Search budget used up; working only from pages already read");
            return;
        }
        try {
            List<String> urls = retrievalClient.search(List.of(claim.text()), NEW_PAGES_PER_CLAIM * 2, null, 4)
                    .results().stream()
                    .map(SearchResult::url)
                    .filter(u -> !tierByUrl.containsKey(u))
                    .distinct()
                    .limit(NEW_PAGES_PER_CLAIM)
                    .toList();
            if (urls.isEmpty()) {
                say(runId, "Searched again but found no new pages");
                return;
            }
            List<String> read = new ArrayList<>();
            for (ExtractedDocument doc : retrievalClient.extract(urls).documents()) {
                if ("OK".equals(doc.status()) && doc.text() != null && !doc.text().isBlank()) {
                    passageStore.index(runId, doc.url(), doc.text());
                    tierByUrl.put(doc.url(), doc.tier());
                    read.add(host(doc.url()));
                }
            }
            say(runId, read.isEmpty()
                    ? "Searched again, but none of the new pages could be read"
                    : "Searched again and read " + read.size() + " new pages: " + String.join(", ", read));
        } catch (Exception e) {
            log.warn("Re-research search failed for claim {} in run {}", claim.id(), runId, e);
            say(runId, "Couldn't search again; working only from pages already read");
        }
    }

    private ClaimRevision revise(UUID runId, ClaimRow claim, Verdict firstVerdict, List<Document> passages) {
        if (!runUsageGuard.tryLlmCall(runId)) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        for (Document p : passages) {
            context.append("Source: ").append(urlOf(p)).append("\n").append(p.getText()).append("\n\n");
        }
        String system = "You repair a statement that failed a fact-check. You get the statement and "
                + "passages from web sources, each labelled with its source URL. If a passage directly "
                + "states a fact on the same topic, rewrite the statement as one short sentence that says "
                + "only what that passage says -- add nothing the passage doesn't state -- and give that "
                + "passage's source URL exactly as written. If no passage addresses the topic, drop it. "
                + "action is REVISE or DROP.";
        String user = "Statement: " + claim.text() + "\n"
                + "Fact-check result: " + firstVerdict + " against " + claim.sourceUrl() + "\n\n"
                + "Passages:\n" + context;
        try {
            ResponseEntity<ChatResponse, ClaimRevision> result = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .responseEntity(ClaimRevision.class);
            if (recordMode) {
                fixtureIO.record("chat-responses.json", FixtureIO.keyFor(system + "\n---\n" + user),
                        result.response().getResult().getOutput().getText());
            }
            return result.entity();
        } catch (Exception e) {
            log.warn("Claim revision failed for claim {} in run {}", claim.id(), runId, e);
            return null;
        }
    }

    private Outcome remove(UUID runId, ClaimRow claim, String why) {
        jdbc.update("UPDATE claims SET correction = 'REMOVED' WHERE id = ?", claim.id());
        say(runId, "Removed it from the answer: " + why);
        return new Outcome(Kind.REMOVED, claim);
    }

    private void say(UUID runId, String message) {
        activity.record(runId, null, "CRITIC", message);
    }

    private static String urlOf(Document passage) {
        return (String) passage.getMetadata().get("sourceUrl");
    }

    static String host(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
