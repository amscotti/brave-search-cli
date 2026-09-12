package io.amscotti.bravesearch.domain;

import io.amscotti.bravesearch.application.port.out.WebSearchPort;

/** Rule 1 fixture: a domain type depending on the application layer. Never used at runtime. */
public final class DomainIsolationFixture {

    private final WebSearchPort port;

    public DomainIsolationFixture(WebSearchPort port) {
        this.port = port;
    }

    public WebSearchPort port() {
        return port;
    }
}
