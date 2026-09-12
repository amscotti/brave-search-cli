package io.amscotti.bravesearch.adapter.cli.presentation.videos;

import io.amscotti.bravesearch.adapter.cli.presentation.PagedSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
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
 * The multi-request videos renderer: the paged channel contract of {@link
 * PagedSearchPresenterBase} carrying the videos payload — the videos heading, age lines,
 * and members of the human listing, the videos bucket of the records, and the videos
 * projection fields.
 */
public final class VideoPagedSearchPresenterImpl
        extends PagedSearchPresenterBase<VideoSearchRequest, VideoSearchResult, VideoResults.Entry>
        implements VideoPagedSearchPresenter {

    private final VideoProjectionExtractor extractor;

    public VideoPagedSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            VideoProjectionExtractor extractor,
            SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return COMMAND;
    }

    @Override
    protected List<VideoResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucket() {
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
}
