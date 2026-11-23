package io.amscotti.bravesearch.adapter.cli.option;

import java.io.PrintWriter;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * The strict parser configuration every command line in this process shares: abbreviated
 * long options stay disabled and unmatched options are rejected as errors instead of
 * becoming option or positional arguments, so a script's meaning can never drift when a new
 * option name appears. Applied recursively over the whole command tree, because each
 * subcommand owns its own parser settings.
 *
 * <p>The same pass installs the parameter-failure report. When an argument cannot be matched,
 * the parser reports it together with every later argument its failed parse could not place —
 * the option's own value and the query text — which reads as though the user's query were an
 * unknown option. The report names only the arguments spelled like options, then renders the
 * way an unmatched argument always was: the message, the suggestion of the intended option,
 * the usage, and the invalid-input exit code.
 */
public final class StrictOptionParsing {

    private StrictOptionParsing() {}

    /** Applies the strict configuration and the parameter-failure report to {@code commandLine} and every subcommand. */
    public static CommandLine apply(CommandLine commandLine) {
        commandLine.setAbbreviatedOptionsAllowed(false);
        commandLine.setUnmatchedOptionsAllowedAsOptionParameters(false);
        commandLine.setUnmatchedOptionsArePositionalParams(false);
        commandLine.setParameterExceptionHandler(StrictOptionParsing::reportParameterFailure);
        commandLine.getSubcommands().values().forEach(StrictOptionParsing::apply);
        return commandLine;
    }

    /**
     * The parameter-failure report: the message, the suggestion of the intended option when
     * there is one, the usage help, and the invalid-input exit code.
     */
    private static int reportParameterFailure(ParameterException failure, String[] args) {
        CommandLine commandLine = failure.getCommandLine();
        PrintWriter err = commandLine.getErr();
        ColorScheme colors = commandLine.getColorScheme();
        ParameterException reported = failure;
        if (failure instanceof UnmatchedArgumentException unmatched) {
            List<String> optionSpelled =
                    unmatched.getUnmatched().stream().filter(argument -> argument.startsWith("-")).toList();
            if (!optionSpelled.isEmpty() && optionSpelled.size() != unmatched.getUnmatched().size()) {
                reported = new UnmatchedArgumentException(commandLine, optionSpelled);
            }
        }
        err.println(colors.errorText(reported.getMessage()));
        if (reported instanceof UnmatchedArgumentException unmatched && !unmatched.getSuggestions().isEmpty()) {
            err.println(colors.errorText("Possible solutions: " + String.join(", ", unmatched.getSuggestions())));
        }
        commandLine.usage(err, colors);
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }
}
