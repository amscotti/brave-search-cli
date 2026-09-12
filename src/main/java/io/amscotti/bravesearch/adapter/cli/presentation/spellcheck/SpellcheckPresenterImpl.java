package io.amscotti.bravesearch.adapter.cli.presentation.spellcheck;

import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SpellcheckPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.SpellcheckResults;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import java.util.List;
import java.util.Objects;

/**
 * The spellcheck renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the corrections payload — the spellcheck heading, the numbered
 * corrected-query lines, the spellcheck bucket of the records, and the constant first
 * page of a non-paginated endpoint. Every framing, failure, warnings, and write-failure
 * rule lives once in the base; this class only shapes the corrections payload, and it
 * names the entries corrections: an empty exchange renders {@code No corrections.} — a
 * clean query is the answer, not an absence of output — and the count line counts
 * corrections.
 */
public final class SpellcheckPresenterImpl
        extends SimpleSearchPresenterBase<SpellcheckRequest, SpellcheckSearchResult, SpellcheckResults.Entry>
        implements SpellcheckPresenter {

    private final SpellcheckProjectionExtractor extractor;

    public SpellcheckPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            SpellcheckProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return SpellcheckPresenter.COMMAND;
    }

    @Override
    protected List<SpellcheckResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(SpellcheckResults.Entry entry) {
        return SpellcheckResults.BUCKET;
    }

    @Override
    protected int positionOf(SpellcheckResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, SpellcheckResults.Entry entry) {
        fields.add(new Projection.Field("query", new Projection.Text(entry.query())));
    }

    @Override
    protected String humanHeading(SpellcheckRequest request) {
        return "Spellcheck results for: " + request.query();
    }

    @Override
    protected String titleOf(SpellcheckResults.Entry entry) {
        return entry.query();
    }

    @Override
    protected List<HumanMember> humanMembers(SpellcheckResults.Entry entry) {
        // the corrected query is the whole correction: the title line carries it all
        return List.of();
    }

    @Override
    protected int effectivePageOf(SpellcheckRequest request) {
        // the spellcheck endpoint documents no pagination: every exchange is the first page
        return 1;
    }

    @Override
    protected String emptyHumanMessage() {
        return "No corrections.";
    }

    @Override
    protected String humanCountLine(int count) {
        return count + (count == 1 ? " correction." : " corrections.");
    }
}
