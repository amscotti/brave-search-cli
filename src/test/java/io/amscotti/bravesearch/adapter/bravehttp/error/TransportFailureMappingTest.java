package io.amscotti.bravesearch.adapter.bravehttp.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.amscotti.bravesearch.application.exchange.BraveHttpResponse;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

/**
 * The transport failure envelope: every transport-level exception class maps to the TRANSPORT
 * kind with a diagnostic built only from the failure category and the exception's class name.
 * Exception messages are outside this code base's control and some embed the full request URI
 * or header material, so none of the message text may survive into the diagnostic.
 */
final class TransportFailureMappingTest {

    @Test
    void everyTransportLevelFailureMapsToARedactedTransportDiagnostic() {
        String token = "sentinel-" + UUID.randomUUID();
        String queryText = "marker-" + UUID.randomUUID();
        List<IOException> causes =
                List.of(
                        new ConnectException("Connection refused to https://host/res?q=" + queryText + " token=" + token),
                        new UnknownHostException("unknown host carrying " + token),
                        new SSLException("handshake failed with " + token),
                        new HttpTimeoutException("timed out waiting for headers of " + queryText),
                        new IOException("plain transport break " + token));

        for (IOException cause : causes) {
            Outcome.Failure<BraveHttpResponse> failure = TransportFailureMapping.of(cause);

            assertEquals(FailureKind.TRANSPORT, failure.kind(), () -> "wrong kind for " + cause.getClass().getSimpleName());
            assertEquals(
                    "upstream transport failure: " + cause.getClass().getSimpleName(),
                    failure.diagnostic(),
                    () -> "the diagnostic must carry only the category and the exception class name");
            assertFalse(failure.diagnostic().contains(token), "the diagnostic must never carry token material");
            assertFalse(failure.diagnostic().contains(queryText), "the diagnostic must never carry query value text");
        }
    }
}
