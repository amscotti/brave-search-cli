package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** Repeated encoding of equal inputs is byte-identical, so machine output is deterministic. */
final class EncoderDeterminismTest {

    @Test
    void successEnvelopeEncodesIdenticallyOnEveryCall() {
        assertArrayEquals(CodecSamples.fullMetaSuccess(), CodecSamples.fullMetaSuccess());
        assertArrayEquals(CodecSamples.zeroResultSuccess(), CodecSamples.zeroResultSuccess());
    }

    @Test
    void failureEnvelopeEncodesIdenticallyOnEveryCall() {
        assertArrayEquals(CodecSamples.authenticationFailure(), CodecSamples.authenticationFailure());
        assertArrayEquals(CodecSamples.rateLimitedFailure(), CodecSamples.rateLimitedFailure());
    }

    @Test
    void jsonlLinesEncodeIdenticallyOnEveryCall() {
        assertArrayEquals(CodecSamples.jsonlResultLine(), CodecSamples.jsonlResultLine());
        assertArrayEquals(CodecSamples.jsonlSummaryLine(), CodecSamples.jsonlSummaryLine());
        assertArrayEquals(CodecSamples.jsonlWarningLine(), CodecSamples.jsonlWarningLine());
        assertArrayEquals(CodecSamples.jsonlErrorLine(), CodecSamples.jsonlErrorLine());
    }
}
