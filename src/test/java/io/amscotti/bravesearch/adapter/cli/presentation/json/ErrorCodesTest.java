package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.domain.error.FailureKind;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The stable wire error-code string for every failure category. */
final class ErrorCodesTest {

    @Test
    void everyFailureKindHasItsExactWireCode() {
        Map<FailureKind, String> expected = new EnumMap<>(FailureKind.class);
        expected.put(FailureKind.USAGE, "USAGE_ERROR");
        expected.put(FailureKind.LOCAL_CONFIG, "LOCAL_CONFIG_ERROR");
        expected.put(FailureKind.AUTHENTICATION, "AUTHENTICATION_FAILED");
        expected.put(FailureKind.RATE_LIMITED, "RATE_LIMITED");
        expected.put(FailureKind.TRANSPORT, "TRANSPORT_ERROR");
        expected.put(FailureKind.UPSTREAM, "UPSTREAM_ERROR");
        expected.put(FailureKind.MALFORMED, "MALFORMED_RESPONSE");
        expected.put(FailureKind.INTERNAL, "INTERNAL_ERROR");
        for (FailureKind kind : FailureKind.values()) {
            assertEquals(expected.get(kind), ErrorCodes.codeFor(kind), kind::name);
        }
        assertEquals(FailureKind.values().length, expected.size());
    }
}
