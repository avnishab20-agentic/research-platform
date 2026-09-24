package com.comeback.researchplatform.agentservice.retrieval.dto;

/** Named ExtractedDocument, not Document -- Spring AI's own vector-store
 *  Document type (org.springframework.ai.document.Document) is used right
 *  next to this one in the researcher loop, and two classes both called
 *  "Document" in the same file is exactly the kind of ambiguity worth a
 *  rename to avoid. Field shape mirrors retrieval-service's Document DTO. */
public record ExtractedDocument(String url, String text, int tier, String status) {

    /** True when the page was read successfully and has some text in it. */
    public boolean isUsable() {
        return "OK".equals(status) && text != null && !text.isBlank();
    }
}
