package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.output.OutputMode;

/**
 * Opens the per-invocation mode-aware warnings channel; composition supplies the adapter.
 * Every presenter family that routes advisories — the single-request search presenters, the
 * sequential walks, and the streaming answers renderer — opens its channel through this one
 * seam, so it is a type of its own rather than a member of any one family.
 */
@FunctionalInterface
public interface WarningsRouter {

    /** The warnings channel of one invocation, for the active output mode. */
    ModeAwareWarnings open(OutputMode mode, DiagnosticsSink diagnostics, ResultWriter results, boolean quiet);
}
