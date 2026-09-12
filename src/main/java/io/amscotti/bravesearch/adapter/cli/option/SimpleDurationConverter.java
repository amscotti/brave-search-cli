package io.amscotti.bravesearch.adapter.cli.option;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Duration option values in the one documented grammar: an unsigned integer with a {@code
 * ms}, {@code s}, or {@code m} unit ({@code 250ms}, {@code 1s}, {@code 2m}). Every other
 * spelling — ISO-8601 forms, {@code ns} and {@code h} units, negatives, zero, and overflow —
 * is a usage rejection before any request is dispatched, because a budget must be a plain
 * positive duration the operator can read back at a glance.
 */
public final class SimpleDurationConverter implements ITypeConverter<Duration> {

    private static final Pattern SHORTHAND = Pattern.compile("(\\d+)(ms|s|m)");

    @Override
    public Duration convert(String value) {
        String trimmed = value.trim();
        Matcher shorthand = SHORTHAND.matcher(trimmed);
        if (!shorthand.matches()) {
            throw new TypeConversionException(
                    "not a duration (expected an unsigned integer with a ms, s, or m unit, like 250ms, 1s, or 2m): "
                            + value);
        }
        Duration duration;
        try {
            duration = Duration.of(Long.parseLong(shorthand.group(1)), unitOf(shorthand.group(2)));
        } catch (NumberFormatException | ArithmeticException overflow) {
            throw new TypeConversionException("duration value overflows: " + value);
        }
        if (duration.isZero()) {
            throw new TypeConversionException("not a positive duration (must be greater than zero): " + value);
        }
        return duration;
    }

    private static ChronoUnit unitOf(String unit) {
        return switch (unit) {
            case "ms" -> ChronoUnit.MILLIS;
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            default -> throw new IllegalStateException("unreachable for the matched pattern: " + unit);
        };
    }
}
