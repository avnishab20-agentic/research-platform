package com.comeback.researchplatform.common;

/**
 * A citation a researcher actually used: enough to persist a {@code sources} row
 * and for the Critic to re-fetch the same page later.
 */
public record SourceRef(
        String url,
        int tier
) {}
