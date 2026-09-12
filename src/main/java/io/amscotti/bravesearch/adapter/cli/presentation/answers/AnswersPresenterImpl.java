package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import io.amscotti.bravesearch.adapter.cli.presentation.AnswersPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.HeadingStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SgrStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.TerminalSafeText;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.AnswersContent;
import io.amscotti.bravesearch.domain.result.AnswersResult;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The blocking answers renderer: the specialization of {@link SearchPresenterBase} whose
 * document is one answer, not a listing. The human document renders the heading, the
 * answer text verbatim, the citations section when the response carried citations, and —
 * exactly when the exchange observed usage headers — the trailing usage-and-cost summary
 * line. The JSON projection carries the answer (omitted when the response offered none),
 * the citation count, and each citation's usable members, while the envelope's {@code
 * data.upstream} keeps the lossless document and {@code meta.usage} keeps the
 * header-observed counters and exact-scale costs. Raw writes the decoded body bytes
 * exactly.
 *
 * <p>The JSONL success seam stays trivial on purpose: blocking answers is incompatible
 * with the JSONL channel and the command rejects that combination before any dispatch, so
 * no success of this renderer can ever reach the JSONL branch — the streaming answers
 * family owns the JSONL records of this command.
 */
public final class AnswersPresenterImpl extends SearchPresenterBase<AnswersRequest, AnswersResult>
        implements AnswersPresenter {

    private static final String NO_ANSWER = "No answer.";

    private final AnswersProjectionExtractor extractor;

    public AnswersPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            RawCodec raw,
            AnswersProjectionExtractor extractor,
            WarningsRouter warnings) {
        super(envelopes, jsonl, raw, warnings);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    protected String command() {
        return AnswersPresenter.COMMAND;
    }

    @Override
    protected byte[] humanDocument(AnswersResult result, AnswersRequest request, OutputRequest output) {
        AnswersContent content = extractor.extract(result.body());
        StringBuilder text = new StringBuilder();
        text.append(HeadingStyle.bold("Answer for: " + request.question(), output)).append('\n');
        if (content.answer() == null) {
            text.append(NO_ANSWER).append('\n');
        } else {
            text.append(TerminalSafeText.sanitize(content.answer()));
            if (text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }
        StringBuilder terminal = new StringBuilder();
        if (!content.citations().isEmpty()) {
            appendHumanCitations(terminal, content.citations());
        }
        if (result.usage() != null) {
            appendTerminalSection(terminal, usageLine(result.usage(), output) + "\n");
        }
        if (!terminal.isEmpty()) {
            // one blank line separates the answer text from the terminal sections
            text.append('\n').append(terminal);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Appends one terminal section, separated from the sections before it by a blank line. */
    private static void appendTerminalSection(StringBuilder terminal, String section) {
        if (!terminal.isEmpty()) {
            terminal.append('\n');
        }
        terminal.append(section);
    }

    private static void appendHumanCitations(StringBuilder terminal, List<AnswersContent.Citation> citations) {
        StringBuilder section = new StringBuilder("Citations:\n");
        int number = 1;
        for (AnswersContent.Citation citation : citations) {
            section.append(number++)
                    .append(". ")
                    .append(TerminalSafeText.sanitize(citation.url()))
                    .append('\n');
            if (citation.snippet() != null) {
                section.append("   ").append(TerminalSafeText.sanitize(citation.snippet())).append('\n');
            }
        }
        appendTerminalSection(terminal, section.toString());
    }

    /**
     * The one-line usage-and-cost summary, carrying exactly the members the exchange
     * observed: the label renders bold in a colorable render context, the payload plain.
     */
    static String usageLine(Usage usage, OutputRequest output) {
        StringBuilder line = new StringBuilder(SgrStyle.bold("Usage:", output));
        boolean observed = false;
        if (usage.requests() != null) {
            line.append(" requests ").append(usage.requests());
            observed = true;
        }
        if (usage.queries() != null) {
            line.append(observed ? ", queries " : " queries ").append(usage.queries());
            observed = true;
        }
        if (usage.tokensIn() != null && usage.tokensOut() != null) {
            line.append(observed ? ", tokens " : " tokens ")
                    .append(usage.tokensIn())
                    .append(" in / ")
                    .append(usage.tokensOut())
                    .append(" out");
            observed = true;
        } else if (usage.tokensIn() != null) {
            line.append(observed ? ", tokens " : " tokens ").append(usage.tokensIn()).append(" in");
            observed = true;
        } else if (usage.tokensOut() != null) {
            line.append(observed ? ", tokens " : " tokens ").append(usage.tokensOut()).append(" out");
            observed = true;
        }
        if (usage.totalCost() != null) {
            line.append(observed ? ", total cost " : " total cost ")
                    .append(usage.totalCost().toPlainString());
        }
        return line.toString();
    }

    @Override
    protected Projection jsonProjection(AnswersRequest request, AnswersResult result) {
        AnswersContent content = extractor.extract(result.body());
        List<Projection.Field> fields = new ArrayList<>(3);
        if (content.answer() != null) {
            fields.add(new Projection.Field("answer", new Projection.Text(content.answer())));
        }
        fields.add(new Projection.Field("citation_count", decimal(content.citations().size())));
        List<Projection.Value> projected = new ArrayList<>(content.citations().size());
        for (AnswersContent.Citation citation : content.citations()) {
            projected.add(new Projection.Nested(citationProjection(citation)));
        }
        fields.add(new Projection.Field("citations", new Projection.Sequence(projected)));
        return new Projection(fields);
    }

    private static Projection citationProjection(AnswersContent.Citation citation) {
        List<Projection.Field> fields = new ArrayList<>(6);
        if (citation.number() != null) {
            fields.add(new Projection.Field("number", decimal(citation.number())));
        }
        fields.add(new Projection.Field("url", new Projection.Text(citation.url())));
        if (citation.favicon() != null) {
            fields.add(new Projection.Field("favicon", new Projection.Text(citation.favicon())));
        }
        if (citation.snippet() != null) {
            fields.add(new Projection.Field("snippet", new Projection.Text(citation.snippet())));
        }
        if (citation.startIndex() != null) {
            fields.add(new Projection.Field("start_index", decimal(citation.startIndex())));
        }
        if (citation.endIndex() != null) {
            fields.add(new Projection.Field("end_index", decimal(citation.endIndex())));
        }
        return new Projection(fields);
    }

    /** Unreachable for a blocking success: the command refuses the JSONL channel pre-dispatch. */
    @Override
    protected List<List<Projection.Field>> jsonlResultRecords(AnswersResult result) {
        return List.of();
    }

    /** Unreachable for a blocking success: the command refuses the JSONL channel pre-dispatch. */
    @Override
    protected List<Projection.Field> jsonlSummaryFields(AnswersResult result, AnswersRequest request, int resultCount) {
        List<Projection.Field> fields = new ArrayList<>(3);
        fields.add(new Projection.Field("http_status", decimal(result.httpStatus())));
        if (result.requestId() != null) {
            fields.add(new Projection.Field("request_id", new Projection.Text(result.requestId())));
        }
        if (result.apiVersion() != null) {
            fields.add(new Projection.Field("api_version", new Projection.Text(result.apiVersion())));
        }
        return fields;
    }

    /** The decimal projection form of a count or index, shared with the specializations. */
    private static Projection.Decimal decimal(long value) {
        return new Projection.Decimal(BigDecimal.valueOf(value));
    }
}
