package com.comeback.researchplatform.retrievalservice;

import com.comeback.researchplatform.common.GuardrailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
// GuardrailProperties lives in common, outside this service's own scanned
// package, so @ConfigurationPropertiesScan alone won't find it -- registered
// explicitly instead.
@EnableConfigurationProperties(GuardrailProperties.class)
public class RetrievalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalServiceApplication.class, args);
    }

}
