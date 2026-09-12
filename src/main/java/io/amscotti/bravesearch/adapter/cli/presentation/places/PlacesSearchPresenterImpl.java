package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.PlacesSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SimpleSearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.result.PlaceResults;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The places search renderer: the thin specialization of {@link
 * SimpleSearchPresenterBase} whose seams carry the multi-bucket places payload. One
 * response mixes buckets, so every entry carries its own bucket word through the
 * per-entry bucket seam and its position inside its own bucket's array, while the
 * human listing numbers the places of every bucket under one heading and one count
 * line. The heading names the invocation's own mode — {@code Places for:} a query,
 * {@code Places near:} an anchor or a geoloc hint, {@code Places everywhere} for the
 * query-less anchor-less broad global search — and a requested radius appends the
 * ranking-bias note, because the radius biases ranking and is not a hard boundary.
 *
 * <p>The JSON projection carries each entry's bucket beside its position, so the mixed
 * enumeration stays visible in the envelope, and every absent member is omitted from
 * every rendered form, never rendered as an empty placeholder.
 */
public final class PlacesSearchPresenterImpl
        extends SimpleSearchPresenterBase<PlaceSearchRequest, PlaceSearchResult, PlaceResults.Entry>
        implements PlacesSearchPresenter {

    private static final String RADIUS_NOTE = "Radius biases ranking; it is not a hard boundary.";

    private final PlacesProjectionExtractor extractor;

    public PlacesSearchPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            PlacesProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return PlacesSearchPresenter.COMMAND;
    }

    @Override
    protected List<PlaceResults.Entry> extractEntries(UpstreamPayload body) {
        return extractor.extract(body).entries();
    }

    @Override
    protected String bucketOf(PlaceResults.Entry entry) {
        return entry.bucket();
    }

    @Override
    protected int positionOf(PlaceResults.Entry entry) {
        return entry.position();
    }

    @Override
    protected void addPositionalFields(List<Projection.Field> fields, PlaceResults.Entry entry) {
        fields.add(new Projection.Field("position", decimal(positionOf(entry))));
        fields.add(new Projection.Field("bucket", new Projection.Text(bucketOf(entry))));
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, PlaceResults.Entry entry) {
        if (entry.id() != null) {
            fields.add(new Projection.Field("id", new Projection.Text(entry.id())));
        }
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.address() != null) {
            fields.add(new Projection.Field("address", new Projection.Text(entry.address())));
        }
        if (entry.ratingValue() != null) {
            fields.add(new Projection.Field("rating_value", new Projection.Text(entry.ratingValue())));
        }
        if (entry.ratingCount() != null) {
            fields.add(new Projection.Field("rating_count", new Projection.Text(entry.ratingCount())));
        }
        if (entry.distance() != null) {
            fields.add(new Projection.Field("distance", new Projection.Text(entry.distance())));
        }
        if (entry.phone() != null) {
            fields.add(new Projection.Field("phone", new Projection.Text(entry.phone())));
        }
        if (entry.website() != null) {
            fields.add(new Projection.Field("website", new Projection.Text(entry.website())));
        }
    }

    @Override
    protected String humanHeading(PlaceSearchRequest request) {
        if (request.query() != null) {
            return "Places for: " + request.query();
        }
        if (request.anchor() instanceof PlaceAnchor.Coordinates coordinates) {
            return "Places near: " + coordinates.latitude() + ", " + coordinates.longitude();
        }
        if (request.anchor() instanceof PlaceAnchor.LocationName location) {
            return "Places near: " + location.name();
        }
        if (request.geoloc() != null) {
            return "Places near: " + request.geoloc().wireForm();
        }
        return "Places everywhere";
    }

    @Override
    protected String titleOf(PlaceResults.Entry entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(PlaceResults.Entry entry) {
        List<HumanMember> members = new ArrayList<>(5);
        if (entry.address() != null) {
            members.add(new HumanMember(entry.address(), HumanMember.Kind.TEXT));
        }
        if (entry.phone() != null) {
            members.add(new HumanMember(entry.phone(), HumanMember.Kind.TEXT));
        }
        if (entry.website() != null) {
            members.add(new HumanMember(entry.website(), HumanMember.Kind.URL));
        }
        if (entry.ratingValue() != null) {
            String rating = "Rating: " + entry.ratingValue();
            if (entry.ratingCount() != null) {
                rating += " (" + entry.ratingCount() + " reviews)";
            }
            members.add(new HumanMember(rating, HumanMember.Kind.META));
        }
        if (entry.distance() != null) {
            members.add(new HumanMember("Distance: " + entry.distance(), HumanMember.Kind.META));
        }
        return members;
    }

    @Override
    protected int effectivePageOf(PlaceSearchRequest request) {
        // the place search endpoint documents no pagination: every exchange is the first page
        return 1;
    }

    @Override
    protected String emptyHumanMessage() {
        return "No places.";
    }

    @Override
    protected String humanCountLine(int count) {
        return count + (count == 1 ? " place." : " places.");
    }

    @Override
    protected byte[] humanDocument(PlaceSearchResult result, PlaceSearchRequest request, OutputRequest output) {
        byte[] listing = super.humanDocument(result, request, output);
        if (request.radius() == null) {
            return listing;
        }
        return (new String(listing, StandardCharsets.UTF_8) + RADIUS_NOTE + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
