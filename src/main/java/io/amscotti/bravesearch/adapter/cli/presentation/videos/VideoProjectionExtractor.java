package io.amscotti.bravesearch.adapter.cli.presentation.videos;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.VideoResults;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the videos logical results from the lossless upstream body: the
 * shared array walk of {@link ResultsArrayExtractor} over the upstream top-level {@code
 * results} path, with every usable array element becoming one entry carrying its original
 * zero-based position and whatever usable textual {@code title}, {@code url}, {@code
 * description}, and {@code age} members it had. The structured {@code video} object
 * (including its {@code duration}), the {@code meta_url} object, and the {@code thumbnail}
 * object of an element are deliberately ignored, because none of them carries a textual
 * member the listing projection renders.
 */
public final class VideoProjectionExtractor extends ResultsArrayExtractor<VideoResults.Entry> {

    public VideoProjectionExtractor(JsonMappers mappers) {
        super(mappers, "results");
    }

    /** The videos logical results of {@code body}. */
    public VideoResults extract(UpstreamPayload body) {
        return new VideoResults(extractEntries(body));
    }

    @Override
    protected VideoResults.Entry entryOf(int position, JsonNode element) {
        return new VideoResults.Entry(
                position,
                text(element.get("title")),
                text(element.get("url")),
                text(element.get("description")),
                text(element.get("age")));
    }
}
