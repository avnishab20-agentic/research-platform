package com.comeback.researchplatform.controlplane.web;

import java.util.List;
import java.util.UUID;

public record RunReportResponse(UUID id, String question, String status, List<ClaimView> claims) {}
