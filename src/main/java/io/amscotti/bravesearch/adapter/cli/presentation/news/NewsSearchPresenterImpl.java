package io.amscotti.bravesearch.adapter.cli.presentation.news;

import io.amscotti.bravesearch.adapter.cli.presentation.NewsSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
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
 * The news search renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the news payload — the news heading, the freshness age lines of the
 * human listing and the machine fields, the news bucket of the records, and the effective
 * page of the request. Every framing, failure, warnings, and write-failure rule lives
 * once in the base; this class only shapes the news payload.
 */
public final class NewsSearchPresenterImpl
        extends SimpleSearchPresenterBase<NewsSearchRequest, NewsSearchResult, NewsResults.Entry>
        implements NewsSearchPresenter {

    private final NewsProjectionExtractor extractor;

    public NewsSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            NewsProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return NewsSearchPresenter.COMMAND;
    }

    @Override
    protected List<NewsResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(NewsResults.Entry entry) {
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

    @Override
    protected int effectivePageOf(NewsSearchRequest request) {
        return request.page() == null ? 1 : request.page();
    }
}
