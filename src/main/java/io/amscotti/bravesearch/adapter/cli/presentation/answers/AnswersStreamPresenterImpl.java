package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.AnswersStreamPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.HeadingStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.ModeAwareWarnings;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriteFailure;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SearchPresenterBase;
import io.amscotti.bravesearch.adapter.cli.presentation.SgrStyle;
import io.amscotti.bravesearch.adapter.cli.presentation.TerminalSafeText;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.ErrorCodes;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.SseException;
import io.amscotti.bravesearch.application.stream.StreamDrain;
import io.amscotti.bravesearch.domain.answer.AnswerDecodeException;
import io.amscotti.bravesearch.domain.answer.AnswerStreamEvent;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.result.Projection;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The streaming answers renderer: one open exchange driven to its terminal on the active
 * output channel. Human writes the heading once, then every text delta as its own flushed
 * stdout document — the output is visibly incremental — and appends the citations, the
 * entities, and the usage summary when the stream completes, routing the final usage
 * tag's parse notes onto the mode's warnings channel; research-family events and
 * unknown upstream tags are concise stderr progress lines, suppressed by {@code --quiet}.
 * JSONL emits one sequenced {@code answer_delta} record per text run, one record per
 * citation, entity, and research event, preserves unknown tags as {@code upstream_event}
 * records, and closes with exactly one terminal record — the summary carrying the observed
 * usage at exact decimal scale, or the error carrying the failure code, the partial counts,
 * and the cost-unknown marker that keeps an unreported cost from reading as free. JSON
 * buffers the whole decoded stream under the injected byte ceiling and emits exactly one
 * envelope whose {@code data.upstream} is the ordered array of decoded events — a streaming
 * exchange has no single JSON body — and whose {@code meta.warnings} carries the
 * jsonl-is-preferable advisory beside any usage parse notes, because buffering hides
 * progress no other mode loses. Raw
 * relays the decoded body bytes as they arrive, unparsed.
 *
 * <p>The run waits on the exchange's shared terminal-cause latch — every ending converges
 * there, including the ones a cancelled or deadline-cut exchange signals with no terminal
 * of its own — and stops the subscription before any terminal document, so no late
 * delivery can race the terminal write. An interruption
 * keeps the conventional 130 and a termination the conventional 143, each rendering the
 * mode's signal document — never a fabricated completion; a downstream broken pipe is a
 * silent 0; a typed decode failure — a
 * malformed tag payload, an unfinished tag, an over-ceiling buffered accumulation — keeps
 * the malformed status 8; and every other ending keeps its cause's own status. Failure
 * diagnostics of a verbose run additionally report the exchange's last transport activity
 * and last semantic progress.
 */
public final class AnswersStreamPresenterImpl implements AnswersStreamPresenter {

    /** The buffered-mode advisory: buffering hides the progress jsonl streams instead. */
    public static final String JSONL_ADVISORY =
            "streaming answers with --output json buffer until completion; jsonl is preferable for progress";

    /** The stable interruption message of every streaming terminal document. */
    public static final String INTERRUPTED_MESSAGE = "interrupted before the stream completed";

    /** The stable termination message of every streaming terminal document. */
    public static final String TERMINATED_MESSAGE = "terminated before the stream completed";

    /** The research-family tags whose payloads are progress, not answer content. */
    private static final Set<String> RESEARCH_TAGS =
            Set.of("queries", "analyzing", "thinking", "progress", "blindspots");

    /** The longest research payload a stderr progress line quotes. */
    private static final int PROGRESS_QUOTE_LIMIT = 160;

    private static final String COMMAND = "answers";

    private final EnvelopeCodec envelopes;
    private final JsonlCodec jsonl;
    private final JsonMappers mappers;
    private final long maxAccumulatedBytes;
    private final SearchPresenterBase.WarningsRouter warnings;

    /**
     * @param envelopes the success and failure envelope codec of JSON mode
     * @param jsonl the streaming record codec of JSONL mode
     * @param mappers the stable machine mappers, used to read tag payload trees
     * @param maxAccumulatedBytes the buffered-mode ceiling over the decoded event stream
     * @param warnings the per-invocation warnings-channel router of the parse notes
     */
    public AnswersStreamPresenterImpl(
            EnvelopeCodec envelopes,
            JsonlCodec jsonl,
            JsonMappers mappers,
            long maxAccumulatedBytes,
            SearchPresenterBase.WarningsRouter warnings) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.jsonl = Objects.requireNonNull(jsonl, "jsonl");
        this.mappers = Objects.requireNonNull(mappers, "mappers");
        this.maxAccumulatedBytes = maxAccumulatedBytes;
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }
    @Override
    public int present(
            AnswersStreamExchange exchange,
            AnswersRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return switch (output.mode()) {
            case HUMAN -> humanStream(exchange, request, output, results, diagnostics);
            case JSONL -> recordStream(exchange, output, results, diagnostics);
            case JSON -> bufferedStream(exchange, output, results, diagnostics);
            case RAW -> rawStream(exchange, output, results, diagnostics);
        };
    }

    private int humanStream(
            AnswersStreamExchange exchange,
            AnswersRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics) {
        StringBuilder answer = new StringBuilder();
        List<String> citations = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        long[] counts = new long[3];
        Usage[] usage = new Usage[1];
        int headingOutcome = writeHeading(
                (HeadingStyle.bold("Answer for: " + request.question(), output) + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                exchange.cancellation(),
                results,
                diagnostics);
        if (headingOutcome >= 0) {
            return headingOutcome;
        }
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(
                exchange.cancellation(),
                event -> humanEvent(event, output, exchange.cancellation(), results, diagnostics, answer, citations, entities, counts, usage));
        exchange.semanticFrames().subscribe(drain);
        exchange.cancellation().awaitUninterruptibly();
        drain.stopNow();
        CancellationContext.Cause cause = terminalCause(exchange);
        if (isSuccess(cause, drain)) {
            int noted = routeUsageNotes(usage[0], output, results, diagnostics);
            if (noted >= 0) {
                return noted;
            }
            StringBuilder sections = new StringBuilder();
            appendHumanCitations(sections, citations);
            if (!entities.isEmpty()) {
                StringBuilder entitySection = new StringBuilder("Entities:\n");
                entities.forEach(line -> entitySection.append("- ").append(line).append('\n'));
                appendTerminalSection(sections, entitySection.toString());
            }
            if (usage[0] != null) {
                appendTerminalSection(sections, AnswersPresenterImpl.usageLine(usage[0], output) + "\n");
            }
            StringBuilder terminal = new StringBuilder();
            if (answer.isEmpty()) {
                // a delta run whose every text sanitized to nothing answered nothing
                terminal.append("No answer.\n");
            } else if (answer.charAt(answer.length() - 1) != '\n') {
                terminal.append('\n');
            }
            if (!sections.isEmpty()) {
                // one blank line separates the answer text from the terminal sections
                terminal.append('\n').append(sections);
            }
            int written = writeTerminal(
                    terminal.toString().getBytes(StandardCharsets.UTF_8), exchange, results, diagnostics);
            return written >= 0 ? written : ExitCodeMapper.SUCCESS;
        }
        return failureExit(exchange, output, results, diagnostics, cause, drain.failure().orElse(null), counts, usage[0] == null);
    }

    private void humanEvent(
            AnswerStreamEvent event,
            OutputRequest output,
            CancellationContext cancellation,
            ResultWriter results,
            DiagnosticsSink diagnostics,
            StringBuilder answer,
            List<String> citations,
            List<String> entities,
            long[] counts,
            Usage[] usage) {
        switch (event) {
            case AnswerStreamEvent.Text text -> {
                // upstream answer text reaches the terminal only through the safety rule
                String delta = TerminalSafeText.sanitize(text.text());
                writeDocument(delta.getBytes(StandardCharsets.UTF_8), cancellation, results, diagnostics);
                answer.append(delta);
                counts[0]++;
            }
            case AnswerStreamEvent.Tagged tagged -> {
                switch (tagged.tag()) {
                    case "citation" -> {
                        citations.add(humanCitation(parseTree(tagged.payload())));
                        counts[1]++;
                    }
                    case "entity" -> {
                        entities.add(humanEntity(parseTree(tagged.payload())));
                        counts[2]++;
                    }
                    case "usage" -> usage[0] = StreamPayloads.usage(parseTree(tagged.payload()));
                    default -> {
                        if (RESEARCH_TAGS.contains(tagged.tag())) {
                            humanProgress(output, diagnostics, tagged.tag(), tagged.payload());
                        } else if (!output.quiet()) {
                            diagnostics.emit(COMMAND + ": upstream event <" + tagged.tag() + ">");
                        }
                    }
                }
            }
            case AnswerStreamEvent.UnknownTag unknown -> {
                if (!output.quiet()) {
                    diagnostics.emit(COMMAND + ": upstream event <" + unknown.name() + ">");
                }
            }
            case AnswerStreamEvent.Passthrough ignored -> {}
        }
    }

    private int recordStream(
            AnswersStreamExchange exchange, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        long[] counts = new long[3];
        Usage[] usage = new Usage[1];
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(
                exchange.cancellation(),
                event -> recordEvent(event, exchange.cancellation(), results, diagnostics, counts, usage));
        exchange.semanticFrames().subscribe(drain);
        exchange.cancellation().awaitUninterruptibly();
        drain.stopNow();
        CancellationContext.Cause cause = terminalCause(exchange);
        if (isSuccess(cause, drain)) {
            int noted = routeUsageNotes(usage[0], output, results, diagnostics);
            if (noted >= 0) {
                return noted;
            }
            RequestMeta openMeta = exchange.openMeta();
            List<Projection.Field> summary = new ArrayList<>(6);
            summary.add(decimalField("http_status", openMeta.httpStatus()));
            if (openMeta.requestId() != null) {
                summary.add(new Projection.Field("request_id", new Projection.Text(openMeta.requestId())));
            }
            if (openMeta.apiVersion() != null) {
                summary.add(new Projection.Field("api_version", new Projection.Text(openMeta.apiVersion())));
            }
            summary.add(decimalField("deltas_emitted", counts[0]));
            summary.add(decimalField("citations_seen", counts[1]));
            summary.add(decimalField("entities_seen", counts[2]));
            int written = writeTerminal(
                    jsonl.encodeStreamSummary(
                            COMMAND, summary, usage[0] == null ? null : jsonl.usageNode(usage[0]), usage[0] == null),
                    exchange,
                    results,
                    diagnostics);
            return written >= 0 ? written : ExitCodeMapper.SUCCESS;
        }
        return failureExit(exchange, output, results, diagnostics, cause, drain.failure().orElse(null), counts, usage[0] == null);
    }

    private void recordEvent(
            AnswerStreamEvent event,
            CancellationContext cancellation,
            ResultWriter results,
            DiagnosticsSink diagnostics,
            long[] counts,
            Usage[] usage) {
        switch (event) {
            case AnswerStreamEvent.Text text -> {
                writeDocument(
                        jsonl.encodeAnswerDelta(COMMAND, counts[0]++, text.text()), cancellation, results, diagnostics);
            }
            case AnswerStreamEvent.Tagged tagged -> {
                switch (tagged.tag()) {
                    case "citation" -> {
                        writeDocument(
                                jsonl.encodeStreamCitation(COMMAND, StreamPayloads.citation(parseTree(tagged.payload()))),
                                cancellation,
                                results,
                                diagnostics);
                        counts[1]++;
                    }
                    case "entity" -> {
                        writeDocument(
                                jsonl.encodeStreamEntity(COMMAND, StreamPayloads.entity(parseTree(tagged.payload()))),
                                cancellation,
                                results,
                                diagnostics);
                        counts[2]++;
                    }
                    case "usage" -> usage[0] = StreamPayloads.usage(parseTree(tagged.payload()));
                    default -> writeDocument(
                            RESEARCH_TAGS.contains(tagged.tag())
                                    ? jsonl.encodeResearchProgress(COMMAND, tagged.tag(), parseTree(tagged.payload()))
                                    : jsonl.encodeUpstreamEvent(COMMAND, tagged.tag(), tagged.payload()),
                            cancellation,
                            results,
                            diagnostics);
                }
            }
            case AnswerStreamEvent.UnknownTag unknown -> writeDocument(
                    jsonl.encodeUpstreamEvent(
                            COMMAND, unknown.name(), unknown.rawText(), unknown.sseName(), unknown.sseId(), unknown.sseRetryMillis()),
                    cancellation,
                    results,
                    diagnostics);
            case AnswerStreamEvent.Passthrough ignored -> {}
        }
    }

    private int bufferedStream(
            AnswersStreamExchange exchange, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        StringBuilder answer = new StringBuilder();
        ArrayNode events = JsonNodeFactory.instance.arrayNode();
        List<List<Projection.Field>> citations = new ArrayList<>();
        List<List<Projection.Field>> entities = new ArrayList<>();
        long[] counts = new long[3];
        long[] bytes = new long[1];
        Usage[] usage = new Usage[1];
        StreamDrain<AnswerStreamEvent> drain = new StreamDrain<>(
                exchange.cancellation(),
                event -> bufferedEvent(event, answer, events, citations, entities, counts, bytes, usage));
        exchange.semanticFrames().subscribe(drain);
        exchange.cancellation().awaitUninterruptibly();
        drain.stopNow();
        CancellationContext.Cause cause = terminalCause(exchange);
        RequestMeta openMeta = exchange.openMeta();
        if (isSuccess(cause, drain)) {
            List<Projection.Field> fields = new ArrayList<>(6);
            if (!answer.isEmpty()) {
                fields.add(new Projection.Field("answer", new Projection.Text(answer.toString())));
            }
            fields.add(decimalField("delta_count", counts[0]));
            fields.add(decimalField("citation_count", counts[1]));
            fields.add(new Projection.Field("citations", new Projection.Sequence(nested(citations))));
            fields.add(decimalField("entity_count", counts[2]));
            fields.add(new Projection.Field("entities", new Projection.Sequence(nested(entities))));
            RequestMeta meta = new RequestMeta(
                    openMeta.requestId(), openMeta.httpStatus(), openMeta.apiVersion(), openMeta.rateLimits(), usage[0]);
            List<String> envelopeWarnings = new ArrayList<>();
            envelopeWarnings.addAll(collectedUsageNotes(usage[0], output, results, diagnostics));
            envelopeWarnings.add(JSONL_ADVISORY);
            int written = writeTerminal(
                    envelopes.encodeSuccess(
                            COMMAND, new Projection(fields), events, meta, envelopeWarnings, output.pretty()),
                    exchange,
                    results,
                    diagnostics);
            return written >= 0 ? written : ExitCodeMapper.SUCCESS;
        }
        return failureExit(exchange, output, results, diagnostics, cause, drain.failure().orElse(null), counts, usage[0] == null);
    }

    private void bufferedEvent(
            AnswerStreamEvent event,
            StringBuilder answer,
            ArrayNode events,
            List<List<Projection.Field>> citations,
            List<List<Projection.Field>> entities,
            long[] counts,
            long[] bytes,
            Usage[] usage) {
        switch (event) {
            case AnswerStreamEvent.Text text -> {
                ObjectNode entry = nextEvent(events, bytes, utf8Length(text.text()));
                entry.put("kind", "text");
                entry.put("text", text.text());
                answer.append(text.text());
                counts[0]++;
            }
            case AnswerStreamEvent.Tagged tagged -> {
                JsonNode payload = parseTree(tagged.payload());
                ObjectNode entry = nextEvent(events, bytes, utf8Length(tagged.payload()));
                entry.put("kind", "tag");
                entry.put("tag", tagged.tag());
                entry.set("payload", payload);
                switch (tagged.tag()) {
                    case "citation" -> {
                        citations.add(StreamPayloads.citation(payload));
                        counts[1]++;
                    }
                    case "entity" -> {
                        entities.add(StreamPayloads.entity(payload));
                        counts[2]++;
                    }
                    case "usage" -> usage[0] = StreamPayloads.usage(payload);
                    default -> {}
                }
            }
            case AnswerStreamEvent.UnknownTag unknown -> {
                ObjectNode entry = nextEvent(events, bytes, utf8Length(unknown.rawText()));
                entry.put("kind", "unknown_tag");
                entry.put("tag", unknown.name());
                entry.put("raw", unknown.rawText());
            }
            case AnswerStreamEvent.Passthrough passthrough -> {
                ObjectNode entry = nextEvent(events, bytes, utf8Length(passthrough.reason()));
                entry.put("kind", "passthrough");
                entry.put("reason", passthrough.reason());
            }
        }
    }

    private int rawStream(
            AnswersStreamExchange exchange, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        StreamDrain<byte[]> drain = new StreamDrain<>(
                exchange.cancellation(),
                chunk -> writeDocument(chunk, exchange.cancellation(), results, diagnostics));
        exchange.decodedRawFrames().subscribe(drain);
        exchange.cancellation().awaitUninterruptibly();
        drain.stopNow();
        CancellationContext.Cause cause = terminalCause(exchange);
        if (isSuccess(cause, drain)) {
            return ExitCodeMapper.SUCCESS;
        }
        return failureExit(exchange, output, results, diagnostics, cause, drain.failure().orElse(null), new long[3], true);
    }

    /** The run's terminal cause, defaulting to the clean close when nothing latched. */
    private static CancellationContext.Cause terminalCause(AnswersStreamExchange exchange) {
        return exchange.cancellation().cause().orElse(CancellationContext.Cause.CLOSED);
    }

    /** A run ended successfully exactly when nothing failed and the cause is the clean close. */
    private static boolean isSuccess(CancellationContext.Cause cause, StreamDrain<?> drain) {
        return drain.failure().isEmpty() && cause == CancellationContext.Cause.CLOSED;
    }

    /**
     * The shared terminal render of a failed run: the interruption, termination, and
     * broken-pipe causes keep their own exits, a typed failure keeps its failure kind's
     * exit, and every other cause keeps its own; human and raw diagnose once on stderr,
     * jsonl writes its counted error record, and json writes one failure envelope — each
     * carrying the cost-unknown marker when no final usage arrived.
     */
    private int failureExit(
            AnswersStreamExchange exchange,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics,
            CancellationContext.Cause cause,
            Throwable failure,
            long[] counts,
            boolean costUnknown) {
        if (cause == CancellationContext.Cause.BROKEN_PIPE) {
            return ExitCodeMapper.BROKEN_PIPE;
        }
        boolean interrupted = cause == CancellationContext.Cause.SIGINT;
        boolean terminated = cause == CancellationContext.Cause.SIGTERM;
        boolean signalled = interrupted || terminated;
        int exit = interrupted
                ? ExitCodeMapper.SIGINT
                : terminated ? ExitCodeMapper.SIGTERM : ExitCodeMapper.forKind(typedKind(failure));
        String code = interrupted
                ? ErrorCodes.INTERRUPTED
                : terminated ? ErrorCodes.TERMINATED : ErrorCodes.codeFor(typedKind(failure));
        String message = failureMessage(cause, failure);
        boolean costNote = costUnknown && !signalled;
        switch (output.mode()) {
            case JSONL -> {
                // a live exchange observed its open headers, so the error record always
                // carries that snapshot's windows — the rendering the envelope shares
                int written = writeTerminal(
                        jsonl.encodeStreamError(
                                COMMAND,
                                code,
                                message,
                                false,
                                counts[0],
                                counts[1],
                                costUnknown,
                                exchange.openMeta().rateLimits()),
                        exchange,
                        results,
                        diagnostics);
                if (written >= 0) {
                    return written;
                }
            }
            case JSON -> {
                RequestMeta openMeta = exchange.openMeta();
                int written = writeTerminal(
                        envelopes.encodeFailure(
                                COMMAND,
                                code,
                                message,
                                false,
                                null,
                                new RequestMeta(
                                        openMeta.requestId(),
                                        openMeta.httpStatus(),
                                        openMeta.apiVersion(),
                                        openMeta.rateLimits(),
                                        null),
                                List.of(
                                        decimalField("deltas_emitted", counts[0]),
                                        decimalField("citations_seen", counts[1]),
                                        new Projection.Field("cost_unknown", new Projection.Flag(costUnknown))),
                                output.pretty()),
                        exchange,
                        results,
                        diagnostics);
                if (written >= 0) {
                    return written;
                }
            }
            case HUMAN, RAW -> diagnostics.emit(
                    COMMAND + ": " + message + (costNote ? " (no final usage arrived; cost unknown)" : ""));
        }
        if (output.verbose()) {
            diagnostics.emit(
                    COMMAND + ": last transport activity " + exchange.lastTransportActivity() + "; last semantic progress "
                            + (exchange.lastSemanticProgress() == null ? "none" : exchange.lastSemanticProgress()));
        }
        return exit;
    }

    /** The typed status of a stream failure: decode and ceiling failures are malformed, an abrupt end transport. */
    private static FailureKind typedKind(Throwable failure) {
        if (failure instanceof AnswerDecodeException || failure instanceof SseException) {
            return FailureKind.MALFORMED;
        }
        return FailureKind.TRANSPORT;
    }

    private static String failureMessage(CancellationContext.Cause cause, Throwable failure) {
        if (cause == CancellationContext.Cause.SIGINT) {
            return INTERRUPTED_MESSAGE;
        }
        if (cause == CancellationContext.Cause.SIGTERM) {
            return TERMINATED_MESSAGE;
        }
        if (cause == CancellationContext.Cause.IDLE_TIMEOUT) {
            return "the stream was idle beyond its deadline";
        }
        if (cause == CancellationContext.Cause.WALL_TIMEOUT) {
            return "the stream exceeded its wall-clock deadline";
        }
        if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            return failure.getMessage();
        }
        return "the streaming run failed";
    }

    /**
     * Writes one streamed document, converting the consumer's departure into the latched
     * pipe cause; any other write failure keeps the drain's subscriber-failure path.
     */
    private int writeDocument(
            byte[] document, CancellationContext cancellation, ResultWriter results, DiagnosticsSink diagnostics) {
        try {
            results.write(document);
        } catch (UncheckedIOException writeFailure) {
            if (ResultWriteFailure.reportsBrokenPipe(writeFailure)) {
                cancellation.latch(CancellationContext.Cause.BROKEN_PIPE);
            }
            throw writeFailure;
        }
        return -1;
    }

    /**
     * The heading write, which sits outside the drain handler and so owns its own
     * departure handling: a consumer already gone is the silent zero like every other
     * first write, while any other write failure keeps the shared write-failure verdict
     * of {@link ResultWriteFailure} — the transport status with one diagnostic line.
     */
    private int writeHeading(
            byte[] document, CancellationContext cancellation, ResultWriter results, DiagnosticsSink diagnostics) {
        try {
            results.write(document);
        } catch (UncheckedIOException writeFailure) {
            if (ResultWriteFailure.reportsBrokenPipe(writeFailure)) {
                cancellation.latch(CancellationContext.Cause.BROKEN_PIPE);
                return ExitCodeMapper.BROKEN_PIPE;
            }
            return ResultWriteFailure.exitFor(writeFailure, diagnostics, COMMAND);
        }
        return -1;
    }

    /** The terminal document write: a consumer leaving mid-document keeps the silent zero. */
    private int writeTerminal(
            byte[] document, AnswersStreamExchange exchange, ResultWriter results, DiagnosticsSink diagnostics) {
        try {
            results.write(document);
            return -1;
        } catch (UncheckedIOException writeFailure) {
            if (ResultWriteFailure.reportsBrokenPipe(writeFailure)
                    || exchange.cancellation().cause().orElse(null) == CancellationContext.Cause.BROKEN_PIPE) {
                return ExitCodeMapper.BROKEN_PIPE;
            }
            diagnostics.emit(COMMAND + ": writing the result document failed");
            return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
        }
    }

    private JsonNode parseTree(String payload) {
        return mappers.upstreamReader().readTree(payload);
    }

    /**
     * Routes the parse notes of the final usage tag onto the mode's warnings channel at
     * stream completion, the earliest every note is known: stderr diagnostics for human,
     * one structured warning record before jsonl's summary. Returns the exit a failed
     * warning-record write owns, or -1 when the run may render on.
     */
    private int routeUsageNotes(Usage usage, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        if (usage == null || usage.notes().isEmpty()) {
            return -1;
        }
        ModeAwareWarnings channel = warningsChannel(output, results, diagnostics);
        try {
            usage.notes().forEach(channel::warning);
        } catch (UncheckedIOException writeFailure) {
            return ResultWriteFailure.exitFor(writeFailure, diagnostics, COMMAND);
        }
        return -1;
    }

    /** The usage parse notes the buffered json envelope collects into its {@code meta.warnings}. */
    private List<String> collectedUsageNotes(
            Usage usage, OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        if (usage == null || usage.notes().isEmpty()) {
            return List.of();
        }
        ModeAwareWarnings channel = warningsChannel(output, results, diagnostics);
        usage.notes().forEach(channel::warning);
        return channel.collected();
    }

    /** The mode-aware warnings channel of this invocation, opened by the injected router. */
    private ModeAwareWarnings warningsChannel(OutputRequest output, ResultWriter results, DiagnosticsSink diagnostics) {
        return warnings.open(output.mode(), diagnostics, results, output.quiet());
    }

    /**
     * Opens the next upstream event entry after counting its bytes against the buffered
     * ceiling; a breach is the typed malformed failure, and the run's cancel signal is the
     * latch the drain's failure path already arms.
     */
    private ObjectNode nextEvent(ArrayNode events, long[] bytes, int eventBytes) {
        bytes[0] += eventBytes;
        if (bytes[0] > maxAccumulatedBytes) {
            throw new SseException.Overflow(
                    "the accumulated stream exceeded its byte ceiling of " + maxAccumulatedBytes);
        }
        ObjectNode entry = events.addObject();
        entry.put("event_index", events.size() - 1);
        return entry;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void appendHumanCitations(StringBuilder terminal, List<String> citations) {
        if (citations.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder("Citations:\n");
        int number = 1;
        for (String citation : citations) {
            section.append(number++).append(". ").append(citation).append('\n');
        }
        appendTerminalSection(terminal, section.toString());
    }

    /** Appends one terminal section, separated from the sections before it by a blank line. */
    private static void appendTerminalSection(StringBuilder terminal, String section) {
        if (!terminal.isEmpty()) {
            terminal.append('\n');
        }
        terminal.append(section);
    }

    private static String humanCitation(JsonNode payload) {
        String url = TerminalSafeText.sanitize(payload.path("url").asString(""));
        String snippet = payload.path("snippet").asString(null);
        if (snippet == null || snippet.isEmpty()) {
            return url;
        }
        return url + "\n   " + TerminalSafeText.sanitize(snippet);
    }

    private static String humanEntity(JsonNode payload) {
        String name = payload.path("name").asString(null);
        String url = payload.path("url").asString(null);
        name = name == null ? null : TerminalSafeText.sanitize(name);
        url = url == null ? null : TerminalSafeText.sanitize(url);
        boolean hasName = name != null && !name.isEmpty();
        boolean hasUrl = url != null && !url.isEmpty();
        if (hasName && hasUrl) {
            return name + " (" + url + ")";
        }
        if (hasName) {
            return name;
        }
        if (hasUrl) {
            return url;
        }
        return payload.toString();
    }

    private void humanProgress(OutputRequest output, DiagnosticsSink diagnostics, String tag, String payload) {
        if (output.quiet()) {
            return;
        }
        String quoted = payload.length() > PROGRESS_QUOTE_LIMIT ? progressQuote(payload) : payload;
        // upstream research text reaches a stderr line through the same safety rule; the
        // label renders bold in a colorable render context, the payload plain
        String label = SgrStyle.bold(COMMAND + ": research " + tag + ":", output);
        diagnostics.emit(label + " " + TerminalSafeText.sanitize(quoted));
    }

    /**
     * The payload's opening quote: the first {@link #PROGRESS_QUOTE_LIMIT} UTF-16 units
     * with the cut backed off a high surrogate, so an astral character is never split
     * into a lone surrogate — a non-control character the safety rule keeps, but one
     * that encodes to mojibake on the diagnostics line.
     */
    private static String progressQuote(String payload) {
        int cut = PROGRESS_QUOTE_LIMIT;
        if (Character.isHighSurrogate(payload.charAt(cut - 1))) {
            cut--;
        }
        return payload.substring(0, cut) + "…";
    }

    private static List<Projection.Value> nested(List<List<Projection.Field>> fieldSets) {
        List<Projection.Value> values = new ArrayList<>(fieldSets.size());
        for (List<Projection.Field> fieldSet : fieldSets) {
            values.add(new Projection.Nested(new Projection(fieldSet)));
        }
        return values;
    }

    private static Projection.Field decimalField(String key, long value) {
        return new Projection.Field(key, new Projection.Decimal(BigDecimal.valueOf(value)));
    }
}
