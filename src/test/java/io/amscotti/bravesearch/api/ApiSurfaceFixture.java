package io.amscotti.bravesearch.api;

import picocli.CommandLine;

/** Rule 11 fixture: an api type depending on a non-exported library type. Never used at runtime. */
public final class ApiSurfaceFixture {

    private final CommandLine commandLine = null;

    public CommandLine commandLine() {
        return commandLine;
    }
}
