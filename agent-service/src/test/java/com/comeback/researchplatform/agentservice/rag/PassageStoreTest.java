package com.comeback.researchplatform.agentservice.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PassageStoreTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final PassageStore store = new PassageStore(vectorStore);
    private final UUID runId = UUID.randomUUID();

    @Test
    @SuppressWarnings("unchecked")
    void splitsOnBlankLinesAndHardSplitsLongParagraphs() {
        String longParagraph = "x".repeat(1000);
        store.index(runId, "https://rbi.org.in", "First paragraph.\n\n  \n\n" + longParagraph);

        ArgumentCaptor<List<Document>> stored = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(stored.capture());
        // "First paragraph." + the 1000-char paragraph split into 800 + 200.
        assertThat(stored.getValue()).hasSize(3);
        assertThat(stored.getValue().get(1).getText()).hasSize(800);
        assertThat(stored.getValue().get(0).getMetadata())
                .containsEntry("runId", runId.toString())
                .containsEntry("sourceUrl", "https://rbi.org.in");
    }

    @Test
    void blankTextStoresNothing() {
        store.index(runId, "https://rbi.org.in", "   ");
        store.index(runId, "https://rbi.org.in", null);

        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void retrievalIsFilteredToTheRun() {
        store.retrieveTopK(runId, "repo rate", 4);

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(4);
        assertThat(request.getValue().getFilterExpression().toString()).contains(runId.toString());
    }
}
