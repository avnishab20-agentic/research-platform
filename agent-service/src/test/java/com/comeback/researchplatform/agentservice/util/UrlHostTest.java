package com.comeback.researchplatform.agentservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlHostTest {

    @Test
    void dropsTheSchemePathAndLeadingWww() {
        assertThat(UrlHost.of("https://www.rbi.org.in/Scripts/page.aspx?id=1")).isEqualTo("rbi.org.in");
    }

    @Test
    void keepsOtherSubdomains() {
        assertThat(UrlHost.of("https://m.thehindu.com/news")).isEqualTo("m.thehindu.com");
    }

    @Test
    void aUrlWithNoHostComesBackUnchanged() {
        assertThat(UrlHost.of("mailto:someone@example.com")).isEqualTo("mailto:someone@example.com");
    }

    @Test
    void anUnparseableUrlComesBackUnchanged() {
        assertThat(UrlHost.of("not a url")).isEqualTo("not a url");
    }
}
