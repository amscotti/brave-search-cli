package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared renderer skeleton of every single-request search command over a listing
 * endpoint: one completed exchange whose logical result list becomes the concise human
 * listing, the stable projection of the JSON envelope, and the JSONL result/summary
 * records, implemented once over the endpoint-agnostic {@link PagedExchange} view.
 *
 * <p>Success renders per mode — the human listing (bold heading, one block per logical
 * result in the shared entry layout of {@link HumanListing} separated by blank lines,
 * count line, advisory quota footer, one {@code No results.} line when empty), exactly
 * one JSON envelope whose projection carries {@code result_count}, the effective page and
 * its upstream offset, and the positioned entries, one JSONL {@code result} record per
 * entry in presentation order followed by exactly one {@code summary} record, or the raw
 * decoded body bytes with nothing added. Every framing, failure, warnings,
 * write-failure, and exchange-metadata rule lives once in {@link SearchPresenterBase};
 * this skeleton adds only the listing shapes over the endpoint-agnostic
 * completed-exchange view.
 *
 * <p>The command-specific shapes — the logical entries of one body, the entry's
 * projection and record fields, the human heading and member lines, and the effective
 * page — arrive through the abstract seams, mirroring the seam set of {@link
 * PagedSearchPresenterBase} so the single-request and multi-request renderers of one
 * endpoint stay shaped alike.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the command's exchange result type
 * @param <E> the endpoint's logical result entry type
 */
public abstract class SimpleSearchPresenterBase<TReq, TRes extends PagedExchange, E>
        extends SearchPresenterBase<TReq, TRes> {

    protected SimpleSearchPresenterBase(
            EnvelopeCodec envelopes, JsonlCodec jsonl, RawCodec raw, WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
    }

    /** The logical result entries of one completed body, in presentation order. */
    protected abstract List<E> extractEntries(UpstreamPayload body);

    /**
     * The stable bucket name of one entry's logical result list — the wire word that
     * names the response array the entry was enumerated from. Single-list endpoints
     * return their one constant bucket; an endpoint whose one logical listing spans
     * several upstream response buckets returns each entry's own bucket word, so the
     * mixed enumeration stays labeled in every machine document.
     */
    protected abstract String bucketOf(E entry);

    /** An entry's original zero-based position in its upstream results array. */
    protected abstract int positionOf(E entry);

    /**
     * Appends the positional members of one entry to an envelope projection field set:
     * the entry's original position, plus any positional labeling the endpoint's
     * envelope carries beside it. The default projects the position alone; an endpoint
     * whose mixed enumeration must stay labeled in the envelope — each entry's own
     * response bucket — overrides to append that label.
     */
    protected void addPositionalFields(List<Projection.Field> fields, E entry) {
        fields.add(new Projection.Field("position", decimal(positionOf(entry))));
    }

    /** Appends the entry's usable textual members to a projection or record field set. */
    protected abstract void addEntryFields(List<Projection.Field> fields, E entry);

    /** The human heading of the listing: the command's label plus the request's query. */
    protected abstract String humanHeading(TReq request);

    /** The entry's usable title, or null when the result carried none. */
    protected abstract String titleOf(E entry);

    /**
     * The entry's member lines below its title line, in presentation order: each member
     * names its own treatment — url, body text, or per-type metadata — and the shared
     * layout renders the metadata last, dim, after every other member.
     */
    protected abstract List<HumanMember> humanMembers(E entry);

    /** The effective user-facing page of the request: the pinned one, or the first page when none was. */
    protected abstract int effectivePageOf(TReq request);

    /**
     * The single human line an empty listing renders. The default names results; an
     * endpoint whose logical entries are named differently — suggestions, corrections —
     * overrides with its own noun so an empty listing still reads as an answer.
     */
    protected String emptyHumanMessage() {
        return "No results.";
    }

    /** The trailing human count line of a nonempty listing, without its line break. */
    protected String humanCountLine(int count) {
        return count + (count == 1 ? " result." : " results.");
    }

    @Override
    protected byte[] humanDocument(TRes result, TReq request, OutputRequest output) {
        List<E> entries = extractEntries(result.body());
        if (entries.isEmpty()) {
            return (emptyHumanMessage() + "\n").getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder();
        text.append(HeadingStyle.bold(humanHeading(request), output));
        if (effectivePageOf(request) > 1) {
            text.append(" (page ").append(effectivePageOf(request)).append(')');
        }
        text.append("\n\n");
        int number = 1;
        for (E entry : entries) {
            if (number > 1) {
                text.append('\n');
            }
            String title = titleOf(entry);
            HumanListing.appendTitle(
                    text, number, entries.size(), title == null ? HumanListing.UNTITLED : title, output);
            HumanListing.appendMembers(text, entries.size(), humanMembers(entry), output);
            number++;
        }
        text.append(humanCountLine(entries.size())).append('\n');
        HumanListing.appendQuotaFooter(text, windowsOf(result), output);
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The outcome snapshot's parsed windows, or null when the exchange observed none. */
    private static <TRes extends PagedExchange> List<RateLimitWindow> windowsOf(TRes result) {
        return result.rateLimits() == null ? null : result.rateLimits().windows();
    }

    @Override
    protected Projection jsonProjection(TReq request, TRes result) {
        List<E> entries = extractEntries(result.body());
        List<Projection.Value> projected = new ArrayList<>(entries.size());
        for (E entry : entries) {
            List<Projection.Field> fields = new ArrayList<>(5);
            addPositionalFields(fields, entry);
            addEntryFields(fields, entry);
            projected.add(new Projection.Nested(new Projection(fields)));
        }
        return new Projection(
                List.of(
                        new Projection.Field("result_count", decimal(entries.size())),
                        new Projection.Field("page", decimal(effectivePageOf(request))),
                        new Projection.Field("upstream_offset", decimal(effectivePageOf(request) - 1)),
                        new Projection.Field("results", new Projection.Sequence(projected))));
    }

    @Override
    protected List<List<Projection.Field>> jsonlResultRecords(TRes result) {
        List<E> entries = extractEntries(result.body());
        List<List<Projection.Field>> records = new ArrayList<>(entries.size());
        for (E entry : entries) {
            List<Projection.Field> fields = new ArrayList<>(6);
            fields.add(new Projection.Field("position", decimal(positionOf(entry))));
            fields.add(new Projection.Field("bucket", new Projection.Text(bucketOf(entry))));
            addEntryFields(fields, entry);
            records.add(fields);
        }
        return records;
    }

    @Override
    protected List<Projection.Field> jsonlSummaryFields(TRes result, TReq request, int resultCount) {
        List<Projection.Field> fields = new ArrayList<>(6);
        fields.add(new Projection.Field("result_count", decimal(resultCount)));
        fields.add(new Projection.Field("page", decimal(effectivePageOf(request))));
        fields.add(new Projection.Field("upstream_offset", decimal(effectivePageOf(request) - 1)));
        fields.add(new Projection.Field("http_status", decimal(result.httpStatus())));
        if (result.requestId() != null) {
            fields.add(new Projection.Field("request_id", new Projection.Text(result.requestId())));
        }
        if (result.apiVersion() != null) {
            fields.add(new Projection.Field("api_version", new Projection.Text(result.apiVersion())));
        }
        return fields;
    }

    /** The decimal projection form of a count or position, shared with the specializations. */
    protected static Projection.Decimal decimal(long value) {
        return new Projection.Decimal(BigDecimal.valueOf(value));
    }
}
