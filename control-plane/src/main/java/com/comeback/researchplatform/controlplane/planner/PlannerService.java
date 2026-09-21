package com.comeback.researchplatform.controlplane.planner;

import com.comeback.researchplatform.common.KafkaTopics;
import com.comeback.researchplatform.common.ResearchSubtask;
import com.comeback.researchplatform.controlplane.config.PlannerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Takes a top-level question, decomposes it into a flat list of
 * sub-questions (level 0 only -- CLAUDE.md: "flat fan-out only, keep the
 * depends_on/level columns unused"), and fans them out onto
 * research.subtasks. This is the producer side of the fan-out/fan-in pair;
 * {@link com.comeback.researchplatform.controlplane.fanin.FanInService} is
 * the consumer side.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final ChatClient chatClient;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PlannerProperties props;

    public PlannerService(ChatClient.Builder chatClientBuilder, JdbcTemplate jdbc,
                           KafkaTemplate<String, Object> kafkaTemplate, PlannerProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.props = props;
    }

    @Transactional
    public UUID submit(String question) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO runs (id, question, status) VALUES (?, ?, 'RUNNING')", runId, question);

        List<String> subQuestions = decompose(question);
        if (subQuestions.isEmpty()) {
            // Never publish a run with zero sub-questions and no way to ever
            // complete it -- fail the run outright instead of leaving it
            // stuck PENDING forever with no dag_levels row to sweep.
            jdbc.update("UPDATE runs SET status = 'PARTIAL', updated_at = now() WHERE id = ?", runId);
            log.warn("Planner produced no sub-questions for run {}; marked PARTIAL", runId);
            return runId;
        }

        List<UUID> nodeIds = new ArrayList<>();
        for (String subQuestion : subQuestions) {
            UUID nodeId = UUID.randomUUID();
            jdbc.update("INSERT INTO dag_nodes (id, run_id, level, sub_question, status) "
                    + "VALUES (?, ?, 0, ?, 'PENDING')", nodeId, runId, subQuestion);
            nodeIds.add(nodeId);
        }

        // java.sql.Timestamp, not Instant -- the Postgres driver can't infer
        // a SQL type for a raw Instant parameter (see FanInService for the
        // same fix, found by actually running this).
        java.sql.Timestamp deadline = java.sql.Timestamp.from(Instant.now().plus(props.levelDeadline()));
        jdbc.update("INSERT INTO dag_levels (run_id, level, expected, completed, deadline) "
                + "VALUES (?, 0, ?, 0, ?)", runId, nodeIds.size(), deadline);

        for (int i = 0; i < nodeIds.size(); i++) {
            ResearchSubtask subtask = new ResearchSubtask(runId, nodeIds.get(i), 0, subQuestions.get(i));
            kafkaTemplate.send(KafkaTopics.RESEARCH_SUBTASKS, runId.toString(), subtask);
        }

        log.info("Run {} planned: {} sub-questions fanned out", runId, nodeIds.size());
        return runId;
    }

    private List<String> decompose(String question) {
        try {
            String text = chatClient.prompt()
                    .system("You break a research question into independent sub-questions. "
                            + "Reply with one sub-question per line, no numbering, no extra text. "
                            + "Each sub-question must be answerable on its own, without needing "
                            + "the answer to any other sub-question first.")
                    .user("Question: " + question + "\n\nGenerate between " + props.minFanOut()
                            + " and " + props.maxFanOut() + " sub-questions.")
                    .call()
                    .content();
            if (text == null) {
                return List.of();
            }
            return Arrays.stream(text.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(props.maxFanOut())
                    .toList();
        } catch (Exception e) {
            log.warn("Planner decomposition failed for question '{}'", question, e);
            return List.of();
        }
    }
}
