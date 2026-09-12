package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Credential precedence: canonical variable over alias over file, fail-closed on any present but
 * invalid source, a typed missing failure, and provenance that can be rendered without ever
 * carrying the token.
 */
final class CredentialResolverTest {

    private static final String CANONICAL_SOURCE = "environment BRAVE_API_KEY";

    private static final String ALIAS_SOURCE = "environment BRAVE_SEARCH_API_KEY";

    private static final String FILE_SOURCE = "config file";

    @Test
    void canonicalVariableOutranksAliasAndFile() throws Exception {
        ResolvedCredential resolution = resolver(
                        Map.of("BRAVE_API_KEY", "canonical-value", "BRAVE_SEARCH_API_KEY", "alias-value"),
                        FileView.of("file-value"))
                .resolveWithProvenance();
        assertEquals(credential("canonical-value"), resolution.credential());
        assertEquals(CANONICAL_SOURCE, resolution.sourceName());
    }

    @Test
    void aliasVariableIsUsedWhenCanonicalIsAbsent() throws Exception {
        ResolvedCredential resolution =
                resolver(Map.of("BRAVE_SEARCH_API_KEY", "alias-value"), FileView.of("file-value"))
                        .resolveWithProvenance();
        assertEquals(credential("alias-value"), resolution.credential());
        assertEquals(ALIAS_SOURCE, resolution.sourceName());
    }

    @Test
    void bothVariablesSetReportTheAliasAsShadowed() throws Exception {
        ResolvedCredential resolution =
                resolver(Map.of("BRAVE_API_KEY", "canonical-value", "BRAVE_SEARCH_API_KEY", "alias-value"), FileView.empty())
                        .resolveWithProvenance();
        assertTrue(resolution.aliasShadowed());
    }

    @Test
    void aliasAloneIsNotReportedAsShadowed() throws Exception {
        ResolvedCredential resolution =
                resolver(Map.of("BRAVE_SEARCH_API_KEY", "alias-value"), FileView.empty()).resolveWithProvenance();
        assertFalse(resolution.aliasShadowed());
    }

    @Test
    void fileCredentialIsUsedWhenNoVariableIsSet() throws Exception {
        ResolvedCredential resolution = resolver(Map.of(), FileView.of("file-value"))
                .resolveWithProvenance();
        assertEquals(credential("file-value"), resolution.credential());
        assertEquals(FILE_SOURCE, resolution.sourceName());
    }

    @Test
    void missingEverywhereFailsWithTheTypedMissingError() {
        CredentialResolutionException failure = assertThrows(
                CredentialResolutionException.class,
                () -> resolver(Map.of(), FileView.empty()).resolveWithProvenance());
        assertEquals(CredentialResolutionException.Category.MISSING, failure.category());
        assertEquals(Optional.empty(), failure.sourceName());
        assertTrue(failure.getMessage().contains("missing"), () -> failure.getMessage());
    }

    @Test
    void invalidCanonicalVariableFailsClosedWithoutFallingBack() {
        String sentinel = sentinel();
        FileView file = FileView.of("file-value");
        CredentialResolutionException failure = assertThrows(
                CredentialResolutionException.class,
                () -> resolver(
                                Map.of(
                                        "BRAVE_API_KEY",
                                        " " + sentinel + "\u0007",
                                        "BRAVE_SEARCH_API_KEY",
                                        "alias-value"),
                                file)
                        .resolveWithProvenance());
        assertEquals(CredentialResolutionException.Category.INVALID, failure.category());
        assertEquals(Optional.of(CANONICAL_SOURCE), failure.sourceName());
        assertTrue(failure.getMessage().contains(CANONICAL_SOURCE), () -> failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel), "the failure must not carry token material");
        assertEquals(0, file.loads(), "an invalid higher source must not fall back to the file");
    }

    @Test
    void emptyCanonicalVariableFailsClosed() {
        CredentialResolutionException failure = assertThrows(
                CredentialResolutionException.class,
                () -> resolver(Map.of("BRAVE_API_KEY", ""), FileView.of("file-value")).resolveWithProvenance());
        assertEquals(CredentialResolutionException.Category.INVALID, failure.category());
        assertEquals(Optional.of(CANONICAL_SOURCE), failure.sourceName());
    }

    @Test
    void invalidAliasVariableFailsClosedWithoutFallingBackToFile() {
        String sentinel = sentinel();
        FileView file = FileView.of("file-value");
        CredentialResolutionException failure = assertThrows(
                CredentialResolutionException.class,
                () -> resolver(Map.of("BRAVE_SEARCH_API_KEY", "\n" + sentinel + "\n"), file)
                        .resolveWithProvenance());
        assertEquals(CredentialResolutionException.Category.INVALID, failure.category());
        assertEquals(Optional.of(ALIAS_SOURCE), failure.sourceName());
        assertFalse(failure.getMessage().contains(sentinel));
        assertEquals(0, file.loads());
    }

    @Test
    void invalidFileCredentialFailsClosed() {
        String sentinel = sentinel();
        FileView file = FileView.rejected("whitespace-only");
        CredentialResolutionException failure = assertThrows(
                CredentialResolutionException.class,
                () -> resolver(Map.of(), file).resolveWithProvenance());
        assertEquals(CredentialResolutionException.Category.INVALID, failure.category());
        assertEquals(Optional.of(FILE_SOURCE), failure.sourceName());
        assertTrue(failure.getMessage().contains("whitespace-only"), () -> failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel));
    }

    @Test
    void multibyteEnvironmentValueIsPreservedByteForByte() throws Exception {
        String token = "ключ-キー-\uD83E\uDDEA-key";
        ResolvedCredential resolution =
                resolver(Map.of("BRAVE_API_KEY", token), FileView.empty()).resolveWithProvenance();
        assertEquals(credential(token), resolution.credential());
        assertEquals(token, new String(resolution.credential().tokenBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void provenanceRenderingCarriesNoTokenMaterial() throws Exception {
        String sentinel = sentinel();
        ResolvedCredential resolution =
                resolver(Map.of("BRAVE_API_KEY", sentinel, "BRAVE_SEARCH_API_KEY", "alias-value"), FileView.of("file-value"))
                        .resolveWithProvenance();
        assertEquals("Credential[redacted]", resolution.credential().toString());
        assertFalse(resolution.toString().contains(sentinel), "the resolution record leaks token material");
        assertFalse(resolution.sourceName().contains(sentinel));
    }

    /**
     * A unique random-looking sentinel per invocation: it is embedded in token material under
     * test, so any appearance in a message or rendering is a leak of the token itself.
     */
    private static String sentinel() {
        return "BSK" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36)
                + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private static CredentialResolver resolver(Map<String, String> variables, FileView file) {
        return new CredentialResolver(new EnvironmentCredentialSource(variables::get), file);
    }

    private static Credential credential(String token) {
        return Credential.of(token.getBytes(StandardCharsets.UTF_8));
    }

    /** A config-file view that counts how often resolution consulted it. */
    private static final class FileView implements Supplier<Optional<Credential>> {

        private final Optional<Credential> credential;

        private final String rejectionReason;

        private final AtomicInteger loads = new AtomicInteger();

        static FileView of(String token) {
            return new FileView(Optional.of(credential(token)), null);
        }

        static FileView empty() {
            return new FileView(Optional.empty(), null);
        }

        static FileView rejected(String reason) {
            return new FileView(Optional.empty(), reason);
        }

        private FileView(Optional<Credential> credential, String rejectionReason) {
            this.credential = credential;
            this.rejectionReason = rejectionReason;
        }

        @Override
        public Optional<Credential> get() {
            loads.incrementAndGet();
            if (rejectionReason != null) {
                throw new InvalidCredentialException(rejectionReason);
            }
            return credential;
        }

        int loads() {
            return loads.get();
        }
    }
}
