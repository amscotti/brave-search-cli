package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.PagedExchange;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The api-boundary conversion of one completed domain exchange into its public response:
 * the typed metadata members plus the eagerly materialized lossless upstream snapshot, with
 * an unparseable body mapped to the malformed outcome instead of an exception.
 */
final class ApiResponses {

    private ApiResponses() {}

    /** The six-member constructor every public response record shares. */
    @FunctionalInterface
    interface ResponseFactory<Response> {

        Response create(
                int httpStatus,
                RateLimitSnapshot rateLimits,
                Usage usage,
                String requestId,
                String apiVersion,
                UpstreamTree upstreamTree);
    }

    static <Exchange extends PagedExchange, Response> Outcome<Response> convert(
            Outcome<Exchange> outcome,
            Function<UpstreamPayload, JsonNode> upstreamReader,
            ResponseFactory<Response> responseFactory) {
        return switch (outcome) {
            case Outcome.Success<Exchange> success -> {
                Exchange exchange = success.value();
                JsonNode upstream;
                try {
                    upstream = upstreamReader.apply(exchange.body());
                } catch (JacksonException unparseable) {
                    // the snapshot must never lie about the wire: a body that is not exactly
                    // one JSON document is a malformed response, never a broken tree
                    yield new Outcome.Failure<>(
                            FailureKind.MALFORMED, "the upstream body did not parse as one JSON document");
                }
                yield new Outcome.Success<>(responseFactory.create(
                        exchange.httpStatus(),
                        exchange.rateLimits(),
                        exchange.usage(),
                        exchange.requestId(),
                        exchange.apiVersion(),
                        UpstreamTree.of(upstream)));
            }
            case Outcome.Failure<Exchange> failure -> new Outcome.Failure<>(
                    failure.kind(), failure.diagnostic(), failure.upstream(), failure.rateLimits(), failure.httpStatus());
        };
    }
}
