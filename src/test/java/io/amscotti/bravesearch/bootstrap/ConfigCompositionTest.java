package io.amscotti.bravesearch.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.amscotti.bravesearch.adapter.config.ConfigPaths;
import io.amscotti.bravesearch.adapter.config.SecureConfigStore;
import io.amscotti.bravesearch.domain.config.ConfigState;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config command group's process wiring stays lazy: building the composition resolves the
 * credential-file path zero times, so a misconfigured environment surfaces as the typed
 * configuration failure at the first use — never as a composition crash — and the path resolves
 * at most once no matter how many parts ask for it.
 */
final class ConfigCompositionTest {

    @TempDir
    Path home;

    @Test
    void wiringResolvesTheCredentialPathOnlyOnFirstUse() {
        AtomicInteger resolutions = new AtomicInteger();
        ConfigComposition composed = ConfigComposition.wiring(
                new ConfigPaths(
                        () -> {
                            resolutions.incrementAndGet();
                            return "Linux";
                        },
                        home::toString,
                        name -> null),
                name -> null,
                () -> Optional.empty(),
                InputStream.nullInputStream(),
                SecureConfigStore.AclProbe.disabled());

        assertEquals(0, resolutions.get(), "constructing the wiring must not resolve the credential path");

        ConfigState state = composed.store().load();

        assertEquals(1, resolutions.get(), "the first store use resolves the credential path");
        assertFalse(state.summary().present(), "the fabricated home holds no credential file");
        composed.configPath().get();
        assertEquals(1, resolutions.get(), "the memoized supplier resolves the path at most once");
    }
}
