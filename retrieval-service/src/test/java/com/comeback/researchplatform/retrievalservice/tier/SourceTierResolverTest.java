package com.comeback.researchplatform.retrievalservice.tier;

import com.comeback.researchplatform.retrievalservice.config.SourceTierProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceTierResolverTest {

    private final SourceTierProperties tierProperties = new SourceTierProperties(
            List.of("pib.gov.in", "rbi.org.in", "isro.gov.in"),
            List.of("thehindu.com", "indianexpress.com", "ndtv.com"),
            List.of("*.blogspot.*", "*wordpress.com")
    );

    private final SourceTierResolver resolver = new SourceTierResolver(tierProperties);

    @Test
    void resolvesTier1ForExactDomainMatch() {
        assertEquals(1, resolver.resolveTier("https://pib.gov.in/press-release"));
    }

    @Test
    void resolvesTier2AndStripsWwwPrefix() {
        assertEquals(2, resolver.resolveTier("https://www.thehindu.com/news/national/article"));
    }

    @Test
    void resolvesTier4ForBlogspotPattern() {
        assertEquals(4, resolver.resolveTier("https://someblog.blogspot.com/2026/08/post.html"));
    }

    @Test
    void resolvesTier4ForWordpressPattern() {
        assertEquals(4, resolver.resolveTier("https://myblog.wordpress.com/hello-world"));
    }

    @Test
    void defaultsToTier3WhenNoMatch() {
        assertEquals(3, resolver.resolveTier("https://example.com/random-article"));
    }
}