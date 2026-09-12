package io.amscotti.bravesearch.application.exchange;

import java.net.URI;

/** The composed request URI plus its redacted rendering for diagnostics. */
public record RequestUri(URI uri, String redacted) {}
