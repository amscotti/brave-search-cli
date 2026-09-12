package io.amscotti.bravesearch.adapter.cli.presentation.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigShowView;
import io.amscotti.bravesearch.testsupport.SchemaCatalog;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact contract of the one machine record {@code config show --output json} writes:
 * a single compact, LF-terminated, schema-versioned object naming the winning source, the
 * resolved config path, whether an effective credential exists, and which lower-precedence
 * sources are shadowed — presence and provenance only, never credential material.
 */
final class ConfigShowRecordCodecTest {

    private static final Path CONFIG_PATH = Path.of("/tmp/hermetic/brave-search/config.json");

    private final SchemaCatalog schemas = new SchemaCatalog();

    @Test
    void anEnvironmentWinnerCarriesBothShadowedSources() {
        byte[] encoded = sample().encode(new ConfigShowView(
                "environment BRAVE_API_KEY", CONFIG_PATH, ConfigFileSight.KEY_STORED, true, true));

        assertArrayEquals(
                ("{\"schema_version\":\"1\",\"command\":\"config.show\","
                        + "\"source\":\"environment BRAVE_API_KEY\","
                        + "\"config_path\":\"/tmp/hermetic/brave-search/config.json\","
                        + "\"api_key_present\":true,"
                        + "\"shadowed\":[\"BRAVE_SEARCH_API_KEY\",\"config file\"]}\n")
                        .getBytes(UTF_8),
                encoded);
        assertTrue(
                schemas.validate("config-show.schema.json", encoded).isEmpty(),
                "the record must satisfy config-show.schema.json");
    }

    @Test
    void aMissingCredentialStatesAbsenceWithNoShadowedSources() {
        byte[] encoded =
                sample().encode(ConfigShowView.missing(CONFIG_PATH, ConfigFileSight.ABSENT));

        assertArrayEquals(
                ("{\"schema_version\":\"1\",\"command\":\"config.show\","
                        + "\"source\":\"missing\","
                        + "\"config_path\":\"/tmp/hermetic/brave-search/config.json\","
                        + "\"api_key_present\":false,"
                        + "\"shadowed\":[]}\n")
                        .getBytes(UTF_8),
                encoded);
        assertTrue(
                schemas.validate("config-show.schema.json", encoded).isEmpty(),
                "the missing-credential record satisfies the published schema");
    }

    @Test
    void theRecordNeverCarriesCredentialMaterial() {
        String sentinel = "BSK-sentinel-token-material";

        byte[] encoded = sample()
                .encode(new ConfigShowView("config file", CONFIG_PATH, ConfigFileSight.KEY_STORED, false, false));

        assertTrue(
                !new String(encoded, UTF_8).contains(sentinel),
                "the record is derived from the redaction-safe view and cannot carry token material");
        assertTrue(
                new String(encoded, UTF_8).indexOf('\n') == new String(encoded, UTF_8).length() - 1,
                "the record is exactly one LF-terminated line");
    }

    private static ConfigShowRecordCodec sample() {
        return new ConfigShowRecordCodec(new JsonMappers());
    }
}
