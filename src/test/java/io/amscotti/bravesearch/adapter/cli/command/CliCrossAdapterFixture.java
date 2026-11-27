package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.api.BraveSearchClient;

/** Rule 3 fixture: CLI adapter types depending on a sibling adapter and on the api surface. */
public final class CliCrossAdapterFixture {

    private final BraveHttpTransport gateway = null;

    public BraveHttpTransport gateway() {
        return gateway;
    }

    /** Rule 3 fixture: adapter dependency on the api surface. */
    public static final class ApiLeak {

        private final BraveSearchClient client = null;

        public BraveSearchClient client() {
            return client;
        }
    }
}
