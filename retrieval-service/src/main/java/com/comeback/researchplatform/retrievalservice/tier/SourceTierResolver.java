package com.comeback.researchplatform.retrievalservice.tier;

import com.comeback.researchplatform.retrievalservice.config.SourceTierProperties;
import com.comeback.researchplatform.retrievalservice.url.UrlNormalizer;
import org.springframework.stereotype.Component;

/**
 * Gives a URL a trust tier from the {@code source-tiers} config:
 * 1 = official source, 2 = established news, 3 = unknown (the default),
 * 4 = low-trust pattern such as a free blog host.
 */
@Component
public class SourceTierResolver {

    private static final int DEFAULT_TIER = 3;

    private final SourceTierProperties tierProperties;

    public SourceTierResolver(SourceTierProperties tierProperties) {
        this.tierProperties = tierProperties;
    }

    public int resolveTier(String url) {
        String host = UrlNormalizer.host(url);
        if (host == null) {
            return DEFAULT_TIER;
        }

        // Tiers 1 and 2 are exact domain names, checked in order.
        if (tierProperties.tier1().contains(host)) {
            return 1;
        }
        if (tierProperties.tier2().contains(host)) {
            return 2;
        }

        // Tier 4 entries are glob patterns like "*.blogspot.*", so each needs a match test.
        for (String pattern : tierProperties.tier4Patterns()) {
            if (matchesPattern(host, pattern)) {
                return 4;
            }
        }
        return DEFAULT_TIER;
    }

    /** Java has no built-in glob matcher for strings, so turn the glob into a regex:
     *  a literal "." becomes "\." and "*" becomes ".*" (match anything). */
    private boolean matchesPattern(String host, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return host.matches(regex);
    }
}
