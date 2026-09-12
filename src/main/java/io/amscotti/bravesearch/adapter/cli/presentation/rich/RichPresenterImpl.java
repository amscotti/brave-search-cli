package io.amscotti.bravesearch.adapter.cli.presentation.rich;

import io.amscotti.bravesearch.adapter.cli.presentation.HeadingStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.HumanListing;
import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.TerminalSafeText;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import io.amscotti.bravesearch.domain.result.RichResult;
import io.amscotti.bravesearch.domain.result.RichVerticals;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The rich renderer: the specialization of {@link SearchPresenterBase} whose machine
 * documents stay generic and lossless, because the upstream response shape of the rich
 * endpoint is undocumented. The JSON projection enumerates exactly the vertical blocks
 * the response carried — each vertical's own member name and its item count — and never
 * models item internals; the lossless body rides the envelope's untouched {@code
 * data.upstream}, so nothing upstream invents or loses can be the projection's fault.
 * JSONL emits one {@code result} record per vertical block — the block is the logical
 * unit this response shape offers — labeled with the vertical's own name as its bucket,
 * followed by the terminal summary.
 *
 * <p>The human document renders one section per vertical: the verticals this CLI knows
 * how to read — the pinned known set below — as numbered listings with each item's
 * usable title, url, and description lines plus the third-party provider attribution
 * line, and every other vertical as the one-line name-and-count note that points at the
 * lossless envelope instead of guessing an internal shape. The known set is this CLI's
 * own pinned assumption, widened only when the endpoint's contract is documented; the
 * generic fallback keeps every future vertical name renderable today.
 */
public final class RichPresenterImpl extends SearchPresenterBase<RichRequest, RichResult>
        implements RichPresenter {

    private static final String UNTITLED = "(no title)";
    private static final String NO_RICH_RESULTS = "No rich results.\n";
    private static final String UNKNOWN_VERTICAL_NOTE =
            " (unrecognized vertical; the json envelope carries the lossless body)";

    /**
     * The vertical names this CLI renders as listings. The endpoint documents no
     * verticals, so this set is the CLI's pinned assumption, exactly the names the
     * fixture family exercises; every other name renders through the generic fallback.
     */
    private static final Set<String> KNOWN_VERTICALS = Set.of("videos", "images", "faqs");

    private final RichProjectionExtractor extractor;

    public RichPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            RichProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return RichPresenter.COMMAND;
    }

    @Override
    protected byte[] humanDocument(RichResult result, RichRequest request, OutputRequest output) {
        RichVerticals verticals = extractor.extract(result.body());
        if (verticals.verticals().isEmpty()) {
            return NO_RICH_RESULTS.getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder();
        text.append(HeadingStyle.bold("Rich results for: " + request.callbackKey(), output)).append("\n\n");
        boolean firstVertical = true;
        for (RichVerticals.Vertical vertical : verticals.verticals()) {
            if (!firstVertical) {
                text.append('\n');
            }
            firstVertical = false;
            if (KNOWN_VERTICALS.contains(vertical.name())) {
                appendListing(text, vertical, output);
            } else {
                appendUnknownNote(text, vertical);
            }
        }
        text.append(verticals.verticals().size())
                .append(verticals.verticals().size() == 1 ? " vertical, " : " verticals, ")
                .append(verticals.itemCount())
                .append(verticals.itemCount() == 1 ? " item.\n" : " items.\n");
        if (result.rateLimits() != null) {
            HumanListing.appendQuotaFooter(text, result.rateLimits().windows(), output);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendListing(StringBuilder text, RichVerticals.Vertical vertical, OutputRequest output) {
        text.append(TerminalSafeText.sanitize(vertical.name()))
                .append(" (")
                .append(vertical.itemCount())
                .append("):\n");
        int number = 1;
        for (RichVerticals.Item item : vertical.items()) {
            if (number > 1) {
                text.append('\n');
            }
            HumanListing.appendTitle(
                    text,
                    number,
                    vertical.itemCount(),
                    item.title() == null ? UNTITLED : item.title(),
                    output);
            HumanListing.appendMembers(text, vertical.itemCount(), membersOf(item), output);
            number++;
        }
    }

    private static List<HumanMember> membersOf(RichVerticals.Item item) {
        List<HumanMember> members = new ArrayList<>(3);
        if (item.url() != null) {
            members.add(new HumanMember(item.url(), HumanMember.Kind.URL));
        }
        if (item.description() != null) {
            members.add(new HumanMember(item.description(), HumanMember.Kind.TEXT));
        }
        if (item.source() != null) {
            members.add(new HumanMember("source: " + item.source(), HumanMember.Kind.META));
        }
        return members;
    }

    private static void appendUnknownNote(StringBuilder text, RichVerticals.Vertical vertical) {
        text.append(TerminalSafeText.sanitize(vertical.name()))
                .append(": ")
                .append(vertical.itemCount())
                .append(vertical.itemCount() == 1 ? " item" : " items")
                .append(UNKNOWN_VERTICAL_NOTE)
                .append('\n');
    }

    @Override
    protected Projection jsonProjection(RichRequest request, RichResult result) {
        RichVerticals verticals = extractor.extract(result.body());
        List<Projection.Value> projected = new ArrayList<>(verticals.verticals().size());
        for (RichVerticals.Vertical vertical : verticals.verticals()) {
            projected.add(new Projection.Nested(new Projection(List.of(
                    new Projection.Field("vertical", new Projection.Text(vertical.name())),
                    new Projection.Field("item_count", decimal(vertical.itemCount()))))));
        }
        return new Projection(List.of(
                new Projection.Field("vertical_count", decimal(verticals.verticals().size())),
                new Projection.Field("item_count", decimal(verticals.itemCount())),
                new Projection.Field("verticals", new Projection.Sequence(projected))));
    }

    @Override
    protected List<List<Projection.Field>> jsonlResultRecords(RichResult result) {
        RichVerticals verticals = extractor.extract(result.body());
        List<List<Projection.Field>> records = new ArrayList<>(verticals.verticals().size());
        for (RichVerticals.Vertical vertical : verticals.verticals()) {
            List<Projection.Field> fields = new ArrayList<>(3);
            fields.add(new Projection.Field("bucket", new Projection.Text(vertical.name())));
            fields.add(new Projection.Field("position", decimal(vertical.position())));
            fields.add(new Projection.Field("item_count", decimal(vertical.itemCount())));
            records.add(fields);
        }
        return records;
    }

    @Override
    protected List<Projection.Field> jsonlSummaryFields(RichResult result, RichRequest request, int resultCount) {
        RichVerticals verticals = extractor.extract(result.body());
        List<Projection.Field> fields = new ArrayList<>(5);
        fields.add(new Projection.Field("result_count", decimal(resultCount)));
        fields.add(new Projection.Field("item_count", decimal(verticals.itemCount())));
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
    private static Projection.Decimal decimal(long value) {
        return new Projection.Decimal(BigDecimal.valueOf(value));
    }
}
