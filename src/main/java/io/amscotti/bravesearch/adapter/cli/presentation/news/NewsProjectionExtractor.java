package io.amscotti.bravesearch.adapter.cli.presentation.news;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.NewsResults;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the news logical results from the lossless upstream body: the
 * shared array walk of {@link ResultsArrayExtractor} over the upstream {@code
 * news.results} path, with every usable array element becoming one entry carrying its
 * original zero-based position and whatever usable textual {@code title}, {@code url},
 * {@code description}, and {@code age} members it had.
 *
 * <p>The news exchange also answers with its results in a shared top-level {@code
 * results} array of tagged elements: while the documented bucket yields nothing, that
 * array is walked instead, and an element whose own type tag names another result kind
 * is vetoed, so a mixed body never misattributes its entries.
 */
public final class NewsProjectionExtractor extends ResultsArrayExtractor<NewsResults.Entry> {

    /** The element tag of a news result inside a shared top-level results array. */
    private static final String NEWS_RESULT_TYPE = "news_result";

    public NewsProjectionExtractor(JsonMappers mappers) {
        super(mappers, "news", "results");
    }

    /** The news logical results of {@code body}. */
    public NewsResults extract(UpstreamPayload body) {
        return new NewsResults(extractEntries(body));
    }

    @Override
    protected List<JsonNode> candidateArrays(JsonNode root) {
        return List.of(root.path("news").path("results"), root.path("results"));
    }

    @Override
    protected NewsResults.Entry entryOf(int position, JsonNode element) {
        if (isAnotherResultType(element)) {
            return null;
        }
        return new NewsResults.Entry(
                position,
                text(element.get("title")),
                text(element.get("url")),
                text(element.get("description")),
                text(element.get("age")));
    }

    /** Whether the element's own type tag rules it out as a news result. */
    private static boolean isAnotherResultType(JsonNode element) {
        JsonNode type = element.path("type");
        return type.isString() && !NEWS_RESULT_TYPE.equals(type.stringValue());
    }
}
