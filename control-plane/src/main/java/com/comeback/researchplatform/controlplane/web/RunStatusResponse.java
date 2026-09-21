package com.comeback.researchplatform.controlplane.web;

import java.util.UUID;

public record RunStatusResponse(UUID id, String question, String status) {}
