package com.comeback.researchplatform.retrievalservice.url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlNormalizerTest {

    @Test
    void lowercasesHostButNotPath() {
        assertEquals(
                "https://thehindu.com/news/RBI-Policy",
                UrlNormalizer.normalize("https://TheHindu.COM/news/RBI-Policy"));
    }

    @Test
    void stripsFragment() {
        assertEquals(
                "https://thehindu.com/news/rbi",
                UrlNormalizer.normalize("https://thehindu.com/news/rbi#section-2"));
    }

    @Test
    void stripsUtmParams() {
        assertEquals(
                "https://thehindu.com/news/rbi",
                UrlNormalizer.normalize("https://thehindu.com/news/rbi?utm_source=twitter&utm_campaign=x"));
    }

    @Test
    void stripsClickIdsAndRef() {
        assertEquals(
                "https://thehindu.com/news/rbi",
                UrlNormalizer.normalize("https://thehindu.com/news/rbi?fbclid=IwAR3xY&gclid=abc&ref=home"));
    }

    @Test
    void keepsMeaningfulParams() {
        assertEquals(
                "https://thehindu.com/news?id=447&page=2",
                UrlNormalizer.normalize("https://thehindu.com/news?page=2&id=447&utm_source=fb"));
    }

    @Test
    void stripsTrailingSlash() {
        assertEquals(
                "https://thehindu.com/news/rbi",
                UrlNormalizer.normalize("https://thehindu.com/news/rbi/"));
    }

    @Test
    void sortsParamsAlphabetically() {
        assertEquals(
                "https://thehindu.com/news?a=1&b=2",
                UrlNormalizer.normalize("https://thehindu.com/news?b=2&a=1"));
    }

    @Test
    void kitchenSink() {
        assertEquals(
                "https://thehindu.com/news/RBI?id=447&page=2",
                UrlNormalizer.normalize("HTTPS://www.TheHindu.com/news/RBI/?page=2&utm_source=x&id=447&fbclid=z#top"));
    }

    // host() is public API in its own right — DomainRateLimiter keys its buckets on it,
    // and SourceTierResolver should. These pin it directly rather than through normalize().

    @Test
    void hostLowercasesAndStripsWww() {
        assertEquals("thehindu.com", UrlNormalizer.host("HTTPS://WWW.TheHindu.com/news/RBI"));
    }

    @Test
    void hostIgnoresPathQueryAndFragment() {
        // Everything after the domain is irrelevant to "which server is this?", which is
        // what makes one bucket per domain possible.
        assertEquals("thehindu.com",
                UrlNormalizer.host("https://thehindu.com/news/rbi?page=2&utm_source=x#top"));
    }

    @Test
    void hostKeepsSubdomainsThatAreNotWww() {
        // Only a leading "www." is noise. m.thehindu.com is a different host and stays one.
        assertEquals("m.thehindu.com", UrlNormalizer.host("https://m.thehindu.com/news"));
    }

    @Test
    void hostReturnsNullWhenThereIsNoHost() {
        // Legal URIs with nothing where a hostname would go. Callers decide what this
        // means: DomainRateLimiter refuses the call, normalize() throws.
        assertNull(UrlNormalizer.host("mailto:someone@example.com"));
        assertNull(UrlNormalizer.host("about:blank"));
    }

    @Test
    void normalizeThrowsAClearErrorForAHostlessUrl() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> UrlNormalizer.normalize("mailto:someone@example.com"));

        // The message must name the offending URL — this used to die as a bare NPE.
        assertTrue(thrown.getMessage().contains("mailto:someone@example.com"));
    }
}
