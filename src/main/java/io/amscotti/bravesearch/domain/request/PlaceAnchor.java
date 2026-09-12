package io.amscotti.bravesearch.domain.request;

import io.amscotti.bravesearch.domain.error.UsageValidationError;

/**
 * The anchor of a place search: the coordinates pair, the place-name string, or none —
 * a query-less request with an anchor is Explore mode, and a query-less anchor-less
 * request is a valid broad global search.
 *
 * <p>The two anchor spellings refuse each other, because the endpoint documents one
 * anchor per request; the pairing, conflict, range, and header-safety rules all live on
 * the anchor itself so every caller — the CLI option boundary and the library facade —
 * validates identically.
 */
public sealed interface PlaceAnchor permits PlaceAnchor.Coordinates, PlaceAnchor.LocationName {

    /**
     * Resolves the anchor the caller described: both coordinates together, the place
     * name, or none at all.
     *
     * @param latitude the documented latitude option value, or null
     * @param longitude the documented longitude option value, or null
     * @param location the documented place-name option value, or null
     * @return the anchor, or null when the caller supplied none of its spellings
     * @throws UsageValidationError when one coordinate arrives alone, or the pair
     *     arrives together with the place name
     */
    static PlaceAnchor of(Double latitude, Double longitude, String location) {
        if (latitude == null && longitude == null) {
            return location == null ? null : new LocationName(location);
        }
        LocationRules.requirePairedCoordinates("latitude", "longitude", latitude, longitude);
        if (location != null) {
            throw new UsageValidationError("latitude and longitude cannot be combined with location");
        }
        return new Coordinates(latitude, longitude);
    }

    /**
     * The coordinates anchor: latitude and longitude paired inside their documented
     * ranges, edges included.
     */
    record Coordinates(Double latitude, Double longitude) implements PlaceAnchor {

        /** Both members arrive paired and range-checked together. */
        public Coordinates {
            LocationRules.requirePairedCoordinates("latitude", "longitude", latitude, longitude);
            LocationRules.requireLatitudeInRange("latitude", latitude);
            LocationRules.requireLongitudeInRange("longitude", longitude);
        }
    }

    /**
     * The place-name anchor: the endpoint documents it without commas — US spellings
     * {@code city state country}, others {@code city country} — and the CLI carries it
     * unchanged, after refusing the control characters and edge whitespace that must
     * never reach a location string.
     */
    record LocationName(String name) implements PlaceAnchor {

        /** A present name is never blank, never header-unsafe: no control characters, no edge whitespace. */
        public LocationName {
            LocationRules.requireNonblankWhenPresent("location", name);
            LocationRules.requireHeaderSafe("location", name);
        }
    }
}
