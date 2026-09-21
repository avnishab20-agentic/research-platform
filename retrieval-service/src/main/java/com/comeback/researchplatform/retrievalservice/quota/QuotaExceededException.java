package com.comeback.researchplatform.retrievalservice.quota;

/** Thrown in ENFORCE mode when the daily search quota is exhausted, before
 *  the real request is placed. Mapped to 429 by RetrievalController. */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(int dailyLimit) {
        super("Daily search quota exhausted (" + dailyLimit + "). Try again tomorrow or raise "
                + "guardrails.retrieval.quota-daily-limit.");
    }
}
