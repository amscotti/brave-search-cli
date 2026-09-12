package io.amscotti.bravesearch.adapter.cli.presentation.places;

import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.HeadingStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.HumanListing;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceEnrichmentPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.WalkPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.output.WalkCounts;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.OrderReconstructor;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The shared renderer skeleton of the two place-enrichment commands: one completed
 * chunk walk becomes the order-reconstructed per-id aggregate on every channel, with
 * the JSONL streaming recorder, warnings routing, and the terminal render implemented
 * once over the endpoint-agnostic {@link PlaceEnrichmentResult} — the walk-level
 * render itself lives in {@link WalkPresenterBase} under the request vocabulary.
 *
 * <p>The aggregation is the order reconstruction, not deduplication: every input
 * position of every completed chunk — duplicates included — renders one record or one
 * human block at its original position, a missing or expired id renders its
 * placeholder, and the counts state the input, returned, and missing totals. Raw mode
 * never reaches this renderer: the commands reject it before dispatch.
 *
 * <p>The command-specific shapes — the identified entries of one chunk body, the
 * heading and member lines of the human listing, the payload fields of a projection or
 * record, and the bucket name — arrive through the abstract seams.
 *
 * @param <E> the endpoint's projected payload type the reconstruction places
 */
public abstract class PlaceEnrichmentPresenterBase<E>
        extends WalkPresenterBase<PlaceEnrichmentRequest, PlaceEnrichmentResult>
        implements PlaceEnrichmentPresenter {

    private static final String NOT_RETURNED_LINE = "(not returned)";

    protected PlaceEnrichmentPresenterBase(
            EnvelopeCodec envelopes, JsonlCodec jsonl, SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings, WalkCounts.REQUESTS);
    }

    /** The stable bucket name of the endpoint's records. */
    protected abstract String bucket();

    /** The identified entries of one completed chunk body, in response order. */
    protected abstract List<OrderReconstructor.Identified<E>> extractEntries(UpstreamPayload body);

    /** Appends the payload's usable members to a projection or record field set. */
    protected abstract void addEntryFields(List<Projection.Field> fields, E entry);

    /** The human heading of the whole listing, naming the invocation's id count. */
    protected abstract String humanHeading(int idCount);

    /** The payload's usable title, or null when the entry carried none. */
    protected abstract String titleOf(E entry);

    /**
     * The entry's member lines below its title line, in presentation order: each member
     * names its own treatment — url, body text, or per-type metadata — and the shared
     * layout renders the metadata last, dim, after every other member.
     */
    protected abstract List<HumanMember> humanMembers(E entry);

    @Override
    public final Consumer<PagedSearch.Page<PlaceEnrichmentResult>> chunkObserver(
            PlaceEnrichmentRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (output.mode() != OutputMode.JSONL) {
            return chunk -> {};
        }
        ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
        return chunk -> {
            notesOf(chunk.result()).forEach(channel::warning);
            OrderReconstructor.Reconstruction<E> reconstruction;
            try {
                reconstruction = reconstructChunk(request, chunk);
            } catch (UnreadableBodyException unreadable) {
                // the terminal render re-reads every chunk and reports the malformed aggregate
                return;
            }
            int offset = chunkOffset(request, chunk.page());
            for (OrderReconstructor.Slot<E> slot : reconstruction.slots()) {
                results.write(jsonl().encodeResult(command(), recordFields(offset, slot)));
            }
        };
    }

    @Override
    public final int present(
            PaginationService.PagedRun<PlaceEnrichmentResult> run,
            PlaceEnrichmentRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        return render(run, request, output, results, diagnostics);
    }

    @Override
    protected final Projection aggregateProjection(
            PlaceEnrichmentRequest request, PagedSearch<PlaceEnrichmentResult> search) {
        OrderReconstructor.Reconstruction<E> reconstruction = reconstructWalk(request, search);
        List<Projection.Value> results = new ArrayList<>(reconstruction.slots().size());
        for (OrderReconstructor.Slot<E> slot : reconstruction.slots()) {
            results.add(new Projection.Nested(new Projection(slotFields(slot))));
        }
        return new Projection(
                List.of(
                        new Projection.Field("id_count", decimal(request.ids().size())),
                        new Projection.Field("returned_count", decimal(reconstruction.returnedCount())),
                        new Projection.Field("missing_count", decimal(reconstruction.missingCount())),
                        new Projection.Field("requested_requests", decimal(search.requestedPages())),
                        new Projection.Field("received_requests", decimal(search.receivedPages())),
                        new Projection.Field("results", new Projection.Sequence(results))));
    }

    @Override
    protected final List<Projection.Field> summaryFields(
            PlaceEnrichmentRequest request, PagedSearch<PlaceEnrichmentResult> search) {
        OrderReconstructor.Reconstruction<E> reconstruction = reconstructWalk(request, search);
        PlaceEnrichmentResult latest = search.pages().getLast().result();
        List<Projection.Field> fields =
                new ArrayList<>(search.requestedPages() + reconstruction.slots().size());
        fields.add(new Projection.Field("id_count", decimal(reconstruction.slots().size())));
        fields.add(new Projection.Field("returned_count", decimal(reconstruction.returnedCount())));
        fields.add(new Projection.Field("missing_count", decimal(reconstruction.missingCount())));
        fields.add(new Projection.Field("requested_requests", decimal(search.requestedPages())));
        fields.add(new Projection.Field("received_requests", decimal(search.receivedPages())));
        fields.add(new Projection.Field("http_status", decimal(latest.httpStatus())));
        if (latest.requestId() != null) {
            fields.add(new Projection.Field("request_id", new Projection.Text(latest.requestId())));
        }
        if (latest.apiVersion() != null) {
            fields.add(new Projection.Field("api_version", new Projection.Text(latest.apiVersion())));
        }
        return fields;
    }

    @Override
    protected final byte[] humanAggregate(
            PlaceEnrichmentRequest request, PagedSearch<PlaceEnrichmentResult> search, OutputRequest output) {
        OrderReconstructor.Reconstruction<E> reconstruction = reconstructWalk(request, search);
        StringBuilder text = new StringBuilder();
        text.append(HeadingStyle.bold(humanHeading(request.ids().size()), output)).append("\n\n");
        int number = 1;
        for (OrderReconstructor.Slot<E> slot : reconstruction.slots()) {
            if (number > 1) {
                text.append('\n');
            }
            if (slot.present()) {
                String title = titleOf(slot.value());
                // a missing title heads the block with the invocation's own key instead
                HumanListing.appendTitle(text, number, reconstruction.slots().size(), title == null ? slot.id() : title, output);
                if (title != null) {
                    // the id is the invocation's own key: it stays visible beside any title
                    HumanListing.appendMetaLines(text, reconstruction.slots().size(), slot.id(), output);
                }
                HumanListing.appendMembers(text, reconstruction.slots().size(), humanMembers(slot.value()), output);
            } else {
                HumanListing.appendTitle(text, number, reconstruction.slots().size(), slot.id(), output);
                HumanListing.appendMetaLines(text, reconstruction.slots().size(), NOT_RETURNED_LINE, output);
            }
            number++;
        }
        int ids = request.ids().size();
        text.append(ids).append(ids == 1 ? " id, " : " ids, ")
                .append(reconstruction.returnedCount())
                .append(" returned, ")
                .append(reconstruction.missingCount())
                .append(" not returned.\n");
        PlaceEnrichmentResult latest = search.pages().getLast().result();
        if (latest.rateLimits() != null) {
            HumanListing.appendQuotaFooter(text, latest.rateLimits().windows(), output);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The single reconstruction of the walk's completed chunks, in input order. */
    private OrderReconstructor.Reconstruction<E> reconstructWalk(
            PlaceEnrichmentRequest request, PagedSearch<PlaceEnrichmentResult> search) {
        List<OrderReconstructor.Slot<E>> slots = new ArrayList<>(request.ids().size());
        int returnedCount = 0;
        for (PagedSearch.Page<PlaceEnrichmentResult> chunk : search.pages()) {
            OrderReconstructor.Reconstruction<E> chunkReconstruction = reconstructChunk(request, chunk);
            int offset = chunkOffset(request, chunk.page());
            for (OrderReconstructor.Slot<E> slot : chunkReconstruction.slots()) {
                slots.add(new OrderReconstructor.Slot<>(offset + slot.position(), slot.id(), slot.value()));
            }
            returnedCount += chunkReconstruction.returnedCount();
        }
        return new OrderReconstructor.Reconstruction<>(slots, returnedCount, slots.size() - returnedCount);
    }

    private OrderReconstructor.Reconstruction<E> reconstructChunk(
            PlaceEnrichmentRequest request, PagedSearch.Page<PlaceEnrichmentResult> chunk) {
        return OrderReconstructor.reconstruct(
                request.idsOfChunk(chunk.page()), extractEntries(chunk.result().body()));
    }

    /** The zero-based input position where one chunk's slice begins. */
    private static int chunkOffset(PlaceEnrichmentRequest request, int chunk) {
        return (chunk - 1) * PlaceEnrichmentRequest.MAX_PER_REQUEST;
    }

    /** The per-position members of one record or projection entry, present or placeholder. */
    private List<Projection.Field> slotFields(OrderReconstructor.Slot<E> slot) {
        List<Projection.Field> fields = new ArrayList<>(6);
        fields.add(new Projection.Field("position", decimal(slot.position())));
        fields.add(new Projection.Field("id", new Projection.Text(slot.id())));
        fields.add(new Projection.Field("present", new Projection.Flag(slot.present())));
        if (slot.present()) {
            addEntryFields(fields, slot.value());
        }
        return fields;
    }

    /** The JSONL record of one reconstructed position: its provenance first, then the payload. */
    private List<Projection.Field> recordFields(int offset, OrderReconstructor.Slot<E> slot) {
        List<Projection.Field> fields = new ArrayList<>(7);
        fields.add(new Projection.Field("position", decimal(offset + slot.position())));
        fields.add(new Projection.Field("bucket", new Projection.Text(bucket())));
        fields.add(new Projection.Field("id", new Projection.Text(slot.id())));
        fields.add(new Projection.Field("present", new Projection.Flag(slot.present())));
        if (slot.present()) {
            addEntryFields(fields, slot.value());
        }
        return fields;
    }
}
