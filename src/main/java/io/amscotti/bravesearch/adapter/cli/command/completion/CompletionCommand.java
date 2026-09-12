package io.amscotti.bravesearch.adapter.cli.command.completion;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriteFailure;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The shell completion command: prints the completion script of one supported shell to
 * stdout, generated from the fully registered command model by picocli's generator, so the
 * offered subcommands and option names are exactly the live grammar's own. The bash form is
 * the generator's script verbatim; the zsh form prepends the {@code #compdef} registration
 * header to the same body, which zsh's {@code bashcompinit} emulation executes natively —
 * both scripts embed only names from the model, never a value from the environment, and
 * both end in exactly one line feed.
 *
 * <p>The script travels through the byte-lossless result channel, so a failed write keeps
 * the operating system's own identity: a downstream consumer that closed the pipe is the
 * documented silent early termination (exit 0, nothing further printed), while every other
 * write failure keeps the transport status with one fixed diagnostic line. The invocation's
 * help writer cannot carry that identity, because a {@code PrintWriter} collapses every
 * failed write into its error flag.
 *
 * <p>The shell argument accepts exactly one value, {@code bash} or {@code zsh}; anything
 * else — a missing value, a second value, another shell's name — is a usage error, so an
 * unknown shell never prints a script it cannot stand behind.
 */
@Command(
        name = CompletionCommand.NAME,
        description = "Print a shell completion script for bash or zsh to stdout.",
        footer = "Install by sourcing the printed script from your shell profile.")
public final class CompletionCommand implements Callable<Integer> {

    /** Registration name under the root command. */
    public static final String NAME = "completion";

    /** The shell argument's only accepted values, in the order help lists them. */
    public static final List<String> SUPPORTED_SHELLS = List.of("bash", "zsh");

    /**
     * The zsh registration header: zsh's completion system loads this file through {@code
     * compdef}, and the body it precedes registers itself through {@code bashcompinit} when
     * sourced, exactly as it does under bash.
     */
    private static final String ZSH_HEADER = """
            #compdef %s
            # %s completion for zsh, bridged from the picocli-generated script through
            # bashcompinit; source this file from your zsh profile.
            """;

    private final ResultWriter results;

    @Spec CommandSpec spec;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "SHELL",
            description = "The target shell: bash or zsh.")
    String shell;

    /**
     * @param results the byte-lossless process stdout writer; write failures carry the
     *     operating system's identity so a broken pipe stays classifiable
     */
    public CompletionCommand(ResultWriter results) {
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public Integer call() {
        if (!SUPPORTED_SHELLS.contains(shell)) {
            throw new ParameterException(
                    spec.commandLine(), "SHELL must be one of " + String.join(", ", SUPPORTED_SHELLS));
        }
        String scriptName = spec.root().name();
        String generated = CanonicalCompletionOrder.sort(AutoComplete.bash(scriptName, spec.root().commandLine()));
        String script = "zsh".equals(shell) ? zshScript(scriptName, generated) : withOneTrailingLf(generated);
        try {
            results.write(script.getBytes(StandardCharsets.UTF_8));
        } catch (UncheckedIOException writeFailure) {
            if (ResultWriteFailure.reportsBrokenPipe(writeFailure)) {
                // the consumer terminated the stream early: silent success, nothing further
                return ExitCodeMapper.BROKEN_PIPE;
            }
            // every other write failure keeps the transport status with one fixed line
            spec.commandLine().getErr().println(NAME + ": writing the completion script failed");
            return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
        }
        return ExitCodeMapper.SUCCESS;
    }

    /** The zsh form: the registration header, then the generated body without its bash shebang. */
    private static String zshScript(String scriptName, String generated) {
        String body = generated.startsWith("#!") ? generated.substring(generated.indexOf('\n') + 1) : generated;
        return ZSH_HEADER.formatted(scriptName, scriptName) + withOneTrailingLf(body);
    }

    /** The text with exactly one trailing line feed, whatever trailing breaks it arrived with. */
    private static String withOneTrailingLf(String text) {
        return text.stripTrailing() + "\n";
    }
}
