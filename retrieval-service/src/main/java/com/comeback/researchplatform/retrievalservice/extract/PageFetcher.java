package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Fetches raw HTML for one URL.
 * <p>
 * Never throws: every failure mode comes back as a {@link FetchedPage} with a status
 * the caller can act on. A dead URL is a result, not an exception — one bad link in a
 * batch of five must not sink the other four.
 */
@Component
public class PageFetcher {

    private final RestClient client;
    private final int maxBytes;

    public PageFetcher(@Qualifier("pageFetchRestClient") RestClient client,
                       ExtractProperties props) {
        this.client = client;
        this.maxBytes = props.maxDocumentBytes();
    }

    public FetchedPage fetch(String url) {
        try {
            return client.get()
                    // URI.create, not the raw String: a String is treated as a URI *template*,
                    // so a real-world URL containing { or } would blow up during expansion.
                    .uri(URI.create(url))
                    // exchange(), not retrieve(): retrieve() throws on 4xx/5xx, but here a 404
                    // is an answer ("UNREACHABLE"), not an error to unwind the stack for.
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return new FetchedPage(null, "UNREACHABLE");
                        }
                        // Cheap guard first: if the server declared an oversized body,
                        // bail before reading a single byte of it.
                        if (response.getHeaders().getContentLength() > maxBytes) {
                            return new FetchedPage(null, "TOO_LARGE");
                        }
                        // Chunked responses declare no Content-Length (-1 above), so the size
                        // limit also has to hold while reading. Asking for maxBytes + 1 means
                        // getting that many proves we're over the limit, while never buffering
                        // more than 200KB + 1 no matter how large the page actually is.
                        byte[] body = response.getBody().readNBytes(maxBytes + 1);
                        if (body.length > maxBytes) {
                            return new FetchedPage(null, "TOO_LARGE");
                        }
                        return new FetchedPage(new String(body, StandardCharsets.UTF_8), "OK");
                    });
        } catch (Exception e) {
            // Everything that fails before or during transport: unknown host, connect timeout,
            // read timeout, malformed URL, connection reset mid-download.
            return new FetchedPage(null, "UNREACHABLE");
        }
    }
}
