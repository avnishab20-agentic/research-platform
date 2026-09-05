package com.comeback.researchplatform.retrievalservice.hash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void isStableAcrossCalls() {
        // Cache keys are built from this. If it ever stopped being deterministic, every
        // lookup would miss and the cache would silently become a very slow no-op.
        assertThat(Hashing.sha256Hex("rbi policy|"))
                .isEqualTo(Hashing.sha256Hex("rbi policy|"));
    }

    @Test
    void producesSixtyFourHexCharacters() {
        // SHA-256 is 32 bytes, two hex chars each. SearchService takes substring(0, 16),
        // so anything shorter than that would throw at runtime rather than here.
        assertThat(Hashing.sha256Hex("anything")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void differentInputsGiveDifferentHashes() {
        assertThat(Hashing.sha256Hex("rbi policy|"))
                .isNotEqualTo(Hashing.sha256Hex("rbi policy|day"));
    }

    @Test
    void handlesEmptyInput() {
        // freshness is null-guarded to "", so "query|" is a real input shape.
        assertThat(Hashing.sha256Hex("")).hasSize(64);
    }
}
