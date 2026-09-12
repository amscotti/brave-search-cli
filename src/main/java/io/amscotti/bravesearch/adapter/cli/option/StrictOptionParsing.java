package io.amscotti.bravesearch.adapter.cli.option;

import picocli.CommandLine;

/**
 * The strict parser configuration every command line in this process shares: abbreviated
 * long options stay disabled and unmatched options are rejected as errors instead of
 * becoming option or positional arguments, so a script's meaning can never drift when a new
 * option name appears. Applied recursively over the whole command tree, because each
 * subcommand owns its own parser settings.
 */
public final class StrictOptionParsing {

    private StrictOptionParsing() {}

    /** Applies the strict configuration to {@code commandLine} and every registered subcommand. */
    public static CommandLine apply(CommandLine commandLine) {
        commandLine.setAbbreviatedOptionsAllowed(false);
        commandLine.setUnmatchedOptionsAllowedAsOptionParameters(false);
        commandLine.setUnmatchedOptionsArePositionalParams(false);
        commandLine.getSubcommands().values().forEach(StrictOptionParsing::apply);
        return commandLine;
    }
}
