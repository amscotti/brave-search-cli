package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Endpoint-valid output option combinations, checked before any side effect.
 *
 * <p>Every rejection is a usage error (exit 2): an explicit {@code --output} or
 * {@code --pretty} on a command with fixed human text, a mode the command family does not
 * accept, {@code --pretty} with the line- and byte-oriented channels jsonl and raw, and
 * {@code --verbose} together with {@code --quiet}. Pure function: it decides from the parsed
 * options alone, in a fixed rule order, and never performs output of its own.
 */
public final class OutputModeCompatibility {

    private OutputModeCompatibility() {}

    /** The usage violations of {@code request} against {@code profile}; empty means valid. */
    public static List<String> check(CommandOutputProfile profile, OutputRequest request) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(request, "request");
        List<String> violations = new ArrayList<>();
        if (request.verbose() && request.quiet()) {
            violations.add("--verbose and --quiet are mutually exclusive");
        }
        if (profile.local()) {
            if (request.outputFlagPresent()) {
                violations.add("--output is not valid for local commands: their output is fixed human text");
            }
            if (request.pretty()) {
                violations.add("--pretty is not valid for local commands: their output is fixed human text");
            }
            return List.copyOf(violations);
        }
        if (!profile.allows(request.mode())) {
            violations.add("--output " + request.mode().wireName() + " is not accepted by this command");
        }
        if (request.pretty()
                && (request.mode() == OutputMode.JSONL || request.mode() == OutputMode.RAW)) {
            violations.add("--pretty is not valid with --output " + request.mode().wireName());
        }
        return List.copyOf(violations);
    }
}
