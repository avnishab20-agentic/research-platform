package com.comeback.researchplatform.agentservice.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shared RAG piece: chunk a document once, embed and store the chunks,
 * then retrieve the passages closest to a query. Used by both the Researcher
 * (query = a sub-question, grounds the synthesized answer) and the Critic
 * (query = a claim's text, grounds a verdict) -- see CLAUDE.md's RAG
 * architecture-decision note for the reasoning.
 * <p>
 * Every stored chunk is tagged with {@code runId} and every retrieval filters
 * on it, so one run's documents can never leak into another run's search
 * results.
 */
@Component
public class PassageStore {

    private static final int CHUNK_SIZE_CHARS = 800;
    private static final String META_RUN_ID = "runId";
    private static final String META_SOURCE_URL = "sourceUrl";

    private final VectorStore vectorStore;

    public PassageStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** Splits {@code text} into paragraph-sized chunks, embeds them, and stores
     *  them tagged with {@code runId} and {@code sourceUrl}. */
    public void index(UUID runId, String sourceUrl, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<Document> documents = new ArrayList<>();
        for (String passage : chunk(text)) {
            Map<String, Object> metadata = Map.of(META_RUN_ID, runId.toString(), META_SOURCE_URL, sourceUrl);
            documents.add(new Document(passage, metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    /** Returns the {@code k} passages closest in meaning to {@code query},
     *  restricted to chunks indexed under this {@code runId}. */
    public List<Document> retrieveTopK(UUID runId, String query, int k) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(META_RUN_ID, runId.toString())
                .build();
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(k)
                .filterExpression(filter)
                .build());
    }

    /**
     * Paragraph-first splitting: break on blank lines, then hard-split any
     * paragraph still over {@code CHUNK_SIZE_CHARS} so one giant paragraph
     * can't become one giant (and therefore diluted) embedding.
     * ponytail: naive char-count splitting, not sentence/token aware --
     * upgrade to a real text splitter if chunk quality shows up as a problem.
     */
    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (int i = 0; i < trimmed.length(); i += CHUNK_SIZE_CHARS) {
                chunks.add(trimmed.substring(i, Math.min(i + CHUNK_SIZE_CHARS, trimmed.length())));
            }
        }
        return chunks;
    }
}
