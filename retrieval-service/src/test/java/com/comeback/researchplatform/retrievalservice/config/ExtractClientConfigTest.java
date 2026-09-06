package com.comeback.researchplatform.retrievalservice.config;

import com.comeback.researchplatform.retrievalservice.extract.ExtractorClient;
import com.comeback.researchplatform.retrievalservice.extract.ExtractorResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the transport, not the payload — the half {@link ExtractorClient}'s own test
 * cannot see.
 * <p>
 * {@code ExtractorClientTest} binds {@code MockRestServiceServer} to the builder, and doing
 * that <b>replaces the request factory</b>: the JSON is matched in memory and nothing is
 * ever put on a socket. So it passed for four sessions while {@code /api/v1/extract} was
 * broken against the real sidecar.
 * <p>
 * The bug: Spring's default request factory wraps the JDK's {@code HttpClient}, which opens
 * every cleartext request with {@code Upgrade: h2c} and sends the body chunked. uvicorn
 * (h11) does not speak h2c — it logged "Unsupported upgrade request", discarded the body,
 * and FastAPI answered {@code 422 {"loc":["body"],"msg":"Field required"}}. RestClient threw
 * on the 422, {@code ExtractService} caught it, and a perfectly reachable page was reported
 * {@code UNREACHABLE}.
 * <p>
 * These tests therefore run the bean {@link ExtractClientConfig} actually builds against a
 * real socket (the JDK's own {@code HttpServer} — no new dependency, no Docker).
 * <p>
 * <b>Which assertion is load-bearing, verified by reverting the fix:</b> only
 * {@link #doesNotOfferAnHttp2Upgrade()} goes red. The JDK's {@code HttpServer} is a
 * well-behaved HTTP/1.1 server — it ignores the upgrade offer and reads the chunked body
 * correctly — so the body still arrives here even with the broken config. It is uvicorn
 * specifically that chokes. Reproducing that faithfully would mean running uvicorn from a
 * test, which needs Python and Docker; asserting on the header instead catches the same
 * regression with nothing but the JDK. Do not "simplify" this class down to the body check.
 */
class ExtractClientConfigTest {

    private HttpServer server;
    private ExtractorClient client;

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> receivedHeaders = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/extract", exchange -> {
            receivedHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] response = ("{\"url\":\"https://thehindu.com/a\",\"title\":\"T\","
                    + "\"text\":\"body text\",\"publishedAt\":\"2026-09-04\",\"status\":\"OK\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        // The real bean, from the real config class — if anyone drops the explicit request
        // factory, these tests go red rather than the sidecar going quietly UNREACHABLE.
        RestClient restClient = new ExtractClientConfig().extractorRestClient(
                new ExtractProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                        204800, Duration.ofDays(7), Duration.ofSeconds(5), Duration.ofSeconds(10)));
        client = new ExtractorClient(restClient);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theBodyActuallyArrivesAtTheServer() {
        client.extract("https://thehindu.com/a", "<html>hi</html>");

        // Documents the symptom seen in production — FastAPI answered "body: Field
        // required" — but note this assertion does NOT fail against the broken config:
        // the JDK's HttpServer reads the chunked body that uvicorn discards. The header
        // test below is the actual guard. This one is here to pin the JSON field names
        // on a real socket, which the MockRestServiceServer test cannot do.
        assertThat(receivedBody.get())
                .as("sidecar must receive a body, not an empty request")
                .isNotEmpty()
                .contains("\"url\":\"https://thehindu.com/a\"")
                .contains("\"html\":\"<html>hi</html>\"");
    }

    @Test
    void doesNotOfferAnHttp2Upgrade() {
        client.extract("https://thehindu.com/a", "<html>hi</html>");

        // THE GUARD. uvicorn logs "Unsupported upgrade request" and drops the body when it
        // sees this header, so the offer itself is the defect — and it is observable from
        // any server, which the dropped body is not. Reverting the requestFactory line in
        // ExtractClientConfig turns this test, and only this test, red.
        assertThat(receivedHeaders.get().keySet())
                .as("no h2c upgrade offer — the sidecar cannot honour one")
                .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("Upgrade"));
    }

    @Test
    void parsesTheSidecarResponse() {
        ExtractorResult result = client.extract("https://thehindu.com/a", "<html>hi</html>");

        // Round trip over a real socket, so this also covers the response side: field names
        // are matched case-sensitively, which is the trap that made publishedAt null before.
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.text()).isEqualTo("body text");
        assertThat(result.publishedAt()).isEqualTo("2026-09-04");
    }
}
