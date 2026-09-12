package io.amscotti.bravesearch.adapter.cli.presentation.web;

import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.WebResults;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The web search renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the web payload — the web heading and the member lines of the human
 * listing, the web bucket of the records, and the effective page of the request. Every
 * framing, failure, warnings, and write-failure rule lives once in the base; this class
 * only shapes the web payload.
 */
public final class WebSearchPresenterImpl
        extends SimpleSearchPresenterBase<WebSearchRequest, WebSearchResult, WebResults.Entry>
        implements WebSearchPresenter {

    private final WebProjectionExtractor extractor;

    public WebSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            WebProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return WebSearchPresenter.COMMAND;
    }

    @Override
    protected List<WebResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(WebResults.Entry entry) {
        return WebResults.BUCKET;
    }

    @Override
    protected int positionOf(WebResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, WebResults.Entry entry) {
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.url() != null) {
            fields.add(new Projection.Field("url", new Projection.Text(entry.url())));
        }
        if (entry.description() != null) {
            fields.add(new Projection.Field("description", new Projection.Text(entry.description())));
        }
    }

    @Override
    protected String humanHeading(WebSearchRequest request) {
        return "Web results for: " + request.query();
    }

    @Override
    protected String titleOf(WebResults.Entry entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(WebResults.Entry entry) {
        List<HumanMember> members = new ArrayList<>(2);
        if (entry.url() != null) {
            members.add(new HumanMember(entry.url(), HumanMember.Kind.URL));
        }
        if (entry.description() != null) {
            members.add(new HumanMember(entry.description(), HumanMember.Kind.TEXT));
        }
        return members;
    }

    @Override
    protected int effectivePageOf(WebSearchRequest request) {
        return request.page() == null ? 1 : request.page();
    }
}
