package com.comeback.researchplatform.retrievalservice.config;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "source-tiers")
public record SourceTierProperties(List<String> tier1, List<String> tier2, List<String> tier4Patterns){

}