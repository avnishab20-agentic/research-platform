package com.comeback.researchplatform.retrievalservice.url;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Rewrites different spellings of the same page into one canonical URL, so they
 * share one cache entry. For example
 * {@code https://www.TheHindu.com/news/?utm_source=x#top} becomes
 * {@code https://thehindu.com/news}.
 */
public final class UrlNormalizer {

    // Tracking parameters: they change the URL but never the page.
    private static final Set<String> NOISE_PARAMS = Set.of("fbclid", "gclid", "ref");
    private static final String NOISE_PREFIX = "utm_";

    private UrlNormalizer() {
    }

    public static String normalize(String url) {
        URI uri = URI.create(url.trim());

        String scheme = uri.getScheme().toLowerCase();

        String host = host(url);
        if (host == null) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }

        String path = uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // The #fragment is dropped simply by never reading uri.getFragment().
        String query = normalizeQuery(uri.getQuery());
        if (query.isEmpty()) {
            return scheme + "://" + host + path;
        }
        return scheme + "://" + host + path + "?" + query;
    }

    /** Lowercase host without a leading "www.", or null when the URL has no host
     *  (e.g. {@code mailto:} or {@code about:blank}). */
    public static String host(String url) {
        URI uri = URI.create(url.trim());

        String rawHost = uri.getHost();
        if (rawHost == null) {
            return null;
        }

        String host = rawHost.toLowerCase();
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }

    /** Drops tracking parameters and sorts the rest, so their order doesn't matter. */
    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String param : query.split("&")) {
            if (!param.isBlank() && !isNoise(paramName(param))) {
                kept.add(param);
            }
        }
        Collections.sort(kept);
        return String.join("&", kept);
    }

    /** "page=2" gives "page". Checking only the name keeps a value like {@code ?q=gclid}. */
    private static String paramName(String param) {
        int eq = param.indexOf('=');
        if (eq < 0) {
            return param;
        }
        return param.substring(0, eq);
    }

    private static boolean isNoise(String name) {
        return name.startsWith(NOISE_PREFIX) || NOISE_PARAMS.contains(name);
    }
}
