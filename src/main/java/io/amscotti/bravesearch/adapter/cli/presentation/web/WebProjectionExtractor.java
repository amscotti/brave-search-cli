package io.amscotti.bravesearch.adapter.cli.presentation.web;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.WebResults;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the web logical results from the lossless upstream body: the
 * shared array walk of {@link ResultsArrayExtractor} over the upstream {@code
 * web.results} path, with every usable array element becoming one entry carrying its
 * original zero-based position and whatever usable textual {@code title}, {@code url},
 * and {@code description} members it had.
 */
public final class WebProjectionExtractor extends ResultsArrayExtractor<WebResults.Entry> {

    public WebProjectionExtractor(JsonMappers mappers) {
        super(mappers, "web", "results");
    }

    /** The web logical results of {@code body}. */
    public WebResults extract(UpstreamPayload body) {
        return new WebResults(extractEntries(body));
    }

    @Override
    protected WebResults.Entry entryOf(int position, JsonNode element) {
        return new WebResults.Entry(
                position, text(element.get("title")), text(element.get("url")), text(element.get("description")));
    }
}
