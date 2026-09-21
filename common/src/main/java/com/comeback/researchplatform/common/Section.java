package com.comeback.researchplatform.common;

import java.util.List;

/**
 * {@code narrative} is connective tissue only — no facts belong in it. Every
 * fact lives in a {@link Claim}, where it can carry a source and a verdict.
 */
public record Section(
        String heading,
        List<Claim> claims,
        String narrative
) {}
