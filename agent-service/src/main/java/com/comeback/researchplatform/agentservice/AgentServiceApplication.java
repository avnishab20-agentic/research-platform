package com.comeback.researchplatform.agentservice;

import com.comeback.researchplatform.common.GuardrailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
// GuardrailProperties lives in common, outside this service's own scanned
// package -- registered explicitly (see RetrievalServiceApplication for
// the same pattern).
@EnableConfigurationProperties(GuardrailProperties.class)
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }

}
