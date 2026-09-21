package com.comeback.researchplatform.common;

import java.util.List;
import java.util.UUID;

/**
 * One atomic, checkable statement. Exactly one {@code sourceId} — a claim that
 * needs two sources is two claims. Free prose with footnotes makes verification
 * impossible; this is why the writer emits claims, not prose.
 */
public record Claim(
        UUID id,
        String text,
        UUID sourceId,
        List<UUID> corroborating,
        ClaimKind kind
) {}
