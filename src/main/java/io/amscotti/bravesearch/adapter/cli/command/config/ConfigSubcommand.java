package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.OutputModeCompatibility;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriteFailure;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * Shared plumbing of the config commands: the output grammar is checked against each
 * command's own compatibility profile before any side effect — the mutating commands print
 * fixed human text and reject every machine-output spelling, while {@code config show}
 * additionally accepts its one machine mode — result lines end in exactly one LF and travel
 * through the byte-lossless result channel, whose write verdict is the shared one (a
 * downstream broken pipe is the documented silent early termination, any other write
 * failure keeps the transport status with one diagnostic line), and every local
 * configuration failure is diagnosed once on stderr with the configuration exit status.
 *
 * <p>The class carries picocli's {@code @Command} marker only so the shared {@code @Spec}
 * seam of the hierarchy is part of the command model the annotation processor validates;
 * each concrete subcommand's own {@code @Command} names and describes it.
 */
@Command
abstract class ConfigSubcommand {

    @Spec CommandSpec spec;

    private final ResultWriter results;

    private final Function<Writer, DiagnosticsSink> diagnosticsFactory;

    protected ConfigSubcommand(ResultWriter results, Function<Writer, DiagnosticsSink> diagnosticsFactory) {
        this.results = Objects.requireNonNull(results, "results");
        this.diagnosticsFactory = Objects.requireNonNull(diagnosticsFactory, "diagnosticsFactory");
    }

    /** The diagnostics channel of this invocation's error writer. */
    protected final DiagnosticsSink diagnostics() {
        return diagnosticsFactory.apply(spec.commandLine().getErr());
    }

    /**
     * Rejects the output options the command's profile does not accept as usage errors
     * before any side effect.
     *
     * @throws ParameterException when the invocation carried an incompatible combination
     */
    protected final void rejectIncompatibleOutput(CommandOutputProfile profile, OutputRequest request) {
        List<String> violations = OutputModeCompatibility.check(profile, request);
        if (!violations.isEmpty()) {
            throw new ParameterException(
                    spec.commandLine(), qualifiedName() + ": " + String.join("; ", violations));
        }
    }

    /**
     * Rejects every machine-output spelling on a command whose text is fixed human output:
     * an explicit {@code --output} or {@code --pretty} is a usage error before any side
     * effect.
     */
    protected final void rejectRemoteOutputFlags(OutputMode output, boolean pretty) {
        rejectIncompatibleOutput(
                CommandOutputProfile.LOCAL,
                new OutputRequest(output != null, output == null ? OutputMode.HUMAN : output, pretty, false, false));
    }

    /** Emits the local-configuration failure once and yields the configuration exit status. */
    protected final int failLocally(LocalConfigException failure) {
        diagnostics().emit(qualifiedName() + ": " + failure.getMessage());
        return ExitCodeMapper.forKind(FailureKind.LOCAL_CONFIG);
    }

    /**
     * Writes one fixed human result line, terminated by exactly one LF, and yields the
     * write's verdict: success, silent success for a downstream broken pipe, or the
     * transport status with one diagnostic line for every other write failure.
     */
    protected final int resultLine(String line) {
        Objects.requireNonNull(line, "line");
        return writeResult((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes one machine document's exact bytes — already LF-terminated by its codec —
     * through the result channel, adding nothing, and yields the write's verdict of
     * {@link #resultLine(String)}.
     */
    protected final int machineDocument(byte[] document) {
        Objects.requireNonNull(document, "document");
        return writeResult(document);
    }

    /**
     * One result write with the shared verdict: the byte-lossless channel carries the
     * operating system's own failure identity, so a consumer that closed the pipe is the
     * documented silent early termination and any other write failure keeps the transport
     * status with exactly one diagnostic line.
     */
    private int writeResult(byte[] document) {
        try {
            results.write(document);
        } catch (UncheckedIOException writeFailure) {
            if (ResultWriteFailure.reportsBrokenPipe(writeFailure)) {
                return ExitCodeMapper.BROKEN_PIPE;
            }
            diagnostics().emit(qualifiedName() + ": writing the result failed");
            return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
        }
        return ExitCodeMapper.SUCCESS;
    }

    /** The command's dotted invocation name for diagnostics, for example {@code config set-key}. */
    protected abstract String qualifiedName();
}
