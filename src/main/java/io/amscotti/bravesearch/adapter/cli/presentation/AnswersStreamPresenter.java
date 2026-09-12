package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.application.port.out.AnswersStreamExchange;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.AnswersRequest;

/**
 * Renders one open streaming answers exchange — from subscription to terminal record — on
 * the selected output channel and returns the run's exit status.
 *
 * <p>The presenter receives the exchange the command opened: it subscribes the channel's
 * own representation (the semantic event stream, or the raw decoded bytes), renders each
 * delivered item incrementally, and resolves the run's ending through the exchange's shared
 * terminal-cause latch — the first terminal cause decides the exit: an interruption is 130,
 * a downstream broken pipe a silent 0, a typed decode failure keeps its malformed status,
 * and every other ending keeps the cause's own status. Injectable so commands never
 * construct presentation adapters themselves.
 */
public interface AnswersStreamPresenter {

    /**
     * Drives {@code exchange} to its terminal state on the channel of {@code output} and
     * returns the run's exit status.
     *
     * @param exchange the open streaming exchange this run owns; the caller closes it
     * @param request the parsed request whose fields the renderers echo
     * @param output the invocation's parsed output options
     * @param results the byte-lossless stdout writer
     * @param diagnostics the stderr diagnostics channel of the same invocation
     */
    int present(
            AnswersStreamExchange exchange,
            AnswersRequest request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics);
}
