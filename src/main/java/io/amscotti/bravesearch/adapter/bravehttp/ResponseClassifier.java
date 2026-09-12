package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.nio.charset.StandardCharsets;

/**
 * Accept enforcement of one completed, bounded, decoded response body.
 *
 * <p>Parsed response modes require the media type to be exactly {@code application/json} —
 * parameters allowed, every other spelling refused — before a non-empty body may be treated
 * as structured; a wrong or missing type is a MALFORMED failure whose diagnostic carries at
 * most a 256-byte escaped preview of the decoded body and never the observed header text,
 * because header values are untrusted input. A bodyless 2xx such as a 204 carries nothing to
 * parse, so no type is required of it. Raw response mode intentionally bypasses JSON parsing:
 * a 2xx body passes through byte-exact whatever its type or validity, while status, redirect,
 * size, and encoding rules were already enforced on the way here. Every non-2xx status routes
 * into {@link UpstreamErrorDecoder} in both modes.
 */
public final class ResponseClassifier {

    /** The most decoded body bytes a diagnostic preview may quote. */
    private static final int PREVIEW_BYTES = 256;

    private ResponseClassifier() {}

    /**
     * The outcome of a completed exchange: a success carrying status and payload, a MALFORMED
     * failure for an unacceptable 2xx, or the upstream failure of a non-2xx.
     */
    public static Outcome<BraveHttpResponse> classify(
            int statusCode, String contentType, boolean rawResponseMode, byte[] decodedBody) {
        if (statusCode < 200 || statusCode > 299) {
            return UpstreamErrorDecoder.decode(statusCode, decodedBody);
        }
        UpstreamPayload payload = new UpstreamPayload(decodedBody);
        if (rawResponseMode) {
            return new Outcome.Success<>(new BraveHttpResponse(statusCode, payload));
        }
        if (decodedBody.length == 0) {
            // a bodyless 2xx such as a 204 carries nothing to parse, so no type is required
            return new Outcome.Success<>(new BraveHttpResponse(statusCode, payload));
        }
        if (!isApplicationJson(contentType)) {
            return new Outcome.Failure<>(
                    FailureKind.MALFORMED,
                    (contentType == null || contentType.isBlank()
                            ? "response content type is missing"
                            : "response content type is not application/json")
                            + "; body preview: \"" + escapedPreview(decodedBody) + "\"",
                    null,
                    null,
                    statusCode);
        }
        return new Outcome.Success<>(new BraveHttpResponse(statusCode, payload));
    }

    /** Exactly {@code application/json} ignoring case, surrounding spaces, and parameters. */
    private static boolean isApplicationJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = (parameters < 0 ? contentType : contentType.substring(0, parameters))
                .strip();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    /**
     * The first at most {@link #PREVIEW_BYTES} decoded body bytes, cut only on a UTF-8
     * codepoint boundary so a multibyte character is never split into a replacement character
     * the body never carried, decoded as UTF-8 with interior malformed sequences replaced, and
     * every control character — C0, delete, and the C1 range — plus backslash and quote
     * escaped, so the preview is printable, bounded, and never a full body.
     */
    public static String escapedPreview(byte[] decodedBody) {
        int length = codepointSafeLength(decodedBody, Math.min(PREVIEW_BYTES, decodedBody.length));
        String text = new String(decodedBody, 0, length, StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                default -> {
                    if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * The greatest length at most {@code length} that never ends inside a UTF-8 sequence: the
     * preview is rendered as text, so a partial trailing sequence — the lead byte with too few
     * continuation bytes — is dropped whole rather than decoded into a replacement character.
     */
    private static int codepointSafeLength(byte[] body, int length) {
        if (length == 0 || length >= body.length) {
            return length;
        }
        int lead = length;
        while (lead > 0 && (body[lead - 1] & 0xc0) == 0x80) {
            lead--;
        }
        if (lead > 0 && (body[lead - 1] & 0xc0) == 0xc0) {
            int leadByte = body[lead - 1] & 0xff;
            int continuationsNeeded = leadByte >= 0xf0 ? 3 : leadByte >= 0xe0 ? 2 : 1;
            if (length - lead < continuationsNeeded) {
                return lead - 1;
            }
        }
        return length;
    }
}
