package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDetails;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The places detail renderer: the enrichment channel contract of {@link
 * PlaceEnrichmentPresenterBase} carrying the POI detail payload — the heading and
 * member lines of the human listing, the detail bucket of the records, and the detail
 * projection fields.
 */
public final class PlaceDetailsPresenterImpl
        extends PlaceEnrichmentPresenterBase<PlaceDetails> implements PlaceDetailsPresenter {

    private final PlaceDetailsExtractor extractor;

    public PlaceDetailsPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            PlaceDetailsExtractor extractor,
            SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return PlaceDetailsPresenter.COMMAND;
    }

    @Override
    protected String bucket() {
        return PlaceDetailsPresenter.BUCKET;
    }

    @Override
    protected List<OrderReconstructor.Identified<PlaceDetails>> extractEntries(UpstreamPayload body) {
        return extractor.extract(body);
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, PlaceDetails entry) {
        if (entry.title() != null) {
            fields.add(new Projection.Field("title", new Projection.Text(entry.title())));
        }
        if (entry.url() != null) {
            fields.add(new Projection.Field("url", new Projection.Text(entry.url())));
        }
        if (entry.description() != null) {
            fields.add(new Projection.Field("description", new Projection.Text(entry.description())));
        }
        if (entry.displayAddress() != null) {
            fields.add(new Projection.Field("display_address", new Projection.Text(entry.displayAddress())));
        }
        if (entry.phone() != null) {
            fields.add(new Projection.Field("phone", new Projection.Text(entry.phone())));
        }
        if (entry.email() != null) {
            fields.add(new Projection.Field("email", new Projection.Text(entry.email())));
        }
        if (entry.priceRange() != null) {
            fields.add(new Projection.Field("price_range", new Projection.Text(entry.priceRange())));
        }
        if (entry.timezone() != null) {
            fields.add(new Projection.Field("timezone", new Projection.Text(entry.timezone())));
        }
        if (entry.thumbnail() != null) {
            fields.add(new Projection.Field("thumbnail", new Projection.Text(entry.thumbnail())));
        }
    }

    @Override
    protected String humanHeading(int idCount) {
        return "Place details for " + idCount + (idCount == 1 ? " id" : " ids");
    }

    @Override
    protected String titleOf(PlaceDetails entry) {
        return entry.title();
    }

    @Override
    protected List<HumanMember> humanMembers(PlaceDetails entry) {
        List<HumanMember> members = new ArrayList<>(6);
        if (entry.url() != null) {
            members.add(new HumanMember(entry.url(), HumanMember.Kind.URL));
        }
        if (entry.description() != null) {
            members.add(new HumanMember(entry.description(), HumanMember.Kind.TEXT));
        }
        if (entry.displayAddress() != null) {
            members.add(new HumanMember(entry.displayAddress(), HumanMember.Kind.TEXT));
        }
        if (entry.phone() != null) {
            members.add(new HumanMember(entry.phone(), HumanMember.Kind.TEXT));
        }
        if (entry.email() != null) {
            members.add(new HumanMember(entry.email(), HumanMember.Kind.TEXT));
        }
        if (entry.thumbnail() != null) {
            members.add(new HumanMember(entry.thumbnail(), HumanMember.Kind.URL));
        }
        if (entry.priceRange() != null) {
            members.add(new HumanMember(entry.priceRange(), HumanMember.Kind.META));
        }
        if (entry.timezone() != null) {
            members.add(new HumanMember(entry.timezone(), HumanMember.Kind.META));
        }
        return members;
    }
}
