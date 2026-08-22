package com.comeback.researchplatform.retrievalservice.url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
