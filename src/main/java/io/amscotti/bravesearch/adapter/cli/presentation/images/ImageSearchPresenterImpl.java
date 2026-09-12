package io.amscotti.bravesearch.adapter.cli.presentation.images;

import io.amscotti.bravesearch.adapter.cli.presentation.ImageSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageResults;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The images search renderer: the thin specialization of {@link SimpleSearchPresenterBase}
 * whose seams carry the images payload — the images heading, the url/image/thumbnail
 * lines of the human listing and the machine fields, the images bucket of the records,
 * and the constant first page of a non-paginated endpoint. Every framing, failure,
 * warnings, and write-failure rule lives once in the base; this class only shapes the
 * images payload.
 */
public final class ImageSearchPresenterImpl
        extends SimpleSearchPresenterBase<ImageSearchRequest, ImageSearchResult, ImageResults.Entry>
        implements ImageSearchPresenter {

    private final ImageProjectionExtractor extractor;

    public ImageSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            ImageProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return ImageSearchPresenter.COMMAND;
    }

    @Override
    protected List<ImageResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(ImageResults.Entry entry) {
        return ImageResults.BUCKET;
    }

    @Override
    protected int positionOf(ImageResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, ImageResults.Entry entry) {
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.url() != null) {
            fields.add(new Projection.Field("url", new Projection.Text(entry.url())));
        }
        if (entry.image() != null) {
            fields.add(new Projection.Field("image", new Projection.Text(entry.image())));
        }
        if (entry.thumbnail() != null) {
            fields.add(new Projection.Field("thumbnail", new Projection.Text(entry.thumbnail())));
        }
    }

    @Override
    protected String humanHeading(ImageSearchRequest request) {
        return "Images results for: " + request.query();
    }

    @Override
    protected String titleOf(ImageResults.Entry entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(ImageResults.Entry entry) {
        List<HumanMember> members = new ArrayList<>(3);
        if (entry.url() != null) {
            members.add(new HumanMember(entry.url(), HumanMember.Kind.URL));
        }
        if (entry.image() != null) {
            members.add(new HumanMember(entry.image(), HumanMember.Kind.URL));
        }
        if (entry.thumbnail() != null) {
            members.add(new HumanMember(entry.thumbnail(), HumanMember.Kind.URL));
        }
        return members;
    }

    @Override
    protected int effectivePageOf(ImageSearchRequest request) {
        // the images endpoint documents no pagination: every exchange is the first page
        return 1;
    }
}
