package io.amscotti.bravesearch.adapter.cli.command;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpWebSearchGateway;
import io.amscotti.bravesearch.api.BraveSearchClient;

/** Rule 3 fixture: CLI adapter types depending on a sibling adapter and on the api surface. */
public final class CliCrossAdapterFixture {

    private final BraveHttpWebSearchGateway gateway = null;

    public BraveHttpWebSearchGateway gateway() {
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
