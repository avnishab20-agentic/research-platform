package com.comeback.researchplatform.controlplane.web;

/** One graded claim for the Ask tab. section is the sub-question it answers
 *  (claims.section_heading), so the answer renders grouped by topic.
 *  correction is null, REVISED, or REMOVED; originalText is what the Writer
 *  first said when the Critic's correction round rewrote it (V4 migration). */
public record ClaimView(String text, String verdict, String sourceUrl, int tier, String evidencePassage,
                        String section, String originalText, String correction) {}
