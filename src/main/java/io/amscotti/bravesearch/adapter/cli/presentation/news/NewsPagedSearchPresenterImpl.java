package io.amscotti.bravesearch.adapter.cli.presentation.news;

import io.amscotti.bravesearch.adapter.cli.presentation.NewsPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.PagedSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsResults;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The multi-request news renderer: the paged channel contract of {@link
 * PagedSearchPresenterBase} carrying the news payload — the news heading, age lines, and
 * members of the human listing, the news bucket of the records, and the news projection
 * fields.
 */
public final class NewsPagedSearchPresenterImpl
        extends PagedSearchPresenterBase<NewsSearchRequest, NewsSearchResult, NewsResults.Entry>
        implements NewsPagedSearchPresenter {

    private final NewsProjectionExtractor extractor;

    public NewsPagedSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            NewsProjectionExtractor extractor,
            SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return COMMAND;
    }

    @Override
    protected List<NewsResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucket() {
        return NewsResults.BUCKET;
    }

    @Override
    protected int positionOf(NewsResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, NewsResults.Entry entry) {
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.url() != null) {
            fields.add(new Projection.Field("url", new Projection.Text(entry.url())));
        }
        if (entry.description() != null) {
            fields.add(new Projection.Field("description", new Projection.Text(entry.description())));
        }
        if (entry.age() != null) {
            fields.add(new Projection.Field("age", new Projection.Text(entry.age())));
        }
    }

    @Override
    protected String humanHeading(NewsSearchRequest request) {
        return "News results for: " + request.query();
    }

    @Override
    protected String titleOf(NewsResults.Entry entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(NewsResults.Entry entry) {
        List<HumanMember> members = new ArrayList<>(3);
        if (entry.url() != null) {
            members.add(new HumanMember(entry.url(), HumanMember.Kind.URL));
        }
        if (entry.description() != null) {
            members.add(new HumanMember(entry.description(), HumanMember.Kind.TEXT));
        }
        if (entry.age() != null) {
            members.add(new HumanMember(entry.age(), HumanMember.Kind.META));
        }
        return members;
    }
}
