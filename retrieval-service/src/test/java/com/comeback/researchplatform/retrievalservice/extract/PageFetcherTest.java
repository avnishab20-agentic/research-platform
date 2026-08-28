package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * PageFetcher's whole job is turning HTTP outcomes into a status string, so the tests
 * are one per outcome. No Spring context: the class takes a RestClient in its
 * constructor, so a test can hand it a fake one.
 */
class PageFetcherTest {

    private static final int MAX_BYTES = 100;

    private MockRestServiceServer server;
    private PageFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ExtractProperties props = new ExtractProperties(
                "http://localhost:8000", MAX_BYTES,
                Duration.ofDays(7), Duration.ofSeconds(5), Duration.ofSeconds(10));
        fetcher = new PageFetcher(builder.build(), props);
    }

    @Test
    void returnsOkWithHtmlOnSuccess() {
        server.expect(requestTo("https://example.com/a"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>hello</html>"));

        FetchedPage page = fetcher.fetch("https://example.com/a");

        assertThat(page.isOk()).isTrue();
        assertThat(page.html()).isEqualTo("<html>hello</html>");
    }

    @Test
    void notFoundIsUnreachableNotAnException() {
        server.expect(requestTo("https://example.com/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        FetchedPage page = fetcher.fetch("https://example.com/missing");

        assertThat(page.status()).isEqualTo("UNREACHABLE");
        assertThat(page.html()).isNull();
    }

    @Test
    void serverErrorIsUnreachable() {
        server.expect(requestTo("https://example.com/boom"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(fetcher.fetch("https://example.com/boom").status()).isEqualTo("UNREACHABLE");
    }

    @Test
    void bodyOverTheCapIsTooLarge() {
        server.expect(requestTo("https://example.com/big"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("x".repeat(MAX_BYTES + 50)));

        FetchedPage page = fetcher.fetch("https://example.com/big");

        assertThat(page.status()).isEqualTo("TOO_LARGE");
        assertThat(page.html()).isNull();
    }

    @Test
    void bodyExactlyAtTheCapIsStillOk() {
        server.expect(requestTo("https://example.com/edge"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("x".repeat(MAX_BYTES)));

        assertThat(fetcher.fetch("https://example.com/edge").isOk()).isTrue();
    }

    @Test
    void malformedUrlIsUnreachable() {
        // Fails inside URI.create, before any request is made — so no expectation is set.
        FetchedPage page = fetcher.fetch("not a url at all");

        assertThat(page.status()).isEqualTo("UNREACHABLE");
    }
}
