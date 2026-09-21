package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.common.GuardrailProperties;
import com.comeback.researchplatform.retrievalservice.config.ExtractProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
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

    public static final String STATUS_BLOCKED = "BLOCKED_PRIVATE_NETWORK";

    private final RestClient client;
    private final int maxBytes;
    private final boolean blockPrivateNetworks;

    public PageFetcher(@Qualifier("pageFetchRestClient") RestClient client,
                       ExtractProperties props, GuardrailProperties guardrails) {
        this.client = client;
        this.maxBytes = props.maxDocumentBytes();
        this.blockPrivateNetworks = guardrails.retrieval().blockPrivateNetworks();
    }

    public FetchedPage fetch(String url) {
        // SSRF protection -- checked unconditionally when enabled, not gated
        // by guardrails.mode the way search/LLM-call ceilings are. A "shadow"
        // mode that logs-and-allows a request to 10.0.0.1 defeats the point.
        if (blockPrivateNetworks && targetsPrivateNetwork(url)) {
            return new FetchedPage(null, STATUS_BLOCKED);
        }
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

    /** DNS-resolves the URL's host and checks the resolved address against loopback,
     *  site-local (10.x/172.16-31.x/192.168.x), and link-local ranges. A resolution
     *  failure or hostless URL is NOT blocked here -- the real fetch attempt below
     *  will report it as UNREACHABLE on its own terms. */
    private boolean targetsPrivateNetwork(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
