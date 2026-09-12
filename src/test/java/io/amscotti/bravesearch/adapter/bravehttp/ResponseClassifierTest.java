package io.amscotti.bravesearch.adapter.bravehttp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UpstreamError;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Accept enforcement of a completed 2xx body: parsed modes require the media type to be exactly
 * {@code application/json} (parameters allowed) before the body may be treated as structured,
 * and a wrong or missing type fails with a diagnostic that carries at most a 256-byte escaped
 * preview of the decoded body. Raw response mode intentionally bypasses JSON parsing: a 2xx
 * body passes through byte-exact whatever its type or validity. Every non-2xx status routes
 * into the upstream error path in both modes.
 */
final class ResponseClassifierTest {

    @Test
    void parsedModeAcceptsExactlyApplicationJsonWithOptionalParameters() {
        for (String type : new String[] {
            "application/json", "application/json;charset=UTF-8", "APPLICATION/JSON",
            "Application/Json ; charset=utf-8", "application/json ; boundary=nothing"
        }) {
            Outcome<BraveHttpResponse> outcome =
                    ResponseClassifier.classify(200, type, false, "{\"ok\":true}".getBytes(UTF_8));
            assertTrue(
                    outcome instanceof Outcome.Success<BraveHttpResponse>,
                    "the media type " + type + " is application/json with parameters and must parse");
        }
    }

    @Test
    void parsedModeRejectsEveryOtherMediaType() {
        for (String type : new String[] {
            "text/plain", "text/json", "application/vnd.api+json", "application/jsonx",
            "application/json-patch+json", "text/event-stream", "garbage", "", null
        }) {
            Outcome<BraveHttpResponse> outcome =
                    ResponseClassifier.classify(200, type, false, "{\"ok\":true}".getBytes(UTF_8));
            assertTrue(
                    outcome instanceof Outcome.Failure<BraveHttpResponse> failure
                            && failure.kind() == FailureKind.MALFORMED
                            && failure.httpStatus() == 200,
                    "the media type " + type + " must fail structured parsing with its status");
        }
    }

    @Test
    void theWrongTypeDiagnosticCarriesABoundedEscapedPreview() {
        byte[] body = new byte[2048];
        Arrays.fill(body, (byte) 'z');
        body[0] = '\n';
        body[1] = 0x01;
        body[2] = '\\';
        body[3] = '"';

        Outcome<BraveHttpResponse> outcome = ResponseClassifier.classify(200, "text/html", false, body);

        String diagnostic = diagnosticOf(outcome);
        assertTrue(diagnostic.contains("response content type is not application/json"), diagnostic);
        assertTrue(diagnostic.contains("\\n"), "a newline must arrive escaped: " + diagnostic);
        assertTrue(diagnostic.contains("\\u0001"), "a control byte must arrive escaped: " + diagnostic);
        assertTrue(diagnostic.contains("\\\\"), "a backslash must arrive escaped: " + diagnostic);
        assertTrue(diagnostic.contains("\\\""), "a quote must arrive escaped: " + diagnostic);
        assertFalse(diagnostic.contains("z".repeat(300)), "the preview may carry at most 256 body bytes");
        int previewBytes = previewBodyBytes(diagnostic);
        assertTrue(previewBytes <= 256, "preview carried " + previewBytes + " body bytes");
    }

    @Test
    void aMissingTypeDiagnosticSaysSoAndStillCarriesThePreview() {
        Outcome<BraveHttpResponse> outcome = ResponseClassifier.classify(200, null, false, "plain".getBytes(UTF_8));

        String diagnostic = diagnosticOf(outcome);
        assertTrue(diagnostic.contains("response content type is missing"), diagnostic);
        assertTrue(diagnostic.contains("plain"), "the preview shows the bounded body start");
    }

    @Test
    void rawModePassesAnyTwoXxBodyThroughByteExact() {
        byte[] truncatedJson = "{\"web\":{\"results\":[{\"ti".getBytes(UTF_8);

        for (String type : new String[] {"application/json", "text/plain", null}) {
            Outcome<BraveHttpResponse> outcome =
                    ResponseClassifier.classify(200, type, true, truncatedJson);
            assertTrue(outcome instanceof Outcome.Success<BraveHttpResponse>, "raw 2xx always succeeds");
            BraveHttpResponse response = ((Outcome.Success<BraveHttpResponse>) outcome).value();
            assertEquals(200, response.statusCode());
            assertArrayEquals(truncatedJson, response.body().toByteArray());
        }
    }

    @Test
    void nonTwoXxStatusesRouteIntoTheUpstreamErrorPathInBothModes() {
        byte[] errorBody = "{\"error\":{\"code\":\"Unhandled\",\"timestamp\":\"2026-08-31T00:00:00Z\"}}"
                .getBytes(UTF_8);

        for (boolean raw : new boolean[] {false, true}) {
            Outcome<BraveHttpResponse> outcome = ResponseClassifier.classify(503, "application/json", raw, errorBody);
            assertTrue(
                    outcome instanceof Outcome.Failure<BraveHttpResponse> failure
                            && failure.kind() == FailureKind.UPSTREAM,
                    "a 503 classifies upstream regardless of response mode");
            UpstreamError upstream = ((Outcome.Failure<BraveHttpResponse>) outcome).upstream();
            assertEquals("Unhandled", upstream.code(), "the structured code survives the routing");
            assertArrayEquals(errorBody, upstream.body().toByteArray(), "the bounded error body is preserved");
        }
    }

    @Test
    void anEmptyTwoXxBodyCarriesNothingToParseAndNeedsNoType() {
        for (String type : new String[] {"application/json", null}) {
            Outcome<BraveHttpResponse> outcome = ResponseClassifier.classify(204, type, false, new byte[0]);
            assertTrue(
                    outcome instanceof Outcome.Success<BraveHttpResponse>,
                    "an empty body such as a 204 has nothing to parse");
            assertEquals(0, ((Outcome.Success<BraveHttpResponse>) outcome).value().body().length());
        }
    }

    @Test
    void aTwoXxJsonBodySucceedsWithStatusAndPayload() {
        byte[] body = "{\"web\":{\"results\":[]}}".getBytes(UTF_8);

        Outcome<BraveHttpResponse> outcome = ResponseClassifier.classify(200, "application/json", false, body);

        BraveHttpResponse response = ((Outcome.Success<BraveHttpResponse>) outcome).value();
        assertEquals(200, response.statusCode());
        assertArrayEquals(body, response.body().toByteArray());
    }

    @Test
    void thePreviewCutNeverSplitsAMultibyteCharacter() {
        byte[][] straddled = {
            concat("a".repeat(255).getBytes(UTF_8), "ébcd".getBytes(UTF_8)),
            concat("a".repeat(254).getBytes(UTF_8), "€bcd".getBytes(UTF_8))
        };
        int[] wholeCharactersKept = {255, 254};

        for (int i = 0; i < straddled.length; i++) {
            String diagnostic = diagnosticOf(ResponseClassifier.classify(200, "text/plain", false, straddled[i]));

            assertFalse(
                    diagnostic.contains("\uFFFD"),
                    "a cut sequence must not surface as a replacement character the body never carried");
            assertTrue(diagnostic.contains("a".repeat(wholeCharactersKept[i])), diagnostic);
            assertEquals(
                    wholeCharactersKept[i],
                    previewBodyBytes(diagnostic),
                    "the cut backs off to the boundary of the whole character before it");
        }
    }

    @Test
    void aMultibyteCharacterEndingExactlyAtTheCutSurvivesWhole() {
        byte[] body = concat("a".repeat(254).getBytes(UTF_8), "é".getBytes(UTF_8));

        String diagnostic = diagnosticOf(ResponseClassifier.classify(200, "text/plain", false, body));

        assertTrue(diagnostic.contains("é"), "a character complete at the boundary is kept whole");
        assertFalse(diagnostic.contains("\uFFFD"), "a complete character never degrades into a replacement");
    }

    @Test
    void c1ControlCharactersArriveEscaped() {
        byte[] body = "x\u0080y\u009fz".getBytes(UTF_8);

        String diagnostic = diagnosticOf(ResponseClassifier.classify(200, "text/plain", false, body));

        assertTrue(diagnostic.contains("\\u0080"), diagnostic);
        assertTrue(diagnostic.contains("\\u009f"), diagnostic);
        assertFalse(diagnostic.contains("\u0080"), "a raw C1 control never rides the diagnostic");
        assertFalse(diagnostic.contains("\u009f"), "a raw C1 control never rides the diagnostic");
    }

    /** Counts the body bytes the preview represents, treating each escape as one byte. */
    private static int previewBodyBytes(String diagnostic) {
        int opening = diagnostic.indexOf("body preview: \"");
        if (opening < 0) {
            return Integer.MAX_VALUE;
        }
        String preview = diagnostic.substring(opening + "body preview: \"".length(), diagnostic.length() - 1);
        int bodyBytes = 0;
        for (int i = 0; i < preview.length(); i++) {
            if (preview.charAt(i) == '\\' && i + 1 < preview.length()) {
                i += preview.charAt(i + 1) == 'u' ? 5 : 1;
            }
            bodyBytes++;
        }
        return bodyBytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static String diagnosticOf(Outcome<BraveHttpResponse> outcome) {
        return outcome instanceof Outcome.Failure<BraveHttpResponse> failure
                ? failure.diagnostic()
                : "";
    }
}
