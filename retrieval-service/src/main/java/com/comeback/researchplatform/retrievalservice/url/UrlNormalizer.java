package com.comeback.researchplatform.retrievalservice.url;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class UrlNormalizer {

    private static final Set<String> NOISE_PARAMS = Set.of("fbclid", "gclid", "ref");
    private static final String NOISE_PREFIX = "utm_";

    private UrlNormalizer() {
    }

    public static String normalize(String url) {
        URI uri = URI.create(url.trim());

        String scheme = uri.getScheme().toLowerCase();

        String host = host(url);
        if(host ==null){
            throw new IllegalArgumentException("URL has no host: " + url);
        }
        String path = uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String query = normalizeQuery(uri.getQuery());

        return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + query);

    }
        private static String normalizeQuery (String query){
            if (query == null || query.isBlank()) {
                return "";
            }
            return Arrays.stream(query.split("&"))
                    .filter(param -> !param.isBlank())
                    .filter(param -> !isNoise(paramName(param)))
                    .sorted()
                    .collect(Collectors.joining("&"));
        }

        private static String paramName (String param){
            int eq = param.indexOf('=');
            return eq < 0 ? param : param.substring(0, eq);
        }

        private static boolean isNoise (String name){
            return name.startsWith(NOISE_PREFIX) || NOISE_PARAMS.contains(name);
        }


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
}

