package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PlaceDescriptions;
import io.amscotti.bravesearch.domain.result.Projection;
import java.util.List;
import java.util.Objects;

/**
 * The places describe renderer: the enrichment channel contract of {@link
 * PlaceEnrichmentPresenterBase} carrying the AI-description payload — the heading and
 * description lines of the human listing, the description bucket of the records, and
 * the description projection field.
 */
public final class PlaceDescribePresenterImpl
        extends PlaceEnrichmentPresenterBase<PlaceDescriptions> implements PlaceDescribePresenter {

    private final PlaceDescriptionsExtractor extractor;

    public PlaceDescribePresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            PlaceDescriptionsExtractor extractor,
            SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return PlaceDescribePresenter.COMMAND;
    }

    @Override
    protected String bucket() {
        return PlaceDescribePresenter.BUCKET;
    }

    @Override
    protected List<OrderReconstructor.Identified<PlaceDescriptions>> extractEntries(UpstreamPayload body) {
        return extractor.extract(body);
    }

    @Override
    protected void addEntryFields(List<Projection.Field> fields, PlaceDescriptions entry) {
        if (entry.description() != null) {
            fields.add(new Projection.Field("description", new Projection.Text(entry.description())));
        }
    }

    @Override
    protected String humanHeading(int idCount) {
        return "Place descriptions for " + idCount + (idCount == 1 ? " id" : " ids");
    }

    @Override
    protected String titleOf(PlaceDescriptions entry) {
        // the description payload carries no title: the id heads the human block
        return null;
    }

    @Override
    protected List<HumanMember> humanMembers(PlaceDescriptions entry) {
        if (entry.description() == null) {
            return List.of();
        }
        return List.of(new HumanMember(entry.description(), HumanMember.Kind.TEXT));
    }
}
