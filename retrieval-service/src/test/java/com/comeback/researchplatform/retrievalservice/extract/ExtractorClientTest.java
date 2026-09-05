package com.comeback.researchplatform.retrievalservice.extract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The client itself is five lines with no branches, so there is little logic to test.
 * What these tests actually guard is the JSON contract with the Python sidecar — the
 * field names on both sides, which no compiler checks.
 */
class ExtractorClientTest {

    private static final String BASE_URL = "http://extractor.test";

    private MockRestServiceServer server;
    private ExtractorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ExtractorClient(builder.build());
    }

    @Test
    void postsUrlAndHtmlToTheSidecar() {
        server.expect(requestTo(BASE_URL + "/extract"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.url").value("https://thehindu.com/a"))
                .andExpect(jsonPath("$.html").value("<html>hi</html>"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"url":"https://thehindu.com/a","title":"T",
                                 "text":"clean","published_at":null,"status":"OK"}"""));

        client.extract("https://thehindu.com/a", "<html>hi</html>");

        server.verify();
    }

    @Test
    void mapsTheSidecarResponseIntoTheRecord() {
        server.expect(requestTo(BASE_URL + "/extract"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"url":"https://thehindu.com/a","title":"Title",
                                 "text":"clean text","status":"PAYWALLED"}"""));

        ExtractorResult result = client.extract("https://thehindu.com/a", "<html>hi</html>");

        // Jackson matches JSON keys to record components case-sensitively. A component
        // accidentally named Status or Text would compile fine and be null forever, so
        // these assertions are the only thing standing between us and a silent null.
        assertThat(result.url()).isEqualTo("https://thehindu.com/a");
        assertThat(result.title()).isEqualTo("Title");
        assertThat(result.text()).isEqualTo("clean text");
        assertThat(result.status()).isEqualTo("PAYWALLED");
    }
}
