package io.amscotti.bravesearch.adapter.config;

import io.amscotti.bravesearch.application.port.out.GoggleFileSource;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads goggle files from the local filesystem: only existing regular UTF-8 files, read
 * through the {@link #MAX_BYTES} bound, with every other filesystem shape — a FIFO, a
 * device, a directory, a missing path — refused as a typed usage failure before a single
 * byte is read, so a special file can never block or stream unbounded. Symlinks that
 * resolve to regular files are followed, the input-file convenience: the loader's threat
 * model is bounded, verified input, not writable-output integrity.
 *
 * <p>Every refusal names the path and byte facts only. File content is user-owned
 * material and never rides an error message, so a goggle file planted with a secret can
 * never leak it through a failure.
 */
public final class GoggleFileLoader implements GoggleFileSource {

    /** The largest goggle file, in bytes, that one invocation will read. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    public LoadedGoggle load(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new UsageValidationError("goggle file must be an existing regular file: " + path);
        }
        byte[] bytes = readBounded(path);
        if (bytes.length > MAX_BYTES) {
            throw new UsageValidationError(
                    "goggle file " + path + " exceeds the " + (MAX_BYTES / (1024 * 1024)) + " MiB read bound at "
                            + sizeOf(path) + " bytes");
        }
        return new LoadedGoggle(decodeStrictUtf8(bytes, path), bytes.length);
    }

    /**
     * Reads at most one byte beyond the bound: enough to detect an oversized file while
     * never buffering more than the bound plus one chunk.
     */
    private static byte[] readBounded(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while (buffer.size() <= MAX_BYTES && (read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException unreadable) {
            throw new UsageValidationError("goggle file cannot be read: " + path);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException unknownSize) {
            return -1;
        }
    }

    /** Decodes strictly: a malformed or unmappable sequence refuses the whole file. */
    private static String decodeStrictUtf8(byte[] bytes, Path path) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new UsageValidationError("goggle file " + path + " is not valid UTF-8 (" + bytes.length + " bytes)");
        }
    }
}
