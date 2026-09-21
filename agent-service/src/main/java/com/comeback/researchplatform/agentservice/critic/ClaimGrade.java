package com.comeback.researchplatform.agentservice.critic;

import com.comeback.researchplatform.common.Verdict;

/**
 * One claim's grade, as returned by a batched LLM call. {@code index} is
 * the claim's position within its batch (1-based, matching the prompt),
 * not a database id -- UUIDs round-trip unreliably through model output,
 * an index the model just has to copy back does not.
 */
public record ClaimGrade(int index, Verdict verdict, String evidencePassage) {}
