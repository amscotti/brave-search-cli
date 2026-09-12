package io.amscotti.bravesearch.domain.result;

/**
 * The projected payload of one returned POI detail entry: only the members the listing
 * can show, each {@code null} when the upstream entry carried no usable textual form of
 * it — an absent member is omitted from every rendered form, never rendered as an empty
 * placeholder.
 *
 * <p>The detail endpoint returns rich nested structures — photos, web result mentions,
 * profiles — and the lossless machine documents carry them all inside {@code
 * data.upstream}; this projection deliberately carries only the bounded textual
 * inventory the listing renders, exactly like every other command's projection. The
 * members are the entry's own {@code title}, {@code url}, and {@code description}, the
 * display address of its postal address block, the telephone and email of its contact
 * block, its {@code price_range}, its {@code timezone}, and the source url of its
 * thumbnail.
 */
public record PlaceDetails(
        String title,
        String url,
        String description,
        String displayAddress,
        String phone,
        String email,
        String priceRange,
        String timezone,
        String thumbnail) {}
