package com.comeback.researchplatform.controlplane.web;

/** One graded claim, shaped for the Verify tab's Claims panel -- mirrors
 *  the static UI's scripted mock CLAIMS shape (text/verdict/source/tier/
 *  evidence) so swapping the mock for this endpoint needs no UI redesign. */
public record ClaimView(String text, String verdict, String sourceUrl, int tier, String evidencePassage) {}
