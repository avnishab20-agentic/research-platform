package com.comeback.researchplatform.agentservice.writer;

import com.comeback.researchplatform.common.ClaimKind;

/**
 * The Writer's LLM call returns a list of these -- structured output parsed
 * from a single prompt per finding. {@code sourceUrl} must be one of the
 * URLs the model was given (the finding's own cited sources); the Writer
 * resolves it to a real {@code sources.id} row before persisting.
 */
public record ExtractedClaim(String text, ClaimKind kind, String sourceUrl) {}
