package io.amscotti.bravesearch.adapter.cli.presentation.suggest;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.SuggestResults;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the suggest logical results from the lossless upstream body:
 * the shared array walk of {@link ResultsArrayExtractor} over the upstream top-level
 * {@code results} path, with every usable array element becoming one entry carrying its
 * original zero-based position, its suggested query completion, and whatever usable
 * textual rich members — the kind, title, description, and image url — it carried. The
 * deprecated upstream {@code is_entity} flag is deliberately ignored, because {@code
 * type} is its documented replacement; an element whose completion member is not textual
 * is skipped whole, because a suggestion without its completion text is not renderable.
 */
public final class SuggestProjectionExtractor extends ResultsArrayExtractor<SuggestResults.Entry> {

    public SuggestProjectionExtractor(JsonMappers mappers) {
        super(mappers, "results");
    }

    /** The suggest logical results of {@code body}. */
    public SuggestResults extract(UpstreamPayload body) {
        return new SuggestResults(entriesOfUsableCompletions(body));
    }

    private java.util.List<SuggestResults.Entry> entriesOfUsableCompletions(UpstreamPayload body) {
        return extractEntries(body).stream()
                .filter(entry -> entry.query() != null)
                .toList();
    }

    @Override
    protected SuggestResults.Entry entryOf(int position, JsonNode element) {
        return new SuggestResults.Entry(
                position,
                text(element.get("query")),
                text(element.get("type")),
                text(element.get("title")),
                text(element.get("description")),
                text(element.get("img")));
    }
}
