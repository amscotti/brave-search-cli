package io.amscotti.bravesearch.adapter.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.port.out.GoggleFileSource;
import io.amscotti.bravesearch.domain.error.FailureKind;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounded, sentinel-free loading of goggle files: only existing regular UTF-8 files load,
 * through the 2 MiB read bound; FIFOs, devices, directories, and missing paths are usage
 * rejections naming the path; a file that breaks a bound or the UTF-8 decoding is refused
 * with its path and byte length only — file content never rides any error, proven by a
 * unique sentinel planted in every failing file. Symlinks that resolve to regular files
 * are followed, the documented input-file convenience.
 */
final class GoggleFileLoaderTest {

    /** Content planted in every failing fixture; no error may ever repeat it. */
    private static final String SENTINEL = "GOOGLE-FILE-SENTINEL-9d4f1c7e";

    @TempDir
    Path temporary;

    @Test
    void aRegularUtf8FileLoadsVerbatimWithItsByteLength() throws IOException {
        Path file = temporary.resolve("rules.goggle");
        Files.write(file, ("! a benign definition\n" + SENTINEL + "\n+site:example.com").getBytes(UTF_8));

        GoggleFileSource.LoadedGoggle loaded = new GoggleFileLoader().load(file);

        assertEquals("! a benign definition\n" + SENTINEL + "\n+site:example.com", loaded.definition());
        assertEquals(Files.size(file), loaded.byteLength());
    }

    @Test
    void aSymlinkToARegularFileIsFollowed() throws IOException {
        Path target = temporary.resolve("target.goggle");
        Files.write(target, "+site:example.com".getBytes(UTF_8));
        Path link = temporary.resolve("link.goggle");
        Files.createSymbolicLink(link, target);

        assertEquals("+site:example.com", new GoggleFileLoader().load(link).definition());
    }

    @Test
    void aMissingPathIsAUsageRejectionNamingIt() {
        UsageValidationError rejected = assertThrows(
                UsageValidationError.class, () -> new GoggleFileLoader().load(temporary.resolve("absent.goggle")));

        assertEquals(FailureKind.USAGE, rejected.kind());
        assertTrue(rejected.getMessage().contains("absent.goggle"), rejected.getMessage());
    }

    @Test
    void aDirectoryIsAUsageRejectionNamingIt() {
        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> new GoggleFileLoader().load(temporary));

        assertTrue(rejected.getMessage().contains("regular file"), rejected.getMessage());
    }

    @Test
    void aCharacterDeviceIsAUsageRejection() {
        Path nullDevice = Path.of("/dev/null");
        Assumptions.assumeTrue(Files.exists(nullDevice), "the character device fixture is POSIX-only");

        assertThrows(UsageValidationError.class, () -> new GoggleFileLoader().load(nullDevice));
    }

    @Test
    void aFifoIsAUsageRejectionWithoutEverReadingIt() throws IOException {
        Path fifo = temporary.resolve("pipe.goggle");
        boolean created = false;
        try {
            Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
            created = mkfifo.waitFor() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Assumptions.assumeTrue(created, "mkfifo is unavailable, so the FIFO fixture cannot be built here");

        UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> new GoggleFileLoader().load(fifo));

        assertTrue(rejected.getMessage().contains("pipe.goggle"), rejected.getMessage());
    }

    @Test
    void aFileAtExactlyTheBoundLoads() throws IOException {
        Path file = temporary.resolve("full.goggle");
        byte[] payload = new byte[GoggleFileLoader.MAX_BYTES];
        Arrays.fill(payload, (byte) 'a');
        Files.write(file, payload);

        GoggleFileSource.LoadedGoggle loaded = new GoggleFileLoader().load(file);

        assertEquals(GoggleFileLoader.MAX_BYTES, loaded.byteLength());
        assertEquals(GoggleFileLoader.MAX_BYTES, loaded.definition().length());
    }

    @Test
    void aFileBeyondTheBoundIsRefusedWithPathAndSizeOnly() throws IOException {
        Path oversized = temporary.resolve("oversized.goggle");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            bytes.write(SENTINEL.getBytes(UTF_8));
            bytes.write('\n');
            byte[] filler = new byte[GoggleFileLoader.MAX_BYTES];
            Arrays.fill(filler, (byte) 'a');
            bytes.write(filler);
            Files.write(oversized, bytes.toByteArray());
        }

        UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> new GoggleFileLoader().load(oversized));

        assertEquals(FailureKind.USAGE, rejected.kind());
        assertTrue(rejected.getMessage().contains("oversized.goggle"), rejected.getMessage());
        assertTrue(
                rejected.getMessage().contains(String.valueOf(Files.size(oversized))),
                "the refusal reports the byte size: " + rejected.getMessage());
        assertFalse(rejected.getMessage().contains(SENTINEL), "content never rides the refusal");
    }

    @Test
    void aFileThatIsNotUtf8IsRefusedWithoutItsContent() throws IOException {
        Path binary = temporary.resolve("binary.goggle");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            bytes.write(SENTINEL.getBytes(UTF_8));
            bytes.write('\n');
            bytes.write(0xFF);
            bytes.write(0xFE);
            Files.write(binary, bytes.toByteArray());
        }

        UsageValidationError rejected = assertThrows(UsageValidationError.class, () -> new GoggleFileLoader().load(binary));

        assertTrue(rejected.getMessage().contains("binary.goggle"), rejected.getMessage());
        assertTrue(
                rejected.getMessage().contains(String.valueOf(Files.size(binary))),
                "the refusal reports the byte size: " + rejected.getMessage());
        assertFalse(rejected.getMessage().contains(SENTINEL), "content never rides the refusal");
    }
}
