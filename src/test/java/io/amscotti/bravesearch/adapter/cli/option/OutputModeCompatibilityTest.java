package io.amscotti.bravesearch.adapter.cli.option;

import static io.amscotti.bravesearch.domain.output.OutputMode.HUMAN;
import static io.amscotti.bravesearch.domain.output.OutputMode.JSON;
import static io.amscotti.bravesearch.domain.output.OutputMode.JSONL;
import static io.amscotti.bravesearch.domain.output.OutputMode.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Endpoint-valid output option combinations: which mode and flag combinations each command
 * family accepts, rejected as usage errors before any side effect.
 */
final class OutputModeCompatibilityTest {

    private static final CommandOutputProfile LOCAL = CommandOutputProfile.LOCAL;

    private static final CommandOutputProfile REMOTE_SINGLE_REQUEST = CommandOutputProfile.REMOTE;

    private static final CommandOutputProfile REMOTE_MULTI_REQUEST = CommandOutputProfile.REMOTE_MULTI_REQUEST;

    private static final CommandOutputProfile CONFIG_SHOW = CommandOutputProfile.CONFIG_SHOW;

    @Test
    void prettyIsRejectedWithJsonlAndRaw() {
        assertTrue(
                violationsOf(REMOTE_SINGLE_REQUEST, true, JSONL, true, false, false)
                        .stream()
                        .anyMatch(violation -> violation.contains("--pretty")),
                "--pretty must be invalid with jsonl");
        assertTrue(
                violationsOf(REMOTE_SINGLE_REQUEST, true, RAW, true, false, false)
                        .stream()
                        .anyMatch(violation -> violation.contains("--pretty")),
                "--pretty must be invalid with raw");
        assertEquals(List.of(), violationsOf(REMOTE_SINGLE_REQUEST, true, JSON, true, false, false));
        assertEquals(List.of(), violationsOf(REMOTE_SINGLE_REQUEST, false, HUMAN, true, false, false));
    }

    @Test
    void localCommandsRejectOutputAndPretty() {
        for (OutputMode mode : OutputMode.values()) {
            List<String> violations = violationsOf(LOCAL, true, mode, false, false, false);
            assertEquals(
                    1,
                    violations.size(),
                    () -> "an explicit --output is the one rejection on a local command: " + violations);
            assertTrue(
                    violations.get(0).contains("--output"),
                    () -> "the rejection must name --output: " + violations);
        }
        assertTrue(
                violationsOf(LOCAL, false, HUMAN, true, false, false)
                        .stream()
                        .anyMatch(violation -> violation.contains("--pretty")),
                "--pretty must be invalid on a local command");
        assertEquals(List.of(), violationsOf(LOCAL, false, HUMAN, false, false, false));
    }

    @Test
    void rawIsRejectedForMultiRequestOperations() {
        List<String> violations = violationsOf(REMOTE_MULTI_REQUEST, true, RAW, false, false, false);
        assertEquals(1, violations.size(), () -> "raw on a multi-request operation is the one rejection: " + violations);
        assertTrue(violations.get(0).contains("raw"), () -> "the rejection must name raw: " + violations);
        for (OutputMode mode : List.of(HUMAN, JSON, JSONL)) {
            assertEquals(
                    List.of(),
                    violationsOf(REMOTE_MULTI_REQUEST, true, mode, false, false, false),
                    () -> "multi-request operations accept " + mode);
        }
    }

    @Test
    void configShowAcceptsOneJsonRecordAndRejectsRawAndJsonl() {
        assertEquals(
                List.of(),
                violationsOf(CONFIG_SHOW, true, JSON, false, false, false),
                "config show accepts --output json for its one machine record");
        assertEquals(
                List.of(),
                violationsOf(CONFIG_SHOW, true, HUMAN, false, false, false),
                "an explicit human mode stays valid for config show");
        for (OutputMode mode : List.of(JSONL, RAW)) {
            List<String> violations = violationsOf(CONFIG_SHOW, true, mode, false, false, false);
            assertEquals(
                    1,
                    violations.size(),
                    () -> mode + " on config show is the one rejection: " + violations);
            assertTrue(
                    violations.get(0).contains(mode.wireName()),
                    () -> "the rejection must name " + mode + ": " + violations);
        }
    }

    @Test
    void verboseAndQuietAreMutuallyExclusive() {
        for (CommandOutputProfile profile :
                List.of(LOCAL, REMOTE_SINGLE_REQUEST, REMOTE_MULTI_REQUEST, CONFIG_SHOW)) {
            assertTrue(
                    violationsOf(profile, false, HUMAN, false, true, true)
                            .stream()
                            .anyMatch(violation -> violation.contains("--verbose") && violation.contains("--quiet")),
                    () -> "--verbose with --quiet must be rejected for " + profile);
            assertEquals(List.of(), violationsOf(profile, false, HUMAN, false, true, false));
            assertEquals(List.of(), violationsOf(profile, false, HUMAN, false, false, true));
        }
    }

    @Test
    void validRemoteSingleRequestCombinationsPassClean() {
        for (OutputMode mode : OutputMode.values()) {
            assertEquals(
                    List.of(),
                    violationsOf(REMOTE_SINGLE_REQUEST, true, mode, false, false, false),
                    () -> "a single-request remote command accepts " + mode);
        }
    }

    private static List<String> violationsOf(
            CommandOutputProfile profile,
            boolean outputFlagPresent,
            OutputMode mode,
            boolean pretty,
            boolean verbose,
            boolean quiet) {
        return OutputModeCompatibility.check(
                profile, new OutputRequest(outputFlagPresent, mode, pretty, verbose, quiet));
    }
}
