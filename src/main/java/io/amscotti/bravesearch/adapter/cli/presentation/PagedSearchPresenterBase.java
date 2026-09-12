package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.error.UnreadableBodyException;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.output.WalkCounts;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.UrlDeduplicator;
import io.amscotti.bravesearch.domain.result.UrlIdentifiedEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The shared renderer skeleton of every multi-request search command: one completed
 * pagination run becomes the deduplicated aggregate on every channel, with the JSONL
 * streaming recorder, warnings routing, and the terminal render implemented once over
 * the endpoint-agnostic {@link PagedExchange} — the walk-level render itself lives in
 * {@link WalkPresenterBase} under the page vocabulary.
 *
 * <p>Success renders per mode — the command's human listing with its page-aware heading
 * and counts line, exactly one JSON envelope whose {@code data.upstream} is the ordered
 * per-request entry array and whose projection carries the pagination counts, or the JSONL
 * terminal summary after the streamed per-page records (the {@link #pageObserver} recorder
 * emitted them while the run walked). Deduplication is the exact-URL rule of {@link
 * UrlDeduplicator}, recomputed deterministically from the pages so the streamed records and
 * the buffered aggregates can never disagree. Raw mode never reaches this renderer: the
 * command rejects it before dispatch.
 *
 * <p>Write failures keep the presenter's own contract through the shared walk skeleton.
 * The command-specific shapes — the logical entries of one body, the projection and record
 * fields of an entry, the human heading and member lines, and the bucket name — arrive
 * through the abstract seams.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the endpoint's completed-exchange type every page carries
 * @param <E> the endpoint's logical result entry type the walk deduplicates
 */
public abstract class PagedSearchPresenterBase<TReq, TRes extends PagedExchange, E extends UrlIdentifiedEntry>
        extends WalkPresenterBase<TReq, TRes> implements PagedSearchPresenter<TReq, TRes> {

    protected PagedSearchPresenterBase(
            EnvelopeCodec envelopes, JsonlCodec jsonl, SearchPresenterBase.WarningsRouter warnings) {
        super(envelopes, jsonl, warnings, WalkCounts.PAGES);
    }

    /** The logical result entries of one completed body, in presentation order. */
    protected abstract List<E> extractEntries(UpstreamPayload body);

    /** The stable bucket name of the endpoint's logical result list. */
    protected abstract String bucket();

    /** An entry's original zero-based position in its upstream results array. */
    protected abstract int positionOf(E entry);

    /** Appends the entry's usable textual members to a projection or record field set. */
    protected abstract void addEntryFields(List<Projection.Field> fields, E entry);

    /** The human aggregate heading of the run: the command's label plus the request's query. */
    protected abstract String humanHeading(TReq request);

    /** The entry's usable title, or null when the result carried none. */
    protected abstract String titleOf(E entry);

    /**
     * The entry's member lines below its title line, in presentation order: each member
     * names its own treatment — url, body text, or per-type metadata — and the shared
     * layout renders the metadata last, dim, after every other member.
     */
    protected abstract List<HumanMember> humanMembers(E entry);

    @Override
    public final Consumer<PagedSearch.Page<TRes>> pageObserver(
            OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (output.mode() != OutputMode.JSONL) {
            return page -> {};
        }
        ModeAwareWarnings channel = warnings().open(output.mode(), diagnostics, results, output.quiet());
        UrlDeduplicator<E> deduplicator = new UrlDeduplicator<>();
        return page -> {
            notesOf(page.result()).forEach(channel::warning);
            List<UrlDeduplicator.Kept<E>> kept;
            try {
                kept = deduplicator.addPage(page.page(), extractEntries(page.result().body()));
            } catch (UnreadableBodyException unreadable) {
                // the terminal render re-reads every page and reports the malformed aggregate
                return;
            }
            for (UrlDeduplicator.Kept<E> entry : kept) {
                results.write(jsonl().encodeResult(command(), recordFields(entry)));
            }
        };
    }

    @Override
    public final int present(
            PaginationService.PagedRun<TRes> run,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        return render(run, request, output, results, diagnostics);
    }

    @Override
    protected final Projection aggregateProjection(TReq request, PagedSearch<TRes> search) {
        UrlDeduplicator<E> deduplicator = deduplicate(search);
        List<Projection.Value> results = new ArrayList<>(deduplicator.kept().size());
        for (UrlDeduplicator.Kept<E> kept : deduplicator.kept()) {
            List<Projection.Field> fields = new ArrayList<>(6);
            fields.add(new Projection.Field("page", decimal(kept.page())));
            fields.add(new Projection.Field("position", decimal(positionOf(kept.entry()))));
            addEntryFields(fields, kept.entry());
            results.add(new Projection.Nested(new Projection(fields)));
        }
        return new Projection(
                List.of(
                        new Projection.Field("result_count", decimal(deduplicator.kept().size())),
                        new Projection.Field("requested_pages", decimal(search.requestedPages())),
                        new Projection.Field("received_pages", decimal(search.receivedPages())),
                        new Projection.Field("duplicates_removed", decimal(deduplicator.duplicatesRemoved())),
                        new Projection.Field("results", new Projection.Sequence(results))));
    }

    @Override
    protected final List<Projection.Field> summaryFields(TReq request, PagedSearch<TRes> search) {
        UrlDeduplicator<E> deduplicator = deduplicate(search);
        TRes latest = search.pages().getLast().result();
        List<Projection.Field> fields = new ArrayList<>(6);
        fields.add(new Projection.Field("result_count", decimal(deduplicator.kept().size())));
        fields.add(new Projection.Field("requested_pages", decimal(search.requestedPages())));
        fields.add(new Projection.Field("received_pages", decimal(search.receivedPages())));
        fields.add(new Projection.Field("duplicates_removed", decimal(deduplicator.duplicatesRemoved())));
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
    protected final byte[] humanAggregate(TReq request, PagedSearch<TRes> search, OutputRequest output) {
        UrlDeduplicator<E> deduplicator = deduplicate(search);
        StringBuilder text = new StringBuilder();
        List<UrlDeduplicator.Kept<E>> kept = deduplicator.kept();
        if (!kept.isEmpty()) {
            text.append(HeadingStyle.bold(humanHeading(request), output));
            if (search.receivedPages() > 1) {
                text.append(" (pages 1-").append(search.receivedPages()).append(')');
            }
            text.append("\n\n");
            int number = 1;
            for (UrlDeduplicator.Kept<E> entry : kept) {
                if (number > 1) {
                    text.append('\n');
                }
                String title = titleOf(entry.entry());
                HumanListing.appendTitle(
                        text, number, kept.size(), title == null ? HumanListing.UNTITLED : title, output);
                HumanListing.appendMembers(text, kept.size(), humanMembers(entry.entry()), output);
                number++;
            }
        }
        text.append(kept.size()).append(kept.size() == 1 ? " result" : " results")
                .append(" across ")
                .append(search.receivedPages())
                .append(" of ")
                .append(search.requestedPages())
                .append(" requested pages, ")
                .append(deduplicator.duplicatesRemoved())
                .append(deduplicator.duplicatesRemoved() == 1 ? " duplicate removed." : " duplicates removed.")
                .append('\n');
        TRes latest = search.pages().getLast().result();
        if (latest.rateLimits() != null) {
            HumanListing.appendQuotaFooter(text, latest.rateLimits().windows(), output);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The single deduplication of the walk's pages; one shared rule for every mode. */
    private UrlDeduplicator<E> deduplicate(PagedSearch<TRes> search) {
        UrlDeduplicator<E> deduplicator = new UrlDeduplicator<>();
        for (PagedSearch.Page<TRes> page : search.pages()) {
            deduplicator.addPage(page.page(), extractEntries(page.result().body()));
        }
        return deduplicator;
    }

    /** The JSONL record of one retained page result: its page and position provenance first. */
    private List<Projection.Field> recordFields(UrlDeduplicator.Kept<E> kept) {
        List<Projection.Field> fields = new ArrayList<>(7);
        fields.add(new Projection.Field("position", decimal(positionOf(kept.entry()))));
        fields.add(new Projection.Field("page", decimal(kept.page())));
        fields.add(new Projection.Field("bucket", new Projection.Text(bucket())));
        addEntryFields(fields, kept.entry());
        return fields;
    }
}
