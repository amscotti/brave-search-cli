package io.amscotti.bravesearch.api;

import io.amscotti.bravesearch.domain.metadata.RateLimitSnapshot;
import io.amscotti.bravesearch.domain.metadata.Usage;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * One completed rich-result exchange on the public surface: the typed exchange metadata — the
 * observed HTTP status, the rate-limit snapshot, the usage counters, and the request and
 * API version identifiers the response headers offered — plus the caller-owned lossless
 * snapshot of the whole upstream body. The upstream tree is a private field:
 * {@link #upstream()} is the only public surface of the exchange's body, and no tree-holding
 * type appears in the public shape.
 */
public final class RichResponse {

    private final int httpStatus;

    private final RateLimitSnapshot rateLimits;

    private final Usage usage;

    private final String requestId;

    private final String apiVersion;

    private final UpstreamTree upstreamTree;

    public RichResponse(
            int httpStatus,
            RateLimitSnapshot rateLimits,
            Usage usage,
            String requestId,
            String apiVersion,
            UpstreamTree upstreamTree) {
        this.httpStatus = httpStatus;
        this.rateLimits = rateLimits == null ? RateLimitSnapshot.empty() : rateLimits;
        this.usage = usage;
        this.requestId = requestId;
        this.apiVersion = apiVersion;
        this.upstreamTree = Objects.requireNonNull(upstreamTree, "upstreamTree");
    }

    /** The observed HTTP status of the exchange. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The rate-limit snapshot observed on the response; never null. */
    public RateLimitSnapshot rateLimits() {
        return rateLimits;
    }

    /** The usage counters the response offered, or null when none did. */
    public Usage usage() {
        return usage;
    }

    /** The request identifier the response offered, or null when none did. */
    public String requestId() {
        return requestId;
    }

    /** The API version the response offered, or null when none did. */
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * A fresh caller-owned deep copy of the lossless upstream body; every call returns an
     * independent tree, and the client never observes a caller's mutation.
     */
    public JsonNode upstream() {
        return upstreamTree.snapshot();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RichResponse that
                && httpStatus == that.httpStatus
                && rateLimits.equals(that.rateLimits)
                && Objects.equals(usage, that.usage)
                && Objects.equals(requestId, that.requestId)
                && Objects.equals(apiVersion, that.apiVersion)
                && upstreamTree.equals(that.upstreamTree);
    }

    @Override
    public int hashCode() {
        return Objects.hash(httpStatus, rateLimits, usage, requestId, apiVersion, upstreamTree);
    }

    @Override
    public String toString() {
        return "RichResponse[httpStatus="
                + httpStatus + ", rateLimits=" + rateLimits + ", usage=" + usage + ", requestId=" + requestId
                + ", apiVersion=" + apiVersion + ", upstream=" + upstreamTree + "]";
    }
}
