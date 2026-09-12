package io.amscotti.bravesearch.application.port.out;

import java.nio.file.Path;

/**
 * The outbound capability of reading one goggle file: local input handled by the config
 * adapter, reached by the command line through this port so the adapters never depend on
 * each other.
 *
 * <p>Implementations load only existing regular UTF-8 files through the documented 2 MiB
 * read bound, reject every other filesystem shape as a typed usage failure naming the
 * path, and never surface file content in any failure — a successful load returns the
 * verbatim definition text plus its byte length.
 */
public interface GoggleFileSource {

    /**
     * Loads the goggle definition of one local file.
     *
     * @throws io.amscotti.bravesearch.domain.error.UsageValidationError when the path is
     *     not an existing regular file, exceeds the read bound, or does not decode as
     *     UTF-8; the message names the path and byte facts only
     */
    LoadedGoggle load(Path path);

    /** One loaded goggle definition: its verbatim text and its byte length. */
    record LoadedGoggle(String definition, int byteLength) {}

    /**
     * One successful load as a diagnostics channel may report it: the source path and the
     * byte length read, and never the content — the content-free report shape of a
     * file-derived goggle.
     */
    record LoadedFile(Path path, int byteLength) {}
}
