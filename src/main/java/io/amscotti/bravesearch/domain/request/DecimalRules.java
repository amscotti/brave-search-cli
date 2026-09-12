package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.math.BigDecimal;

/**
 * The decimal rules every decimal option value of a request shares: the value is a
 * plain decimal — never an exponent spelling — with a bounded fraction scale and a
 * bounded digit count.
 *
 * <p>One home for the rules, because a decimal's wire form is its plain-string
 * rendering: an exponent spelling such as {@code 1e2147483647} parses into a tiny
 * option value whose plain form is billions of characters long, and no range check can
 * see that — only the scale and the digit count can. A plain-decimal spelling inside
 * these bounds can never render to more than a bounded digit string, so one flag can
 * never turn into an out-of-memory wire form.
 */
final class DecimalRules {

    /** The largest fraction scale of a decimal option value. */
    static final int MAX_FRACTION_DIGITS = 100;

    /** The largest digit count of a decimal option value. */
    static final int MAX_SIGNIFICANT_DIGITS = 1000;

    private DecimalRules() {}

    /**
     * A decimal option value carries no exponent spelling and stays inside the shared
     * fraction-scale and digit bounds, under the calling option's name.
     *
     * @throws UsageValidationError naming the broken decimal rule
     */
    static void requirePlainDecimal(String optionName, BigDecimal value) {
        if (value.scale() < 0) {
            throw new UsageValidationError(optionName + " must be a plain decimal, not an exponent spelling");
        }
        if (value.scale() > MAX_FRACTION_DIGITS) {
            throw new UsageValidationError(
                    optionName + " must carry at most " + MAX_FRACTION_DIGITS + " fraction digits");
        }
        if (value.precision() > MAX_SIGNIFICANT_DIGITS) {
            throw new UsageValidationError(
                    optionName + " must carry at most " + MAX_SIGNIFICANT_DIGITS + " significant digits");
        }
    }
}
