package io.amscotti.bravesearch.adapter.cli.command.config;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code config} command group over the local credential configuration. A bare invocation
 * prints the group's usage help; the mutating and reporting subcommands are registered with
 * their injected ports by the composition root. The group declares no options of its own, so
 * remote-only flags like {@code --output} are unknown at this level and fail as usage errors
 * before any subcommand side effect.
 */
@Command(name = ConfigCommand.NAME, description = "Manage the local Brave credential configuration.")
public final class ConfigCommand implements Runnable {

    /** Registration name under the root command. */
    public static final String NAME = "config";

    @Spec CommandSpec spec;

    /** Prints the group's usage help to the invocation's output writer. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
