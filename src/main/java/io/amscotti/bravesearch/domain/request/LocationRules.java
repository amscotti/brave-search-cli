package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The location rules every endpoint's location group shares: coordinates arrive paired
 * inside their documented ranges, text members stay nonblank, and code members are two
 * uppercase ASCII letters.
 *
 * <p>One home for the rules, because the header group itself is endpoint-shaped — web adds
 * a timezone member, the LLM context endpoint documents none — so each request keeps its
 * own location record while delegating every shared check here. Both coordinates must be
 * finite doubles: {@code NaN} compares false against every bound, so a plain range check
 * would wave it through onto the wire, and the finiteness guard keeps the promise that
 * an invalid request can never reach the wire. Header safety is the one rule the
 * transport boundary shares as well: free-text location members become header values, so
 * the endpoint adapters route every header value through the same check the domain's
 * place-name anchor uses, and both spellings of the boundary can never disagree.
 */
public final class LocationRules {

    private static final double MIN_LATITUDE = -90.0;
    private static final double MAX_LATITUDE = 90.0;
    private static final double MIN_LONGITUDE = -180.0;
    private static final double MAX_LONGITUDE = 180.0;

    private LocationRules() {}

    /**
     * Latitude and longitude arrive together or not at all, under the two option names
     * the calling endpoint documents.
     */
    static void requirePairedCoordinates(String firstOption, String secondOption, Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new UsageValidationError(firstOption + " and " + secondOption + " must be supplied together");
        }
    }

    /** Latitude and longitude arrive together or not at all. */
    static void requirePairedCoordinates(Double latitude, Double longitude) {
        requirePairedCoordinates("loc-lat", "loc-long", latitude, longitude);
    }

    /** Latitude stays inside its documented range, edges included. */
    static void requireLatitudeInRange(Double latitude) {
        requireLatitudeInRange("loc-lat", latitude);
    }

    /** Longitude stays inside its documented range, edges included. */
    static void requireLongitudeInRange(Double longitude) {
        requireLongitudeInRange("loc-long", longitude);
    }

    /** Latitude stays inside its documented range, edges included, under the calling endpoint's option name. */
    static void requireLatitudeInRange(String optionName, Double latitude) {
        if (latitude != null && (!Double.isFinite(latitude) || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE)) {
            throw new UsageValidationError(optionName + " must be between -90 and 90");
        }
    }

    /** Longitude stays inside its documented range, edges included, under the calling endpoint's option name. */
    static void requireLongitudeInRange(String optionName, Double longitude) {
        if (longitude != null && (!Double.isFinite(longitude) || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE)) {
            throw new UsageValidationError(optionName + " must be between -180 and 180");
        }
    }

    /** A present text member is never blank. */
    static void requireNonblankWhenPresent(String optionName, String value) {
        if (value != null && value.isBlank()) {
            throw new UsageValidationError(optionName + " must not be blank when supplied");
        }
    }

    /** A present code member is exactly two uppercase ASCII letters. */
    static void requireTwoLetterCode(String optionName, String code) {
        if (code == null || (code.length() == 2 && isAsciiUpper(code.charAt(0)) && isAsciiUpper(code.charAt(1)))) {
            return;
        }
        throw new UsageValidationError(optionName + " must be a two-letter uppercase code");
    }

    /**
     * A location-carrier string carries no control character — CR, LF, NUL, and every
     * other control code — and neither begins nor ends with whitespace, so nothing
     * adversarial can ride it onto a header map or a query line. Blankness stays each
     * caller's own nonblank rule; this check carries only the shared header boundary.
     */
    public static void requireHeaderSafe(String optionName, String value) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new UsageValidationError(optionName + " must not carry control characters");
            }
        }
        if (!value.equals(value.strip())) {
            throw new UsageValidationError(optionName + " must not begin or end with whitespace");
        }
    }

    private static boolean isAsciiUpper(char letter) {
        return letter >= 'A' && letter <= 'Z';
    }
}
