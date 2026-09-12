package io.amscotti.bravesearch.adapter.cli.presentation.suggest;

import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SuggestPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.SuggestResults;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The suggest renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the suggestions payload — the suggestions heading, the numbered
 * completion lines with the enriched rich members indented, the suggest bucket of the
 * records, and the constant first page of a non-paginated endpoint. Every framing,
 * failure, warnings, and write-failure rule lives once in the base; this class only
 * shapes the suggestions payload, and it names the entries suggestions: an empty
 * exchange renders {@code No suggestions.} and the count line counts suggestions.
 *
 * <p>The suggestion kind rides the machine documents as {@code suggestion_type} — the
 * frame already owns the record's {@code type} member, so the upstream member's own name
 * cannot travel unchanged. The deprecated upstream {@code is_entity} flag is not
 * carried, because the kind is its documented replacement.
 */
public final class SuggestPresenterImpl
        extends SimpleSearchPresenterBase<SuggestRequest, SuggestSearchResult, SuggestResults.Entry>
        implements SuggestPresenter {

    private final SuggestProjectionExtractor extractor;

    public SuggestPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            SuggestProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return SuggestPresenter.COMMAND;
    }

    @Override
    protected List<SuggestResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(SuggestResults.Entry entry) {
        return SuggestResults.BUCKET;
    }

    @Override
    protected int positionOf(SuggestResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, SuggestResults.Entry entry) {
        fields.add(new Projection.Field("query", new Projection.Text(entry.query())));
        if (entry.type() != null) {
            fields.add(new Projection.Field("suggestion_type", new Projection.Text(entry.type())));
        }
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.description() != null) {
            fields.add(new Projection.Field("description", new Projection.Text(entry.description())));
        }
        if (entry.img() != null) {
            fields.add(new Projection.Field("img", new Projection.Text(entry.img())));
        }
    }

    @Override
    protected String humanHeading(SuggestRequest request) {
        return "Suggestions for: " + request.query();
    }

    @Override
    protected String titleOf(SuggestResults.Entry entry) {
        return entry.query();
    }

    @Override
    protected List<HumanMember> humanMembers(SuggestResults.Entry entry) {
        List<HumanMember> members = new ArrayList<>(3);
        if (entry.title() != null) {
            members.add(new HumanMember(entry.title(), HumanMember.Kind.TEXT));
        }
        if (entry.description() != null) {
            members.add(new HumanMember(entry.description(), HumanMember.Kind.TEXT));
        }
        if (entry.img() != null) {
            members.add(new HumanMember(entry.img(), HumanMember.Kind.URL));
        }
        return members;
    }

    @Override
    protected int effectivePageOf(SuggestRequest request) {
        // the suggest endpoint documents no pagination: every exchange is the first page
        return 1;
    }

    @Override
    protected String emptyHumanMessage() {
        return "No suggestions.";
    }

    @Override
    protected String humanCountLine(int count) {
        return count + (count == 1 ? " suggestion." : " suggestions.");
    }
}
