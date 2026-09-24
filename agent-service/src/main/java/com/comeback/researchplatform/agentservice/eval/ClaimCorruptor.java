package com.comeback.researchplatform.agentservice.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN's five fabrication-injection corruption types, applied
 * programmatically -- no LLM call, deterministic, so the eval it feeds
 * stays free and repeatable (CLAUDE.md: "evals run on fixture mode and
 * cost $0"). Each method returns {@link Optional#empty()} rather than the
 * unchanged text when a corruption genuinely doesn't apply to a given
 * claim (no number to swap, no polarity word to invert, etc.) -- silently
 * returning the original text would make the eval think a claim was
 * corrupted when it wasn't, inflating the apparent catch rate for nothing.
 */
public final class ClaimCorruptor {

    private static final Pattern NUMBER = Pattern.compile("([$€£]?\\s?[0-9][0-9,]*(?:\\.[0-9]+)?\\s?%?)");
    // A run of capitalised words, e.g. "Reserve Bank". The "+" after each
    // quantifier (*+, ++) means "never give characters back", which stops the
    // regex engine trying exponentially many ways to split a long run of words.
    private static final Pattern PROPER_NOUN =
            Pattern.compile("\\b[A-Z][a-zA-Z]*+(?:\\s++[A-Z][a-zA-Z]*+)*+\\b");

    // Ordered so the longer/more specific phrase in a pair is tried first
    // (e.g. "grew by" before "grew") -- Map iteration order matters here.
    private static final Map<String, String> POLARITY_PAIRS = new LinkedHashMap<>();
    static {
        POLARITY_PAIRS.put("increased", "decreased");
        POLARITY_PAIRS.put("decreased", "increased");
        POLARITY_PAIRS.put("grew", "declined");
        POLARITY_PAIRS.put("declined", "grew");
        POLARITY_PAIRS.put("rose", "fell");
        POLARITY_PAIRS.put("fell", "rose");
        POLARITY_PAIRS.put("gained", "lost");
        POLARITY_PAIRS.put("lost", "gained");
        POLARITY_PAIRS.put("higher", "lower");
        POLARITY_PAIRS.put("lower", "higher");
        POLARITY_PAIRS.put("above", "below");
        POLARITY_PAIRS.put("below", "above");
    }

    private static final Map<String, String> HEDGE_TO_ABSOLUTE = new LinkedHashMap<>();
    static {
        HEDGE_TO_ABSOLUTE.put("some analysts", "analysts unanimously");
        HEDGE_TO_ABSOLUTE.put("many experts", "all experts");
        HEDGE_TO_ABSOLUTE.put("several reports", "every report");
        HEDGE_TO_ABSOLUTE.put("a few", "all");
        HEDGE_TO_ABSOLUTE.put("some", "all");
        HEDGE_TO_ABSOLUTE.put("many", "every");
        HEDGE_TO_ABSOLUTE.put("several", "all");
        HEDGE_TO_ABSOLUTE.put("appears to", "definitely");
        HEDGE_TO_ABSOLUTE.put("likely", "certainly");
        HEDGE_TO_ABSOLUTE.put("may", "will");
    }

    private static final String[] ALTERNATE_ENTITIES =
            {"Meridian Corp", "Vantage Group", "Northbridge Holdings", "Concordia Ltd"};

    private ClaimCorruptor() {}

    public static Optional<String> corrupt(String claimText, CorruptionType type) {
        return switch (type) {
            case SWAP_NUMBER -> swapNumber(claimText);
            case INVERT -> invert(claimText);
            case SWAP_ENTITY -> swapEntity(claimText);
            case OVERREACH -> overreach(claimText);
            case FABRICATE -> Optional.empty(); // not a mutation -- see fabricate()
        };
    }

    /** FABRICATE doesn't corrupt an existing claim, it inserts a wholly new
     *  one -- PLAN: "insert plausible claim citing a real source". Deliberately
     *  generic and topic-agnostic, and deliberately unverifiable: it names no
     *  fact from {@code sourceUrl}'s actual content, so a correctly-working
     *  Critic should never find evidence for it. */
    public static String fabricate(String sourceUrl) {
        return "Additional analysis from " + sourceUrl + " indicates this trend is expected "
                + "to continue through the next reporting period.";
    }

    private static Optional<String> swapNumber(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String matched = m.group(1);
        String digitsOnly = matched.replaceAll("[^0-9.]", "");
        if (digitsOnly.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal original = new BigDecimal(digitsOnly);
        // Scaled up ~1.8x rather than a fixed offset -- stays plausible-looking
        // for both small and large numbers, and is never a no-op (a fixed +1
        // would round away to nothing on a value like "4.2%").
        BigDecimal corrupted = original.multiply(new BigDecimal("1.8")).setScale(1, RoundingMode.HALF_UP);
        String replacement = matched.replace(digitsOnly, stripTrailingZero(corrupted));
        return Optional.of(text.substring(0, m.start(1)) + replacement + text.substring(m.end(1)));
    }

    private static String stripTrailingZero(BigDecimal value) {
        String s = value.toPlainString();
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static Optional<String> invert(String text) {
        for (Map.Entry<String, String> pair : POLARITY_PAIRS.entrySet()) {
            String replaced = replaceFirstWholeWord(text, pair.getKey(), pair.getValue());
            if (replaced != null) {
                return Optional.of(replaced);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> overreach(String text) {
        for (Map.Entry<String, String> pair : HEDGE_TO_ABSOLUTE.entrySet()) {
            String replaced = replaceFirstWholeWord(text, pair.getKey(), pair.getValue());
            if (replaced != null) {
                return Optional.of(replaced);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> swapEntity(String text) {
        Matcher m = PROPER_NOUN.matcher(text);
        String best = null;
        int bestStart = -1, bestEnd = -1;
        while (m.find()) {
            // Longest match wins -- in a short claim sentence the longest
            // capitalized run is almost always the actual named entity
            // ("Reserve Bank of India"), not an incidental capitalized word.
            if (best == null || m.group().length() > best.length()) {
                best = m.group();
                bestStart = m.start();
                bestEnd = m.end();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String replacement = ALTERNATE_ENTITIES[Math.abs(best.hashCode()) % ALTERNATE_ENTITIES.length];
        return Optional.of(text.substring(0, bestStart) + replacement + text.substring(bestEnd));
    }

    /** Whole-word, case-insensitive replace of the first occurrence only.
     *  Returns null (not the unchanged text) when there's no match, so
     *  callers can tell "not applicable" apart from "applied, no visible
     *  change". */
    private static String replaceFirstWholeWord(String text, String phrase, String replacement) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        return text.substring(0, m.start()) + replacement + text.substring(m.end());
    }
}
