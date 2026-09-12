package io.amscotti.bravesearch.adapter.cli.presentation;


/**
 * Renders one completed place-enrichment chunk walk of the AI-description endpoint on
 * the selected output channel; the channel contract is the enrichment one both place
 * enrichment commands share.
 */
public interface PlaceDescribePresenter extends PlaceEnrichmentPresenter {

    /** The canonical command name of places describe, the exact dispatch and wire value. */
    String COMMAND = "places.describe";

    /** The stable bucket name of the description records. */
    String BUCKET = "descriptions";
}
