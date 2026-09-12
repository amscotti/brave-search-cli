package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;

/**
 * Renders one completed place search exchange — success or expected failure — on the
 * selected output channel; see {@link SearchPresenter} for the contract both sides
 * share. The places search specialization of the generic presenter surface, kept as
 * its own type so the command, the composition, and the contract index name the
 * renderer concretely.
 */
public interface PlacesSearchPresenter extends SearchPresenter<PlaceSearchRequest, PlaceSearchResult> {

    /**
     * The canonical command name of places search — the group token dot the subcommand
     * token, the dotted subcommand path every machine document and diagnostic carries.
     */
    String COMMAND = "places.search";
}
