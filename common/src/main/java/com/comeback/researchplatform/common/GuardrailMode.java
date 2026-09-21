package com.comeback.researchplatform.common;

/** ENFORCE blocks on a breach. SHADOW logs it and proceeds anyway -- how a
 *  new limit gets introduced without it blocking something legitimate on
 *  day one: run shadowed for a while, read the log lines, then flip. One
 *  switch for every guardrail, not a mode per guardrail (CLAUDE.md: config
 *  surface you'd never actually use at this scale). */
public enum GuardrailMode { ENFORCE, SHADOW }
