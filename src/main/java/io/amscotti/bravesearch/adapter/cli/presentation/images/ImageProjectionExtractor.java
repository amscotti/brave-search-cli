package io.amscotti.bravesearch.adapter.cli.presentation.images;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.ImageResults;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the images logical results from the lossless upstream body: the
 * shared array walk of {@link ResultsArrayExtractor} over the upstream top-level {@code
 * results} path, with every usable array element becoming one entry carrying its original
 * zero-based position and whatever usable textual {@code title}, {@code url}, {@code
 * image}, and {@code thumbnail} members it had — the result page, the full-size image,
 * and the thumbnail. The structured {@code dimension} object of an element and every
 * unknown member are deliberately ignored, because none of them carries a textual member
 * the listing projection renders.
 */
public final class ImageProjectionExtractor extends ResultsArrayExtractor<ImageResults.Entry> {

    public ImageProjectionExtractor(JsonMappers mappers) {
        super(mappers, "results");
    }

    /** The images logical results of {@code body}. */
    public ImageResults extract(UpstreamPayload body) {
        return new ImageResults(extractEntries(body));
    }

    @Override
    protected ImageResults.Entry entryOf(int position, JsonNode element) {
        return new ImageResults.Entry(
                position,
                text(element.get("title")),
                text(element.get("url")),
                text(element.get("image")),
                text(element.get("thumbnail")));
    }
}
