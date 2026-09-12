package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.io.Console;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Single injectable terminal state detector for presentation code.
 *
 * <p>Presentation classes receive their terminal boolean and their render width from this
 * component instead of probing the console or the environment themselves. Console, TTY,
 * and {@code COLUMNS} inspection lives here and nowhere else in presentation; it never
 * reads passwords.
 *
 * <p>The process-wide color decision is deliberately conservative: color-capability is
 * reported only when the process console exists, reports itself a terminal through {@link
 * Console#isTerminal()}, and neither {@code NO_COLOR} (any nonempty value) nor {@code
 * TERM=dumb} speaks against it. A missing console, a failing probe, or any other
 * uncertainty leaves color off — a maybe never enables. The {@code java.io.Console} API
 * is process-wide, so this one decision covers the whole process: stdout human documents
 * apply it, and stderr carries no styling except one labeled surface — the bold labels of
 * a streaming answers run's research progress lines, which ride the same decision and
 * stay plain behind any pipe — because a process console cannot prove a redirected
 * stderr colorable. Each stream is treated by what was proven about it, never by
 * assumption.
 *
 * <p>The render-width decision is equally conservative: an exported {@code COLUMNS}
 * supplies the width only when it parses as an integer inside the documented 40..500
 * range; anything else — unexported, non-numeric, padded, or out of range — leaves the
 * default width. {@code COLUMNS} is honored when exported; otherwise 100.
 */
public final class TerminalDetector {

    private final BooleanSupplier interactive;

    private final IntSupplier width;

    /** Creates a detector from an injectable terminal-boolean supplier at the default width. */
    public TerminalDetector(BooleanSupplier interactive) {
        this.interactive = Objects.requireNonNull(interactive, "interactive");
        this.width = () -> OutputRequest.DEFAULT_WIDTH;
    }

    /** Creates a detector from injectable terminal and width suppliers. */
    public TerminalDetector(BooleanSupplier interactive, IntSupplier width) {
        this.interactive = Objects.requireNonNull(interactive, "interactive");
        this.width = Objects.requireNonNull(width, "width");
    }

    /** Whether output currently targets an interactive terminal. */
    public boolean interactive() {
        return interactive.getAsBoolean();
    }

    /**
     * The render width this invocation's terminal reported; a failing width probe leaves
     * the default, because an unreadable width is exactly the uncertainty the default
     * absorbs.
     */
    public int width() {
        try {
            return width.getAsInt();
        } catch (RuntimeException probeFailed) {
            return OutputRequest.DEFAULT_WIDTH;
        }
    }

    /**
     * The process's own color-capability decision: the attached console must be a terminal and
     * the environment must hold no disabler. This is the supplier the process composition root
     * injects; every probe failure resolves to color-off inside {@link #consoleIsATerminal} and
     * {@link #decisiveColor}.
     */
    public static boolean processColorCapable() {
        return decisiveColor(TerminalDetector::processConsoleIsATerminal, System::getenv);
    }

    /**
     * The process's own render width: the exported {@code COLUMNS} when it is a sane
     * integer, and the default width otherwise.
     */
    public static int processWidth() {
        return decisiveWidth(System::getenv);
    }

    /**
     * Whether the process console exists and reports itself a terminal; a missing console or
     * a failing probe is never a terminal, so the answer is always safe to deny on.
     */
    static boolean processConsoleIsATerminal() {
        return consoleIsATerminal(System::console);
    }

    /** The attachment probe over an injectable console source, for hermetic verification. */
    static boolean consoleIsATerminal(Supplier<Console> console) {
        try {
            Console attached = console.get();
            return attached != null && attached.isTerminal();
        } catch (RuntimeException probeFailed) {
            return false;
        }
    }

    /**
     * The combined conservative color decision over injectable probes: a terminal console plus an
     * environment without disablers enables color, and anything else — a non-terminal console,
     * {@code NO_COLOR} nonempty, {@code TERM=dumb}, or a probe that fails — disables it.
     */
    static boolean decisiveColor(BooleanSupplier consoleIsATerminal, Function<String, String> environment) {
        try {
            if (!consoleIsATerminal.getAsBoolean()) {
                return false;
            }
            String noColor = environment.apply("NO_COLOR");
            if (noColor != null && !noColor.isEmpty()) {
                return false;
            }
            String term = environment.apply("TERM");
            return !"dumb".equals(term);
        } catch (RuntimeException probeFailed) {
            return false;
        }
    }

    /**
     * The conservative width decision over an injectable environment: an exported {@code
     * COLUMNS} that parses as an integer inside the documented 40..500 range supplies the
     * width; anything else leaves the default. The parse follows {@code Integer.parseInt}
     * semantics exactly, so a leading plus ({@code +80}) or leading zeros ({@code 0080})
     * are honored at their parsed value when it is in range, while a minus sign parses but
     * its negative value is always out of range; surrounding whitespace or any other
     * non-integer spelling fails the parse and never widens or narrows the layout.
     */
    static int decisiveWidth(Function<String, String> environment) {
        try {
            String columns = environment.apply("COLUMNS");
            if (columns == null) {
                return OutputRequest.DEFAULT_WIDTH;
            }
            int parsed = Integer.parseInt(columns);
            if (parsed < OutputRequest.MINIMUM_WIDTH || parsed > OutputRequest.MAXIMUM_WIDTH) {
                return OutputRequest.DEFAULT_WIDTH;
            }
            return parsed;
        } catch (RuntimeException probeFailed) {
            return OutputRequest.DEFAULT_WIDTH;
        }
    }
}
