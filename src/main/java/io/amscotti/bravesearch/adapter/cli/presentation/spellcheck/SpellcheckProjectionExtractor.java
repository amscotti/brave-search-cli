package io.amscotti.bravesearch.adapter.cli.presentation.spellcheck;

import io.amscotti.bravesearch.adapter.cli.presentation.ResultsArrayExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.SpellcheckResults;
import tools.jackson.databind.JsonNode;

/**
 * Tolerant enumeration of the spellcheck logical results from the lossless upstream
 * body: the shared array walk of {@link ResultsArrayExtractor} over the upstream
 * top-level {@code results} path, with every usable array element becoming one entry
 * carrying its original zero-based position and the spellcheck-corrected query — the
 * single documented member of a spellcheck result. An element whose correction member is
 * not textual is skipped whole, because a correction without its corrected query is not
 * renderable.
 */
public final class SpellcheckProjectionExtractor extends ResultsArrayExtractor<SpellcheckResults.Entry> {

    public SpellcheckProjectionExtractor(JsonMappers mappers) {
        super(mappers, "results");
    }

    /** The spellcheck logical results of {@code body}. */
    public SpellcheckResults extract(UpstreamPayload body) {
        return new SpellcheckResults(entriesOfUsableCorrections(body));
    }

    private java.util.List<SpellcheckResults.Entry> entriesOfUsableCorrections(UpstreamPayload body) {
        return extractEntries(body).stream()
                .filter(entry -> entry.query() != null)
                .toList();
    }

    @Override
    protected SpellcheckResults.Entry entryOf(int position, JsonNode element) {
        return new SpellcheckResults.Entry(position, text(element.get("query")));
    }
}
