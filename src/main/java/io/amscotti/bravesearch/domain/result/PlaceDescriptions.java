package io.amscotti.bravesearch.domain.result;

/**
 * The projected payload of one returned AI-description entry: the description text the
 * entry carried, {@code null} when the entry carried no usable textual form — an absent
 * member is omitted from every rendered form, never rendered as an empty placeholder.
 */
public record PlaceDescriptions(String description) {}
