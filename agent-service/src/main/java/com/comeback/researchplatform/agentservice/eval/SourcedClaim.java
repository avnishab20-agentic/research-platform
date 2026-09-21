package com.comeback.researchplatform.agentservice.eval;

import java.util.List;

/** One known-good claim plus the evidence passages that support it --
 *  FabricationEval's input shape, deliberately decoupled from the real
 *  Claim/ClaimRow types so this eval can run standalone against synthetic
 *  data, not just real persisted claims. */
public record SourcedClaim(String text, List<String> evidence, String sourceUrl) {}
