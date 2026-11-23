package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The version flag is an all-command global: every command of the tree accepts it and prints
 * the root's version line, the local commands included, so no node silently swallows it.
 */
final class VersionFlagOnEveryCommandTest {

    /** The commands outside the remote search family, where the flag once printed nothing. */
    private static final List<String> LOCAL_COMMANDS = List.of("config", "config show", "completion", "places");

    @Test
    void theVersionFlagPrintsTheRootVersionLineOnEveryCommandOfTheTree() throws IOException {
        String versionLine = new Main.VersionProvider().getVersion()[0];
        Main main = new Main();
        List<List<String>> invocations = new ArrayList<>();
        collect(main.assemble(new PrintWriter(new java.io.StringWriter())), new ArrayList<>(), invocations);
        List<String> paths = invocations.stream().map(segments -> String.join(" ", segments)).toList();

        assertTrue(paths.containsAll(LOCAL_COMMANDS), () -> "the walk reaches the local commands: " + paths);

        for (List<String> invocation : invocations) {
            String path = String.join(" ", invocation);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(stdout, ISO_8859_1), true);

            // executed from the root, the way a user types it: brave-search config show --version
            String[] args = Stream.concat(invocation.stream(), Stream.of("--version")).toArray(String[]::new);

            int exit = main.assemble(out).execute(args);

            assertEquals(0, exit, () -> "exit of " + path);
            assertEquals(versionLine + "\n", stdout.toString(ISO_8859_1), () -> "stdout of " + path);
        }
    }

    /** Every invocation path of the tree: the bare root, then each node below it. */
    private static void collect(CommandLine command, List<String> prefix, List<List<String>> into) {
        into.add(List.copyOf(prefix));
        command.getSubcommands().forEach((name, subcommand) -> {
            List<String> below = new ArrayList<>(prefix);
            below.add(name);
            collect(subcommand, below, into);
        });
    }
}
