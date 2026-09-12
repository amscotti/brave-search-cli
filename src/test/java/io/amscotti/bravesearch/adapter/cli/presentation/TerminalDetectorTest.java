package io.amscotti.bravesearch.adapter.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The conservative color-capability detection of the process console: color is enabled only
 * when the attached console provably is a terminal and no disabler speaks, and every form of
 * uncertainty — a missing console, a console that is not a terminal, an environment probe
 * that fails — leaves color off. The console and environment seams are injected, so the
 * process-wide probes themselves never run inside the test JVM; the console-attached-but-
 * not-terminal combination is proven by the PTY process tests instead, because a {@code
 * Console} instance cannot be fabricated in process.
 */
final class TerminalDetectorTest {

    @Test
    void aTerminalConsoleWithoutDisablersEnablesColor() {
        assertTrue(TerminalDetector.decisiveColor(() -> true, environment("TERM", "xterm-256color")));
    }

    @Test
    void aConsoleThatIsNotATerminalDisablesColorWhateverTheEnvironment() {
        assertFalse(TerminalDetector.decisiveColor(() -> false, environment("TERM", "xterm-256color")));
    }

    @Test
    void anyNonemptyNoColorValueDisablesColor() {
        assertFalse(TerminalDetector.decisiveColor(() -> true, environment("NO_COLOR", "1")));
        assertFalse(TerminalDetector.decisiveColor(() -> true, environment("NO_COLOR", "false")));
    }

    @Test
    void anEmptyNoColorValueStaysSilentLikeAnUnsetOne() {
        assertTrue(TerminalDetector.decisiveColor(() -> true, environment("NO_COLOR", "")));
    }

    @Test
    void aDumbTermDisablesColor() {
        assertFalse(TerminalDetector.decisiveColor(() -> true, environment("TERM", "dumb")));
    }

    @Test
    void aFailingEnvironmentProbeDisablesColor() {
        Function<String, String> failing = name -> {
            throw new IllegalStateException("environment is unreadable");
        };
        assertFalse(TerminalDetector.decisiveColor(() -> true, failing));
    }

    @Test
    void aFailingConsoleProbeDisablesColor() {
        BooleanSupplier failing = () -> {
            throw new IllegalStateException("console is unreadable");
        };
        assertFalse(TerminalDetector.decisiveColor(failing, environment("TERM", "xterm-256color")));
    }

    @Test
    void aMissingConsoleIsNeverATerminal() {
        assertFalse(TerminalDetector.consoleIsATerminal(() -> null));
    }

    @Test
    void aFailingConsoleAttachmentProbeIsNeverATerminal() {
        assertFalse(TerminalDetector.consoleIsATerminal(
                () -> {
                    throw new IllegalStateException("console is unreadable");
                }));
    }

    @Test
    void anExportedSaneColumnsValueSuppliesTheRenderWidth() {
        assertEquals(120, TerminalDetector.decisiveWidth(columns("120")));
        assertEquals(40, TerminalDetector.decisiveWidth(columns("40")));
        assertEquals(500, TerminalDetector.decisiveWidth(columns("500")));
    }

    @Test
    void aLeadingPlusOrLeadingZerosSpellingIsHonoredAtItsParsedValue() {
        // the parse is Integer.parseInt semantics: +80 and 0080 are the integer 80
        assertEquals(80, TerminalDetector.decisiveWidth(columns("+80")));
        assertEquals(80, TerminalDetector.decisiveWidth(columns("0080")));
        assertEquals(500, TerminalDetector.decisiveWidth(columns("+500")));
    }

    @Test
    void anInsaneColumnsValueFallsBackToTheDefaultWidth() {
        assertEquals(100, TerminalDetector.decisiveWidth(columns("10")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("1000")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("0")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("-80")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("wide")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("120x")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns("")));
        assertEquals(100, TerminalDetector.decisiveWidth(columns(" 120 ")));
    }

    @Test
    void aMissingColumnsValueLeavesTheDefaultWidth() {
        assertEquals(100, TerminalDetector.decisiveWidth(name -> null));
    }

    @Test
    void aFailingColumnsProbeLeavesTheDefaultWidth() {
        Function<String, String> failing = name -> {
            throw new IllegalStateException("environment is unreadable");
        };
        assertEquals(100, TerminalDetector.decisiveWidth(failing));
    }

    @Test
    void theDetectorSuppliesItsInjectedWidthAndDefaultsToOneHundred() {
        assertEquals(72, new TerminalDetector(() -> true, () -> 72).width());
        assertEquals(100, new TerminalDetector(() -> true).width());
        assertEquals(
                100,
                new TerminalDetector(() -> true, () -> {
                    throw new IllegalStateException("width is unreadable");
                }).width());
    }

    private static Function<String, String> columns(String value) {
        Map<String, String> env = new HashMap<>();
        env.put("COLUMNS", value);
        return env::get;
    }

    private static Function<String, String> environment(String overridden, String value) {
        Map<String, String> env = new HashMap<>();
        env.put("TERM", "xterm-256color");
        env.put(overridden, value);
        return env::get;
    }
}
