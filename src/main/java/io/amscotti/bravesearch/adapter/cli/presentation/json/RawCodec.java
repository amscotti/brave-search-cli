package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;

/**
 * Raw output: the exact decoded upstream body bytes and nothing else.
 *
 * <p>No trailing newline is added or removed, no JSON normalization happens, and content-coding
 * bytes are already decoded before the payload reaches this codec, so the passthrough is the
 * identity on decoded bytes.
 */
public final class RawCodec {

    public byte[] passthrough(UpstreamPayload payload) {
        return payload.toByteArray();
    }
}
