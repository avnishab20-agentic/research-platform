package com.comeback.researchplatform.controlplane.web;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** conclusion is the runs.conclusion JSON passed through as-is (null for older runs). */
public record RunReportResponse(UUID id, String question, String status, JsonNode conclusion, List<ClaimView> claims) {}
