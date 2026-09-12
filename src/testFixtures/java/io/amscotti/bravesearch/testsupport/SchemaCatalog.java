package io.amscotti.bravesearch.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads the published schemas under {@code schemas/v1} and serves them by their {@code $id} so
 * relative references between them resolve offline, without any network fetch.
 *
 * <p>Shared by every suite that must prove an emitted machine document satisfies the published
 * contract: the JVM unit suites and the whole-process scenario suites, JVM launcher and native
 * binary alike.
 */
public final class SchemaCatalog {

    static final String BASE_ID = "https://brave-search-cli.invalid/schemas/v1/";

    private final JsonSchemaFactory factory;
    private final ObjectMapper documentReader = new ObjectMapper();

    public SchemaCatalog() {
        Map<String, String> schemasById = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("schemas", "v1"))) {
            files.forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".schema.json")) {
                    try {
                        schemasById.put(BASE_ID + name, Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (schemasById.isEmpty()) {
            throw new IllegalStateException("no *.schema.json files found under schemas/v1");
        }
        this.factory =
                JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                        .schemaLoaders(loaders -> loaders.schemas(schemasById::get))
                        .build();
    }

    public Set<ValidationMessage> validate(String schemaFile, byte[] document) {
        try {
            return factory
                    .getSchema(SchemaLocation.of(BASE_ID + schemaFile))
                    .validate(documentReader.readTree(document));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Set<ValidationMessage> validateText(String schemaFile, String document) {
        return validate(schemaFile, document.getBytes(StandardCharsets.UTF_8));
    }
}
