package io.amscotti.bravesearch.adapter.bravehttp;

import io.amscotti.bravesearch.adapter.bravehttp.json.UpstreamJsonCodec;
import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.HttpFailureClassification;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Error decoding of one bounded non-2xx body.
 *
 * <p>The tolerant parse reads the Brave error envelope — {@code error.code}, an optional
 * {@code error.timestamp} kept as its original text — and treats everything else about the
 * body as opaque: missing or malformed bodies simply yield no structured code, and the raw
 * bounded bytes ride along unchanged as the {@link UpstreamError} payload, which is what makes
 * unknown {@code error.meta} fields survive losslessly for the machine surfaces.
 *
 * <p>Classification belongs to {@link HttpFailureClassification}: the status decides, except
 * that an auth-bearing structured code upgrades a 422. A structured code can never override
 * any other status. The auth-bearing code set is small and matched after normalization
 * (lower-cased, punctuation stripped): {@code unauthorized}, {@code forbidden},
 * {@code authentication_failed}, {@code invalid_api_key}, {@code entitlement_required},
 * {@code payment_required}, {@code subscription_required}.
 *
 * <p>The human diagnostic names only the status — recovery-focused by construction, quoting
 * neither the body nor the URI nor any header.
 */
public final class UpstreamErrorDecoder {

    /**
     * The normalized structured codes that make a 422 auth-bearing; documented in
     * {@code docs/brave-api-contract.md} under error decoding and classification.
     */
    private static final Set<String> AUTH_BEARING_CODES =
            Set.of(
                    "unauthorized",
                    "forbidden",
                    "authenticationfailed",
                    "invalidapikey",
                    "entitlementrequired",
                    "paymentrequired",
                    "subscriptionrequired");

    private UpstreamErrorDecoder() {}

    /** The failure of one non-2xx exchange whose body was already bounded. */
    public static Outcome.Failure<BraveHttpResponse> decode(int statusCode, byte[] errorBody) {
        String code = null;
        String timestamp = null;
        if (errorBody != null && errorBody.length > 0) {
            try {
                JsonNode error = UpstreamJsonCodec.readFirstDocument(errorBody).get("error");
                if (error != null && error.isObject()) {
                    code = textOf(error.get("code"));
                    timestamp = textOf(error.get("timestamp"));
                }
            } catch (RuntimeException unreadable) {
                // a malformed error body is an unstructured fact, never a crash
            }
        }
        FailureKind kind = HttpFailureClassification.classify(
                statusCode, authBearing(code) ? FailureKind.AUTHENTICATION : null);
        UpstreamError upstream =
                errorBody == null || errorBody.length == 0
                        ? null
                        : new UpstreamError(code, timestamp, new UpstreamPayload(errorBody));
        return new Outcome.Failure<>(kind, "upstream exchange failed with status " + statusCode, upstream, null, statusCode);
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static boolean authBearing(String code) {
        return code != null
                && AUTH_BEARING_CODES.contains(code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }
}
