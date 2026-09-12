package io.amscotti.bravesearch.domain.config;

import java.nio.file.Path;

/**
 * The identity of the credential file as the config layer sees it: the schema version it was
 * read with ({@code 0} when the file is absent), the resolved path it lives at, and whether it
 * exists at all.
 *
 * @param schemaVersion version declared by the file, {@code 0} when absent
 * @param sourcePath the platform-resolved path of the file
 * @param present whether the file exists
 */
public record ConfigFileSummary(int schemaVersion, Path sourcePath, boolean present) {}
