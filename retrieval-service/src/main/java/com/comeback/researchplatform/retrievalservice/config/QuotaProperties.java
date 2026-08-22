package com.comeback.researchplatform.retrievalservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "quota")
public record QuotaProperties (int dailyLimit){

}
