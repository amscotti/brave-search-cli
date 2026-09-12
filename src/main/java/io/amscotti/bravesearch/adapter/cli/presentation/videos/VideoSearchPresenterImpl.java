package io.amscotti.bravesearch.adapter.cli.presentation.videos;

import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.VideoResults;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The videos search renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the videos payload — the videos heading, the freshness age lines of
 * the human listing and the machine fields, the videos bucket of the records, and the
 * effective page of the request. Every framing, failure, warnings, and write-failure rule
 * lives once in the base; this class only shapes the videos payload.
 */
public final class VideoSearchPresenterImpl
        extends SimpleSearchPresenterBase<VideoSearchRequest, VideoSearchResult, VideoResults.Entry>
        implements VideoSearchPresenter {

    private final VideoProjectionExtractor extractor;

    public VideoSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            VideoProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return VideoSearchPresenter.COMMAND;
    }

    @Override
    protected List<VideoResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(VideoResults.Entry entry) {
        return VideoResults.BUCKET;
    }

    @Override
    protected int positionOf(VideoResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, VideoResults.Entry entry) {
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
    protected String humanHeading(VideoSearchRequest request) {
        return "Videos results for: " + request.query();
    }

    @Override
    protected String titleOf(VideoResults.Entry entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(VideoResults.Entry entry) {
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
    protected int effectivePageOf(VideoSearchRequest request) {
        return request.page() == null ? 1 : request.page();
    }
}
