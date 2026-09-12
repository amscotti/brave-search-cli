package io.amscotti.bravesearch.domain.output;

import java.util.Objects;

/**
 * One invocation's parsed output-related options.
 *
 * <p>{@code outputFlagPresent} records whether {@code --output} appeared on the command line
 * at all, which is distinct from the mode it produced: local commands reject the presence of
 * the flag even when its value spells the default human channel.
 *
 * <p>{@code colorable} carries the conservative ANSI decision of the process composition:
 * terminal detection succeeded, no disabler spoke, and {@code --no-color} was absent. Human
 * documents consult it; machine documents never do. It defaults to off, because the historical
 * callers render inside pipes and must keep their plain bytes.
 *
 * <p>{@code width} is the render width human layout wraps to, bounded to the documented
 * 40..500 column range — a value outside it can only be a wiring defect — and defaults to
 * 100, the width every invocation uses unless the terminal seam supplied an exported,
 * sane {@code COLUMNS}. Machine documents never consult it.
 *
 * <p>Lives beside {@link OutputMode} and {@link CommandOutputProfile} because it is shared
 * contract vocabulary of option parsing and compatibility checking, constructed by every
 * command family rather than by a single adapter.
 */
public record OutputRequest(
        boolean outputFlagPresent,
        OutputMode mode,
        boolean pretty,
        boolean verbose,
        boolean quiet,
        boolean colorable,
        int width) {

    /** The render width every invocation wraps to unless the terminal seam supplied one. */
    public static final int DEFAULT_WIDTH = 100;

    /** The narrowest and widest render widths the human layout documents. */
    public static final int MINIMUM_WIDTH = 40;

    public static final int MAXIMUM_WIDTH = 500;

    public OutputRequest {
        Objects.requireNonNull(mode, "mode");
        if (width < MINIMUM_WIDTH || width > MAXIMUM_WIDTH) {
            throw new IllegalArgumentException(
                    "render width must be between " + MINIMUM_WIDTH + " and " + MAXIMUM_WIDTH + ": " + width);
        }
    }

    /** The shape without a color decision or an explicit width, as every piped caller renders. */
    public OutputRequest(boolean outputFlagPresent, OutputMode mode, boolean pretty, boolean verbose, boolean quiet) {
        this(outputFlagPresent, mode, pretty, verbose, quiet, false, DEFAULT_WIDTH);
    }

    /** The shape without an explicit width: the default width, like an unexported COLUMNS. */
    public OutputRequest(
            boolean outputFlagPresent, OutputMode mode, boolean pretty, boolean verbose, boolean quiet, boolean colorable) {
        this(outputFlagPresent, mode, pretty, verbose, quiet, colorable, DEFAULT_WIDTH);
    }
}
