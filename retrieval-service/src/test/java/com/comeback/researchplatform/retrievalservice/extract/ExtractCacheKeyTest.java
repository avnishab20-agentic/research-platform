package com.comeback.researchplatform.retrievalservice.extract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractCacheKeyTest {

    @Test
    void hasVersionedPrefixAndFixedLength() {
        String key = ExtractCacheKey.of("https://thehindu.com/news/rbi");
        assertTrue(key.startsWith("extract:v1:"));
        assertEquals("extract:v1:".length() + 16, key.length());
    }

    @Test
    void equivalentUrlsShareOneKey() {
        assertEquals(
                ExtractCacheKey.of("https://www.TheHindu.com/news/rbi/?utm_source=twitter#top"),
                ExtractCacheKey.of("https://thehindu.com/news/rbi"));
    }

    @Test
    void meaningfulParamsProduceDifferentKeys() {
        assertNotEquals(
                ExtractCacheKey.of("https://thehindu.com/news?page=1"),
                ExtractCacheKey.of("https://thehindu.com/news?page=2"));
    }
}
