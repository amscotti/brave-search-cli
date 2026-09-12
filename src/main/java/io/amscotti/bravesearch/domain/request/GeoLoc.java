package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The device-geolocation hint of a place search: two decimal components spelled
 * {@code latitudexlongitude} on the wire, exactly as given — the value keeps its
 * original decimal scale, so {@code 40.690x-74.250} serializes back with its trailing
 * zeros.
 *
 * <p>Both components are range-checked exactly like the anchor coordinates, because the
 * same geographic bounds apply; the parsing rejects every spelling that is not two
 * plain decimal numbers around the one {@code x} separator — the non-finite spellings
 * the decimal grammar forbids cannot parse at all, and an exponent spelling re-renders
 * as a different plain form — and every component keeps its scale and digit count
 * inside the shared decimal bounds, so the wire form can never explode a tiny
 * spelling into a giant digit string.
 */
public record GeoLoc(BigDecimal latitude, BigDecimal longitude) {

    private static final char SEPARATOR = 'x';

    /** Both components arrive inside their documented ranges, edges included. */
    public GeoLoc {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
        DecimalRules.requirePlainDecimal("geoloc latitude", latitude);
        DecimalRules.requirePlainDecimal("geoloc longitude", longitude);
        requireInRange("geoloc latitude", latitude, "-90", "90");
        requireInRange("geoloc longitude", longitude, "-180", "180");
    }

    /**
     * Parses the {@code latitudexlongitude} spelling of the option boundary.
     *
     * @param spelling the raw option value
     * @return the validated geolocation hint
     * @throws UsageValidationError naming the geoloc rule when the spelling is not two
     *     decimal coordinates or a component leaves its range
     */
    public static GeoLoc parse(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        int separator = spelling.indexOf(SEPARATOR);
        boolean malformed = separator < 1
                || separator == spelling.length() - 1
                || spelling.indexOf(SEPARATOR, separator + 1) >= 0
                || spelling.indexOf('e') >= 0
                || spelling.indexOf('E') >= 0;
        BigDecimal latitude = null;
        BigDecimal longitude = null;
        if (!malformed) {
            try {
                latitude = new BigDecimal(spelling.substring(0, separator));
                longitude = new BigDecimal(spelling.substring(separator + 1));
            } catch (NumberFormatException notDecimal) {
                malformed = true;
            }
        }
        if (malformed) {
            throw new UsageValidationError("geoloc must be two decimal coordinates spelled latitudexlongitude");
        }
        return new GeoLoc(latitude, longitude);
    }

    /** The wire form: {@code latitudexlongitude} with each component's given scale. */
    public String wireForm() {
        return latitude.toPlainString() + SEPARATOR + longitude.toPlainString();
    }

    private static void requireInRange(String member, BigDecimal value, String minimum, String maximum) {
        if (value.compareTo(new BigDecimal(minimum)) < 0 || value.compareTo(new BigDecimal(maximum)) > 0) {
            throw new UsageValidationError(member + " must be between " + minimum + " and " + maximum);
        }
    }
}
