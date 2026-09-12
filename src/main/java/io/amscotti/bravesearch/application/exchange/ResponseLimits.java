package io.amscotti.bravesearch.application.exchange;

/**
 * The byte ceilings of one response exchange, injected so contract tests run at kibibyte scale
 * while production ships the documented defaults: 16 MiB of compressed transfer, 16 MiB of
 * decoded body, and 1 MiB of structured error body.
 *
 * <p>The compressed and decoded ceilings are enforced separately: a gzip response counts its
 * wire bytes against the first and its inflated bytes against the second, and an identity
 * response counts its bytes exactly once against the decoded ceiling.
 */
public record ResponseLimits(long compressedBytes, long decodedBytes, long errorBytes) {

    /** One kibibyte, the scale contract tests inject. */
    public static final long KIB = 1024;

    /** One mebibyte, the scale of the production defaults. */
    public static final long MIB = KIB * KIB;

    /** The documented production ceilings: 16 MiB compressed, 16 MiB decoded, 1 MiB of error body. */
    public static ResponseLimits production() {
        return new ResponseLimits(16 * MIB, 16 * MIB, MIB);
    }

    public ResponseLimits {
        if (compressedBytes <= 0 || decodedBytes <= 0 || errorBytes <= 0) {
            throw new IllegalArgumentException("response limits must be positive: "
                    + compressedBytes + "/" + decodedBytes + "/" + errorBytes);
        }
    }
}
