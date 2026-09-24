package com.comeback.researchplatform.agentservice.retrieval;

import com.comeback.researchplatform.agentservice.fixtures.FixtureIO;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.ExtractedDocument;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResponse;
import com.comeback.researchplatform.agentservice.retrieval.dto.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Both RetrievalClient versions: the real HTTP one and the fixture replay one. */
class RetrievalClientsTest {

    private static final String URL = "https://rbi.org.in/repo-rate";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SearchResponse searchResponse =
            new SearchResponse(List.of(new SearchResult(URL, "RBI", "snippet", 1)), 1, 0);
    private final ExtractedDocument document = new ExtractedDocument(URL, "The repo rate is 6.5%.", 1, "OK");

    @Test
    void httpClientCallsRetrievalServiceAndRecordsWhenAsked() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://retrieval.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://retrieval.test/api/v1/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(searchResponse), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://retrieval.test/api/v1/extract"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(new ExtractResponse(List.of(document))),
                        MediaType.APPLICATION_JSON));
        FixtureIO fixtureIO = mock(FixtureIO.class);
        when(fixtureIO.isRecordMode()).thenReturn(true);

        HttpRetrievalClient client = new HttpRetrievalClient(builder.build(), fixtureIO);

        assertThat(client.search(List.of("repo rate"), 5, null, 4)).isEqualTo(searchResponse);
        assertThat(client.extract(List.of(URL)).documents()).containsExactly(document);
        server.verify();
        verify(fixtureIO).record("search-responses.json", FixtureIO.keyFor("repo rate"), searchResponse);
        verify(fixtureIO).record("extract-responses.json", FixtureIO.keyFor(URL), document);
    }

    @Test
    void httpClientDoesNotRecordOutsideRecordMode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://retrieval.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://retrieval.test/api/v1/search"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(searchResponse), MediaType.APPLICATION_JSON));
        FixtureIO fixtureIO = mock(FixtureIO.class);

        new HttpRetrievalClient(builder.build(), fixtureIO).search(List.of("repo rate"), 5, null, 4);

        verify(fixtureIO, never()).record(anyString(), anyString(), any());
    }

    @Test
    void fixtureClientReplaysRecordedResponses() {
        FixtureIO fixtureIO = mock(FixtureIO.class);
        when(fixtureIO.read("search-responses.json", FixtureIO.keyFor("a|b"), SearchResponse.class))
                .thenReturn(searchResponse);
        when(fixtureIO.read(eq("extract-responses.json"), eq(FixtureIO.keyFor(URL)), eq(ExtractedDocument.class)))
                .thenReturn(document);

        FixtureRetrievalClient client = new FixtureRetrievalClient(fixtureIO);

        assertThat(client.search(List.of("a", "b"), 5, null, 4)).isEqualTo(searchResponse);
        assertThat(client.extract(List.of(URL)).documents()).containsExactly(document);
    }

    @Test
    void aDocumentIsUsableOnlyWhenReadOkAndNotBlank() {
        assertThat(document.isUsable()).isTrue();
        assertThat(new ExtractedDocument(URL, "text", 1, "PAYWALLED").isUsable()).isFalse();
        assertThat(new ExtractedDocument(URL, null, 1, "OK").isUsable()).isFalse();
        assertThat(new ExtractedDocument(URL, "  ", 1, "OK").isUsable()).isFalse();
    }
}
