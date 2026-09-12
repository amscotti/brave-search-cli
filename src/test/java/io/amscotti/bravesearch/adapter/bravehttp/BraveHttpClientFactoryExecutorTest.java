package io.amscotti.bravesearch.adapter.bravehttp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The executor-aware factory wiring of the library composition: the shared client may ride a
 * caller-chosen executor, and the wiring keeps the same redirect-refusing shape as the
 * executor-less factory while rejecting null collaborators the same way.
 */
final class BraveHttpClientFactoryExecutorTest {

    @Test
    void aFactoryWithAnExecutorProducesAClient() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            BraveHttpClientFactory factory = new BraveHttpClientFactory(Duration.ofSeconds(2), executor);

            assertNotNull(factory.newClient(), "an executor-wired factory still builds a usable client");
        }
    }

    @Test
    void nullCollaboratorsAreRejected() {
        assertThrows(NullPointerException.class, () -> new BraveHttpClientFactory(Duration.ofSeconds(2), null));
        assertThrows(
                NullPointerException.class, () -> new BraveHttpClientFactory(null, Executors.newSingleThreadExecutor()));
    }
}
