package io.amscotti.bravesearch.application;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpWebSearchGateway;
import picocli.CommandLine;

/**
 * Rules 2 and 9 fixture: an application type depending on an adapter and on picocli. Never used at
 * runtime.
 */
public final class ApplicationBoundaryFixture {

    private final BraveHttpWebSearchGateway gateway = null;
    private final CommandLine commandLine = null;

    public BraveHttpWebSearchGateway gateway() {
        return gateway;
    }

    public CommandLine commandLine() {
        return commandLine;
    }
}
