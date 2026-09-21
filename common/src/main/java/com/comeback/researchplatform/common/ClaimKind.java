package com.comeback.researchplatform.common;

/** FACT/FIGURE/QUOTE are verified against evidence; INFERENCE is skipped but must
 *  render visually distinct — it's the writer's own reasoning, not a sourced claim. */
public enum ClaimKind {
    FACT, FIGURE, QUOTE, INFERENCE
}
