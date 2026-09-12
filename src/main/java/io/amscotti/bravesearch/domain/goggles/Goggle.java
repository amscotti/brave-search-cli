package io.amscotti.bravesearch.domain.goggles;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.net.URI;

/**
 * One reranking instruction set for a search: either a reference to a goggle published at
 * an absolute {@code http(s)} URL, or an inline definition carrying the goggle dialect
 * text itself.
 *
 * <p>The inline shape is validated against the limits the checked-in upstream document
 * records: at most {@link #MAX_INSTRUCTIONS} instructions — approximated as the nonblank
 * lines that are not comment lines, because the document fixes the count without fixing a
 * grammar — each instruction at most {@link #MAX_INSTRUCTION_CODE_POINTS} Unicode code
 * points, and no control character other than the structurally admitted newline and tab
 * anywhere in the text. The document records that wildcard and caret usage is limited
 * without publishing the numeric bound, so this CLI pins the conservative enforceable
 * choice of at most {@link #MAX_WILDCARDS_PER_INSTRUCTION} wildcards and at most
 * {@link #MAX_CARETS_PER_INSTRUCTION} carets per instruction.
 *
 * <p>Every rejection names the violated rule only; goggle text is user content and never
 * rides a usage message.
 */
public sealed interface Goggle permits Goggle.UrlReference, Goggle.Inline {

    /** The largest number of instructions of one inline definition. */
    int MAX_INSTRUCTIONS = 100_000;

    /** The largest instruction, in Unicode code points. */
    int MAX_INSTRUCTION_CODE_POINTS = 500;

    /** The largest number of wildcard characters of one instruction (a pinned bound). */
    int MAX_WILDCARDS_PER_INSTRUCTION = 3;

    /** The largest number of caret characters of one instruction (a pinned bound). */
    int MAX_CARETS_PER_INSTRUCTION = 3;

    /** A reference to a goggle published at an absolute http or https URL. */
    record UrlReference(String url) implements Goggle {

        /** @throws UsageValidationError when the URL is not an absolute http(s) spelling */
        public UrlReference {
            requireAbsoluteHttpUrl(url);
        }
    }

    /** An inline goggle: the definition text itself, validated at construction. */
    record Inline(String definition) implements Goggle {

        /** @throws UsageValidationError when the definition breaks a documented limit */
        public Inline {
            requireValidDefinition(definition);
        }
    }

    /**
     * Whether the given value carries the URL-reference shape — an absolute {@code http}
     * or {@code https} URI with a host — and therefore distinguishes itself from inline
     * definition text.
     */
    static boolean isUrlReferenceShape(String value) {
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (!parsed.isAbsolute() || parsed.getHost() == null || parsed.getHost().isEmpty()) {
            return false;
        }
        String scheme = parsed.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** Validates an inline definition against every documented limit. */
    static void requireValidDefinition(String definition) {
        if (definition == null) {
            throw new UsageValidationError("goggle definition is required");
        }
        if (definition.isBlank()) {
            throw new UsageValidationError("goggle definition must not be blank");
        }
        rejectForbiddenControls(definition);
        int instructions = 0;
        int position = 0;
        for (String line : definition.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("!")) {
                continue;
            }
            instructions++;
            position++;
            if (instructions > MAX_INSTRUCTIONS) {
                throw new UsageValidationError(
                        "goggle definition must not exceed " + MAX_INSTRUCTIONS + " instructions");
            }
            requireWithinInstructionLimits(line, position);
        }
    }

    private static void rejectForbiddenControls(String definition) {
        for (int index = 0; index < definition.length(); index++) {
            char character = definition.charAt(index);
            if (character == '\n' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw new UsageValidationError(
                        "goggle definition must not carry control characters other than newline and tab");
            }
        }
    }

    private static void requireWithinInstructionLimits(String instruction, int position) {
        int codePoints = instruction.codePointCount(0, instruction.length());
        if (codePoints > MAX_INSTRUCTION_CODE_POINTS) {
            throw new UsageValidationError(
                    "goggle instruction " + position + " exceeds the " + MAX_INSTRUCTION_CODE_POINTS
                            + " code point limit");
        }
        if (countOf('*', instruction) > MAX_WILDCARDS_PER_INSTRUCTION) {
            throw new UsageValidationError(
                    "goggle instruction " + position + " exceeds the wildcard limit of "
                            + MAX_WILDCARDS_PER_INSTRUCTION + " per instruction");
        }
        if (countOf('^', instruction) > MAX_CARETS_PER_INSTRUCTION) {
            throw new UsageValidationError(
                    "goggle instruction " + position + " exceeds the caret limit of "
                            + MAX_CARETS_PER_INSTRUCTION + " per instruction");
        }
    }

    private static int countOf(char character, String text) {
        int count = 0;
        for (int index = text.indexOf(character); index >= 0; index = text.indexOf(character, index + 1)) {
            count++;
        }
        return count;
    }

    private static void requireAbsoluteHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new UsageValidationError("goggle URL reference is required");
        }
        if (!isUrlReferenceShape(url)) {
            throw new UsageValidationError("goggle URL reference must be an absolute http or https URL");
        }
    }
}
