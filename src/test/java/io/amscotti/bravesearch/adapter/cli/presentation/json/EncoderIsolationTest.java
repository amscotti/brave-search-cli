package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.metadata.RequestMeta;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.result.Projection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Encoders keep no caller-owned mutable state, so earlier bytes survive later input changes. */
final class EncoderIsolationTest {

    @Test
    void mutatingTheUpstreamSourceArrayAfterConstructionDoesNotAlterEncoding() {
        byte[] source = CodecSamples.UPSTREAM_JSON.getBytes(StandardCharsets.UTF_8);
        UpstreamPayload payload = new UpstreamPayload(source);
        byte[] first = CodecSamples.envelopeCodec()
                .encodeSuccess("web", CodecSamples.fullMetaProjection(), payload, CodecSamples.fullMeta(), List.of());
        source[0] = 'X';
        byte[] second = CodecSamples.envelopeCodec()
                .encodeSuccess("web", CodecSamples.fullMetaProjection(), payload, CodecSamples.fullMeta(), List.of());
        assertArrayEquals(first, second);
    }

    @Test
    void interleavedEncodesWithDifferentInputsStayIndependent() {
        List<String> warnings = new ArrayList<>();
        warnings.add("first warning");
        byte[] first = CodecSamples.envelopeCodec()
                .encodeSuccess(
                        "web",
                        CodecSamples.fullMetaProjection(),
                        new UpstreamPayload(CodecSamples.UPSTREAM_JSON.getBytes(StandardCharsets.UTF_8)),
                        CodecSamples.fullMeta(),
                        warnings);
        byte[] other = CodecSamples.zeroResultSuccess();
        byte[] failureBetween = CodecSamples.authenticationFailure();
        warnings.add("added after the first encode");
        byte[] second = CodecSamples.envelopeCodec()
                .encodeSuccess(
                        "web",
                        CodecSamples.fullMetaProjection(),
                        new UpstreamPayload(CodecSamples.UPSTREAM_JSON.getBytes(StandardCharsets.UTF_8)),
                        CodecSamples.fullMeta(),
                        List.of("first warning"));
        assertArrayEquals(first, second);
        assertArrayEquals(other, CodecSamples.zeroResultSuccess());
        assertArrayEquals(failureBetween, CodecSamples.authenticationFailure());
    }

    @Test
    void projectionFieldListMutationAfterConstructionDoesNotAlterEncoding() {
        List<Projection.Field> fields = new ArrayList<>();
        fields.add(new Projection.Field("result_count", new Projection.Decimal(java.math.BigDecimal.ONE)));
        Projection projection = new Projection(fields);
        byte[] first =
                CodecSamples.jsonlCodec().encodeSummary("web", projection.fields());
        fields.add(new Projection.Field("sneaky", new Projection.Flag(true)));
        byte[] second =
                CodecSamples.jsonlCodec().encodeSummary("web", projection.fields());
        assertArrayEquals(first, second);
    }

    @Test
    void metaRateLimitListMutationAfterConstructionDoesNotAlterEncoding() {
        List<RateLimitWindow> windows = new ArrayList<>();
        windows.add(new RateLimitWindow("request", 1, 0, java.time.Duration.ofMillis(1200)));
        RequestMeta meta = new RequestMeta(null, 200, null, windows, null);
        byte[] first = CodecSamples.envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        meta,
                        List.of());
        windows.add(new RateLimitWindow("token", 0, 0, java.time.Duration.ZERO));
        windows.remove(0);
        byte[] second = CodecSamples.envelopeCodec()
                .encodeSuccess(
                        "web",
                        Projection.empty(),
                        new UpstreamPayload("{}".getBytes(StandardCharsets.UTF_8)),
                        meta,
                        List.of());
        assertArrayEquals(first, second);
    }
}
