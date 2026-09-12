package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;

/**
 * Renders one completed images-search exchange — success or expected failure — on the
 * selected output channel; see {@link SearchPresenter} for the contract both sides share.
 * The images specialization of the generic presenter surface, kept as its own type so the
 * command, the composition, and the contract index name the images renderer concretely.
 */
public interface ImageSearchPresenter extends SearchPresenter<ImageSearchRequest, ImageSearchResult> {

    /** The canonical command name of images search, the exact dispatch and wire value. */
    String COMMAND = "images";
}
