package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.common.GuardrailMode;
import com.comeback.researchplatform.common.GuardrailProperties;
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

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private PageFetcher fetcher;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        fetcher = fetcherWithBlocking(true);
    }

    private PageFetcher fetcherWithBlocking(boolean blockPrivateNetworks) {
        ExtractProperties props = new ExtractProperties(
                "http://localhost:8000", MAX_BYTES,
                Duration.ofDays(7), Duration.ofSeconds(5), Duration.ofSeconds(10), 4);
        var retrieval = new GuardrailProperties.Retrieval(
                1000, 1.0, 3, 4, 5, Duration.ofSeconds(5), Duration.ofSeconds(15),
                MAX_BYTES, blockPrivateNetworks);
        var run = new GuardrailProperties.Run(Duration.ofMinutes(10), 40, 60, 8, 2, "PARTIAL");
        var agent = new GuardrailProperties.Agent(
                new GuardrailProperties.Agent.Researcher(Duration.ofSeconds(90), 25000, 5, "PARTIAL_LOW_CONFIDENCE"));
        var eval = new GuardrailProperties.Eval(0.85, 0.10);
        var guardrails = new GuardrailProperties(GuardrailMode.ENFORCE, run, agent, retrieval, eval);
        return new PageFetcher(builder.build(), props, guardrails);
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

    @Test
    void loopbackUrlIsBlockedWhenEnabled() {
        // No expectation set on the mock server — the request must never be made.
        FetchedPage page = fetcher.fetch("http://127.0.0.1:8080/secrets");

        assertThat(page.status()).isEqualTo(PageFetcher.STATUS_BLOCKED);
        assertThat(page.html()).isNull();
    }

    @Test
    void siteLocalUrlIsBlockedWhenEnabled() {
        FetchedPage page = fetcher.fetch("http://10.0.0.1/internal");

        assertThat(page.status()).isEqualTo(PageFetcher.STATUS_BLOCKED);
    }

    @Test
    void linkLocalUrlIsBlockedWhenEnabled() {
        FetchedPage page = fetcher.fetch("http://169.254.169.254/latest/meta-data/");

        assertThat(page.status()).isEqualTo(PageFetcher.STATUS_BLOCKED);
    }

    @Test
    void publicUrlIsNeverBlocked() {
        server.expect(requestTo("https://example.com/a"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>hello</html>"));

        FetchedPage page = fetcher.fetch("https://example.com/a");

        assertThat(page.isOk()).isTrue();
    }

    @Test
    void privateNetworkNotBlockedWhenGuardrailDisabled() {
        PageFetcher unguarded = fetcherWithBlocking(false);
        server.expect(requestTo("http://10.0.0.1/internal"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>internal</html>"));

        FetchedPage page = unguarded.fetch("http://10.0.0.1/internal");

        assertThat(page.isOk()).isTrue();
    }
}
