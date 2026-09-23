package com.comeback.researchplatform.agentservice.writer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * maxClaimsPerFinding caps how many statements the Writer keeps per sub-question.
 * Every statement costs a Critic grading slot (with its source passages), so this
 * is the main lever on LLM spend per run -- uncapped, runs produced ~90 claims,
 * many of them the same fact reworded.
 */
@ConfigurationProperties(prefix = "writer")
public record WriterProperties(int maxClaimsPerFinding) {}
