package io.amscotti.bravesearch.adapter.cli.presentation.context;

import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.HeadingStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.HumanListing;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.TerminalSafeText;
import io.amscotti.bravesearch.adapter.cli.presentation.TextWrap;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.result.ContextContent;
import io.amscotti.bravesearch.domain.result.ContextResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The LLM context renderer: the thin specialization of {@link SearchPresenterBase} whose
 * seams carry the context projection. The human document renders the context passages
 * verbatim under one heading and ends in the snippet and source count line — exactly one
 * {@code No context.} line when no passage was usable. The machine documents carry the two
 * stable counts this response shape provides ({@code snippet_count} from the generic
 * passages, {@code source_count} from the sources map); the response documents no token
 * estimate, so none is invented. The JSONL channel emits exactly one {@code result} record
 * of bucket {@code context} — the whole context is one logical result — followed by the
 * terminal summary. Every framing, failure, warnings, and write-failure rule lives once in
 * the base.
 */
public final class ContextPresenterImpl extends SearchPresenterBase<ContextRequest, ContextResult>
        implements ContextPresenter {

    private static final String NO_CONTEXT = "No context.\n";

    private final ContextProjectionExtractor extractor;

    public ContextPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            ContextProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return ContextPresenter.COMMAND;
    }

    @Override
    protected byte[] humanDocument(ContextResult result, ContextRequest request, OutputRequest output) {
        ContextContent content = extractor.extract(result.body());
        if (result.rateLimits() == null || result.rateLimits().windows().isEmpty()) {
            return humanDocumentOf(content, request, output);
        }
        StringBuilder listing = new StringBuilder(
                new String(humanDocumentOf(content, request, output), StandardCharsets.UTF_8));
        HumanListing.appendQuotaFooter(listing, result.rateLimits().windows(), output);
        return listing.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected Projection jsonProjection(ContextRequest request, ContextResult result) {
        ContextContent content = extractor.extract(result.body());
        return new Projection(List.of(
                new Projection.Field("snippet_count", decimal(content.entries().size())),
                new Projection.Field("source_count", decimal(content.sourceCount()))));
    }

    @Override
    protected List<List<Projection.Field>> jsonlResultRecords(ContextResult result) {
        ContextContent content = extractor.extract(result.body());
        if (content.entries().isEmpty()) {
            // empty success emits the summary alone, exactly like every other vertical
            return List.of();
        }
        List<Projection.Field> fields = new ArrayList<>(2);
        fields.add(new Projection.Field("bucket", new Projection.Text(ContextContent.BUCKET)));
        fields.add(new Projection.Field("content", new Projection.Text(content.joinedText())));
        return List.of(fields);
    }

    @Override
    protected List<Projection.Field> jsonlSummaryFields(ContextResult result, ContextRequest request, int resultCount) {
        ContextContent content = extractor.extract(result.body());
        List<Projection.Field> fields = new ArrayList<>(5);
        fields.add(new Projection.Field("snippet_count", decimal(content.entries().size())));
        fields.add(new Projection.Field("source_count", decimal(content.sourceCount())));
        fields.add(new Projection.Field("http_status", decimal(result.httpStatus())));
        if (result.requestId() != null) {
            fields.add(new Projection.Field("request_id", new Projection.Text(result.requestId())));
        }
        if (result.apiVersion() != null) {
            fields.add(new Projection.Field("api_version", new Projection.Text(result.apiVersion())));
        }
        return fields;
    }

    /**
     * The heading, every usable passage through the terminal-safety rule and the shared
     * wrap, and the trailing count line: passages are the entries of this document, so a
     * blank line separates them and follows the heading.
     */
    static byte[] humanDocumentOf(ContextContent content, ContextRequest request, OutputRequest output) {
        if (content.entries().isEmpty()) {
            return NO_CONTEXT.getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder();
        text.append(HeadingStyle.bold("Context for: " + request.query(), output)).append("\n\n");
        boolean first = true;
        for (ContextContent.Entry entry : content.entries()) {
            if (!first) {
                text.append('\n');
            }
            first = false;
            // passages are pure upstream text: they reach the document only through the
            // terminal-safety rule and the shared wrap, which keep their line structure
            for (String line : TextWrap.words(TerminalSafeText.sanitize(entry.text()), output.width())) {
                text.append(line).append('\n');
            }
        }
        text.append(content.entries().size())
                .append(content.entries().size() == 1 ? " snippet across " : " snippets across ")
                .append(content.sourceCount())
                .append(content.sourceCount() == 1 ? " source." : " sources.")
                .append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Projection.Decimal decimal(long value) {
        return new Projection.Decimal(BigDecimal.valueOf(value));
    }
}
