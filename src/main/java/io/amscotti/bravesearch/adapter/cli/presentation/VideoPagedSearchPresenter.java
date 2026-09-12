package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;

/**
 * Renders one completed multi-request videos pagination run — the aggregate of its
 * completed pages or the failure of its first failed page — on the selected output
 * channel; the channel contract is the generic {@link PagedSearchPresenter} one every
 * paginated command shares.
 */
public interface VideoPagedSearchPresenter extends PagedSearchPresenter<VideoSearchRequest, VideoSearchResult> {

    /** The canonical command name of videos search, the exact dispatch and wire value. */
    String COMMAND = "videos";
}
