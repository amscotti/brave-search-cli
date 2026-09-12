package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;

/**
 * Renders one completed videos-search exchange — success or expected failure — on the
 * selected output channel; see {@link SearchPresenter} for the contract both sides share.
 * The videos specialization of the generic presenter surface, kept as its own type so the
 * command, the composition, and the contract index name the videos renderer concretely.
 */
public interface VideoSearchPresenter extends SearchPresenter<VideoSearchRequest, VideoSearchResult> {

    /** The canonical command name of videos search, the exact dispatch and wire value. */
    String COMMAND = "videos";
}
