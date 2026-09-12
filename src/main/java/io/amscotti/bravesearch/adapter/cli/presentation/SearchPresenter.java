package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.output.OutputRequest;

/**
 * Renders one completed search exchange — success or expected failure — on the selected
 * output channel, for any command's request and result types.
 *
 * <p>The command hands over everything the channel needs — the outcome, the parsed request
 * whose fields the renderers echo, the parsed output request, the byte-lossless stdout
 * writer, and the stderr diagnostics sink — and takes back the exit status, because
 * rendering owns its own failure contract: a broken stdout pipe stays a silent zero while
 * any other write failure keeps its transport status, and a failed exchange renders its
 * mode's failure document with the failure kind's own status. The stdout writer is a {@link
 * ResultWriter}, not a print writer, so the operating system's genuine write failure —
 * including the POSIX broken pipe — reaches the classification instead of a swallowed
 * error flag. Injectable so commands never construct presentation adapters themselves.
 *
 * @param <TReq> the command's parsed request type
 * @param <TRes> the command's exchange result type
 */
public interface SearchPresenter<TReq, TRes> {

    /**
     * Renders {@code outcome} and returns the run's exit status.
     *
     * @param outcome the completed exchange to render, success or expected failure
     * @param request the parsed request whose fields the renderers echo
     * @param output the invocation's parsed output options
     * @param results the byte-lossless stdout writer
     * @param diagnostics the stderr diagnostics channel of the same invocation
     */
    int present(
            Outcome<TRes> outcome,
            TReq request,
            OutputRequest output,
            ResultWriter results,
            DiagnosticsSink diagnostics);
}
