package io.amscotti.bravesearch.adapter.cli.presentation;


/**
 * Renders one completed place-enrichment chunk walk of the POI detail endpoint on the
 * selected output channel; the channel contract is the enrichment one both place
 * enrichment commands share.
 */
public interface PlaceDetailsPresenter extends PlaceEnrichmentPresenter {

    /** The canonical command name of places details, the exact dispatch and wire value. */
    String COMMAND = "places.details";

    /** The stable bucket name of the detail records. */
    String BUCKET = "details";
}
