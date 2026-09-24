package com.comeback.researchplatform.agentservice.util;

import java.net.URI;

/** Turns a URL into a short site name for the activity feed, e.g.
 *  "https://www.rbi.org.in/page" becomes "rbi.org.in". */
public final class UrlHost {

    private UrlHost() {
    }

    /** Falls back to the full URL when it has no host or can't be parsed. */
    public static String of(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return url;
            }
            if (host.startsWith("www.")) {
                return host.substring(4);
            }
            return host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
