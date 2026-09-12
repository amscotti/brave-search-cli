package io.amscotti.bravesearch.domain.result;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;

/**
 * The completed-exchange view a sequential page walk reads: the lossless body a continuation
 * rule inspects, the rate-limit snapshot a pacing decision reads, and the exchange
 * identifiers the machine documents expose.
 *
 * <p>Every paginated endpoint's result record already carries exactly these members, so
 * implementing this interface is a declaration, not an adaptation — it exists so the
 * pagination orchestrator, the page aggregate, and the multi-request documents stay written
 * once against the endpoint-agnostic shape instead of once per endpoint. Single-request
 * endpoints declare it for the same reason: their presenters read the same
 * completed-exchange view for the machine documents' exchange metadata. Transport and
 * endpoint-specific projections never appear here: this is the domain view of one finished
 * exchange, whatever adapter produced it.
 */
public interface PagedExchange {

    /** The HTTP status of the completed exchange. */
    int httpStatus();

    /** The lossless bounded body bytes of the exchange. */
    UpstreamPayload body();

    /** The rate-limit snapshot observed on the response; never null. */
    RateLimitSnapshot rateLimits();

    /** The usage counters the response offered, or null when none did. */
    Usage usage();

    /** The request identifier the response offered, or null when none did. */
    String requestId();

    /** The API version the response offered, or null when none did. */
    String apiVersion();
}
