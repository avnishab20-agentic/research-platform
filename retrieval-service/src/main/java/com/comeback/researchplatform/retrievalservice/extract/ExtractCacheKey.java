package com.comeback.researchplatform.retrievalservice.extract;

import com.comeback.researchplatform.retrievalservice.hash.Hashing;
import com.comeback.researchplatform.retrievalservice.url.UrlNormalizer;

public final class ExtractCacheKey {

    private static final String PREFIX = "extract:v1:";

    private ExtractCacheKey() {
    }

    public static String of(String url) {
        return PREFIX + Hashing.sha256Hex(UrlNormalizer.normalize(url)).substring(0, 16);
    }
}
