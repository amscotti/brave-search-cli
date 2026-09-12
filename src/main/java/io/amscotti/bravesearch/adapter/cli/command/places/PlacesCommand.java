package io.amscotti.bravesearch.adapter.cli.command.places;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The {@code places} command group over the local-place endpoints. A bare invocation
 * prints the group's usage help; the group's subcommands — {@code search} first — are
 * registered with their injected ports by the composition root. The group declares no
 * options of its own, so remote-only flags like {@code --output} are unknown at this
 * level and fail as usage errors before any subcommand side effect.
 */
@Command(name = PlacesCommand.NAME, description = "Search local places: points of interest, cities, and addresses.")
public final class PlacesCommand implements Runnable {

    /** Registration name under the root command. */
    public static final String NAME = "places";

    @Spec CommandSpec spec;

    /** Prints the group's usage help to the invocation's output writer. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
