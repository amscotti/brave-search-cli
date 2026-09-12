package io.amscotti.bravesearch.domain.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The credential-file state carries its summary and stored credential without nulls. */
final class ConfigStateTest {

    @Test
    void rejectsNullParts() {
        ConfigFileSummary summary = new ConfigFileSummary(1, Path.of("config.json"), true);
        assertThrows(NullPointerException.class, () -> new ConfigState(null, summary));
        assertThrows(NullPointerException.class, () -> new ConfigState(Optional.empty(), null));
    }

    @Test
    void carriesTheStoredCredentialAndTheFileIdentity() {
        Credential credential = Credential.of("stored".getBytes(StandardCharsets.UTF_8));
        ConfigFileSummary summary = new ConfigFileSummary(1, Path.of("config.json"), true);
        ConfigState state = new ConfigState(Optional.of(credential), summary);
        assertEquals(Optional.of(credential), state.credential());
        assertEquals(1, state.summary().schemaVersion());
        assertEquals(Path.of("config.json"), state.summary().sourcePath());
        assertTrue(state.summary().present());
    }
}
