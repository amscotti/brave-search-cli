package io.amscotti.bravesearch.domain.output;

import java.util.Set;

/**
 * The output capability of one command family: whether its text is fixed human output, and the
 * exact set of output modes it accepts.
 *
 * <p>Contract vocabulary shared by option parsing and compatibility checking — it mirrors the
 * output-mode compatibility matrix of the CLI contract — so it lives beside {@link OutputMode}
 * rather than in an adapter: local commands print fixed human text and accept no output flags,
 * remote commands accept every machine mode except that raw is legal only for operations that
 * issue exactly one upstream request, and {@code config show} offers its single machine record
 * in json mode and nothing else beyond human text.
 */
public record CommandOutputProfile(boolean local, Set<OutputMode> allowedModes) {

    /** Fixed human text; both output flags are usage errors. */
    public static final CommandOutputProfile LOCAL = new CommandOutputProfile(true, Set.of(OutputMode.HUMAN));

    /** Human text plus every machine mode, including the single-request-only raw channel. */
    public static final CommandOutputProfile REMOTE =
            new CommandOutputProfile(false, Set.of(OutputMode.values()));

    /** Human text plus the buffered machine modes; raw needs exactly one upstream request. */
    public static final CommandOutputProfile REMOTE_MULTI_REQUEST =
            new CommandOutputProfile(false, Set.of(OutputMode.HUMAN, OutputMode.JSON, OutputMode.JSONL));

    /** Human text plus one machine record in json mode. */
    public static final CommandOutputProfile CONFIG_SHOW =
            new CommandOutputProfile(false, Set.of(OutputMode.HUMAN, OutputMode.JSON));

    public CommandOutputProfile {
        allowedModes = Set.copyOf(allowedModes);
    }

    /** Whether {@code mode} is one of the modes this command family accepts. */
    public boolean allows(OutputMode mode) {
        return allowedModes.contains(mode);
    }
}
