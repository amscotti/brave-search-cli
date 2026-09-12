package io.amscotti.bravesearch.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Durability gate of {@code docs/contract-index.yaml}: every stable requirement identifier is
 * unique and structurally valid, every referenced test exists as an executable JUnit method in
 * the compiled test classes, and every referenced documentation anchor exists as a heading in
 * the contract document it names.
 *
 * <p>Verification approach, kept deliberately simple and dependency-free:
 *
 * <ul>
 *   <li>the index is parsed with a strict parser for the exact YAML subset the file uses
 *       (comment lines, blank lines, {@code IDENTIFIER:} headers, and exactly two indented
 *       {@code doc:}/{@code test:} properties per entry), so malformed structure fails loudly
 *       instead of being silently skipped;
 *   <li>compiled classes are enumerated from the test class path by walking every directory
 *       resource under the root package ({@code ClassLoader.getResources}), and each referenced
 *       {@code Class#method} is confirmed by reflection to exist and to carry JUnit Jupiter's
 *       {@code @Test}, proving the name is real and executable rather than aspirational;
 *   <li>documentation anchors are matched against GitHub-style slugs of the markdown headings —
 *       headings inside fenced code blocks mint no anchor, and repeated headings earn the
 *       {@code -N} suffix GitHub appends — so a renamed section breaks the index visibly.
 */
final class ContractIndexTest {

    private record Entry(String id, String doc, String test) {}

    private static final Path INDEX = Paths.get("docs", "contract-index.yaml");

    /** The contract documents an entry's {@code doc} property may point into. */
    private static final Map<String, Path> CONTRACT_DOCUMENTS =
            Map.of(
                    "docs/cli-contract.md", Paths.get("docs", "cli-contract.md"),
                    "docs/config-security.md", Paths.get("docs", "config-security.md"),
                    "docs/brave-api-contract.md", Paths.get("docs", "brave-api-contract.md"),
                    "docs/release.md", Paths.get("docs", "release.md"));

    /** The requirement identifiers the contract index must always cover. */
    private static final Set<String> CORE_REQUIREMENTS =
            Set.of(
                    "EXIT-CODE-TABLE",
                    "EXIT-PRECEDENCE",
                    "HTTP-STATUS-CLASSIFICATION",
                    "OUTPUT-MODE-GRAMMAR",
                    "CLI-GLOBAL-PLACEMENT",
                    "CLI-GLOBAL-VERBOSE-QUIET",
                    "CLI-STRICT-PARSING",
                    "CLI-DURATION-GRAMMAR",
                    "CLI-TIMEOUT-DEFAULTS",
                    "CLI-API-VERSION-PIN",
                    "MODE-COMPAT-PRETTY",
                    "MODE-COMPAT-LOCAL",
                    "MODE-COMPAT-RAW-SINGLE",
                    "PARSE-BEFORE-RENDER",
                    "WARNINGS-ROUTING",
                    "ENVELOPE-SUCCESS",
                    "ENVELOPE-ERROR",
                    "JSONL-FRAMING",
                    "RAW-BYTES",
                    "CONFIG-PRECEDENCE",
                    "CONFIG-PATHS",
                    "CONFIG-SECURE-WRITE",
                    "CONFIG-REFUSALS",
                    "CONFIG-SHOW-REDACTION",
                    "CONFIG-SHOW-JSON",
                    "CONFIG-STDIN-CONTRACT",
                    "CONFIG-EXIT-3",
                    "API-BASE-PROTOCOL",
                    "API-ORIGIN-LOOPBACK",
                    "API-REQUEST-ENCODING",
                    "API-RESPONSE-BOUNDS",
                    "API-CONTENT-TYPE",
                    "API-GZIP",
                    "API-ERROR-CLASSIFICATION",
                    "API-SENTINEL-FREE-DIAGNOSTICS",
                    "META-RATE-WINDOWS",
                    "META-RATE-MISMATCH-NONFATAL",
                    "META-RESET-DURATION",
                    "META-ANSWERS-USAGE",
                    "META-UNKNOWN-PRESERVED",
                    "SSE-LINE-ENDINGS",
                    "SSE-DATA-JOINING",
                    "SSE-BOUNDS",
                    "SSE-UTF8-SPLIT",
                    "SSE-EOF-DONE",
                    "TAG-DECODER-SPLIT",
                    "TAG-UNKNOWN-PRESERVED",
                    "TAG-TEXT-EXACT",
                    "WEB-WIRE",
                    "WEB-OPTIONS",
                    "WEB-HUMAN-OUTPUT",
                    "WEB-JSON-ENVELOPE",
                    "WEB-JSONL-RECORDS",
                    "WEB-RAW-BYTES",
                    "WEB-AUTH-EXIT-4",
                    "WEB-USAGE-PRE-DISPATCH",
                    "WEB-LOOPBACK-TESTKEY",
                    "PAG-CONTINUATION",
                    "PAG-DEDUP",
                    "PAG-PARTIAL-FAILURE",
                    "PAG-RATE-PACING",
                    "PAG-RAW-REJECTED",
                    "PAG-UPSTREAM-ARRAY",
                    "WALK-EPIPE-SILENT-ZERO",
                    "GOG-STRATEGY-EXCLUSIVE",
                    "GOG-MAX-THREE",
                    "GOG-SITE-INJECTION-SAFE",
                    "GOG-FILE-BOUNDS",
                    "GOG-CONTENT-REDACTED",
                    "GOG-GET-POST-EQUIVALENCE",
                    "NEWS-WIRE",
                    "NEWS-BOUNDS",
                    "NEWS-PAGE-TERMINATION",
                    "NEWS-DEDUP",
                    "NEWS-PARTIAL-FAILURE",
                    "NEWS-OUTPUT",
                    "VID-WIRE",
                    "VID-BOUNDS",
                    "VID-PAGE-TERMINATION",
                    "VID-NO-GOGGLES",
                    "VID-OUTPUT",
                    "IMG-WIRE",
                    "IMG-COUNT-200",
                    "IMG-SAFESEARCH-OFF-STRICT",
                    "IMG-NO-PAGINATION",
                    "IMG-OUTPUT",
                    "SUG-WIRE",
                    "SUG-LANG-MAPPING",
                    "SUG-COUNT",
                    "SUG-RICH",
                    "SUG-OUTPUT",
                    "SPL-WIRE",
                    "SPL-LANG-MAPPING",
                    "SPL-OUTPUT",
                    "PLACE-WIRE",
                    "PLACE-ANCHOR-RULES",
                    "PLACE-GEOLOC",
                    "PLACE-DECIMAL-BOUNDS",
                    "PLACE-COUNT-BUDGET",
                    "PLACE-HEADER-SAFETY",
                    "PLACE-BUCKETS",
                    "PLACE-OUTPUT",
                    "PLACE-ENRICHMENT-WIRE",
                    "PLACE-DETAILS-CHUNKING",
                    "PLACE-DETAILS-PACING",
                    "PLACE-DETAILS-ORDER-RECONSTRUCTION",
                    "PLACE-DETAILS-PARTIAL-FAILURE",
                    "PLACE-DETAILS-CAP-200",
                    "PLACE-DETAILS-RAW-REJECTED",
                    "PLACE-DETAILS-OUTPUT",
                    "PLACE-DESCRIBE-CHUNKING",
                    "PLACE-DESCRIBE-ORDER-RECONSTRUCTION",
                    "PLACE-DESCRIBE-PARTIAL-FAILURE",
                    "PLACE-DESCRIBE-CAP-200",
                    "PLACE-DESCRIBE-RAW-REJECTED",
                    "PLACE-DESCRIBE-OUTPUT",
                    "CTX-WIRE",
                    "CTX-BOUNDS",
                    "CTX-THRESHOLD",
                    "CTX-LOCATION-NO-TIMEZONE",
                    "CTX-OUTPUT",
                    "CTX-SINGLE-REQUEST",
                    "RICH-WIRE-CALLBACK-KEY",
                    "RICH-LOSSLESS",
                    "RICH-HUMAN-VERTICALS",
                    "RICH-ATTRIBUTION",
                    "RICH-OUTPUT",
                    "ANS-BODY-NESTED",
                    "ANS-ONE-MESSAGE",
                    "ANS-RESEARCH-REQUIRES-STREAM",
                    "ANS-NO-STREAM-JSONL-INVALID",
                    "ANS-BLOCKING-OUTPUT",
                    "ANS-USAGE-METADATA",
                    "ANS-STREAM-MODES",
                    "ANS-STREAM-JSONL-EVENTS",
                    "ANS-STREAM-JSON-BUFFERED-ADVISORY",
                    "ANS-STREAM-RAW-SSE-BYTES",
                    "ANS-STREAM-SIGINT-130",
                    "ANS-STREAM-SIGTERM-143",
                    "ANS-STREAM-EPIPE-0",
                    "ANS-STREAM-IDLE-WALL",
                    "ANS-STREAM-ABRUPT-EOF-COST-UNKNOWN",
                    "ANS-STREAM-INCREMENTAL",
                    "EXIT-INTERNAL-CATCHALL",
                    "EXIT-INTERNAL-COMPOSITION",
                    "EXIT-LOCAL-CONFIG-ESCAPE",
                    "VERSION-OUTPUT",
                    "COMPLETION-SCRIPTS",
                    "COMPLETION-SHELL-VALIDATION",
                    "ANSI-ENABLE-CONSERVATIVE",
                    "ANSI-PTY-POSITIVE",
                    "HUMAN-LAYOUT",
                    "HUMAN-WIDTH",
                    "HUMAN-STYLES",
                    "QUOTA-FOOTER",
                    "HELP-SNAPSHOTS",
                    "RELEASE-ARCHIVE-LAYOUT",
                    "RELEASE-CHECKSUMS",
                    "RELEASE-SBOM-RUNTIME-ONLY",
                    "RELEASE-LOCAL-VERIFICATION",
                    "CI-MATRIX-AUTHORED",
                    "RELEASE-TAMPER-DRILL",
                    "LIVE-WEB-PROTOCOL",
                    "LIVE-CONTEXT-PROTOCOL",
                    "LIVE-PLACES-PROTOCOL",
                    "LIVE-ANSWERS-WIRE-FORM",
                    "LIVE-API-VERSION-OBSERVATION");

    private static final Pattern ENTRY_HEADER = Pattern.compile("^([A-Z][A-Z0-9-]*):$");

    private static final Pattern ENTRY_PROPERTY = Pattern.compile("^ {2}(doc|test): (\\S.*)$");

    @Test
    void everyEntryIsStructurallyValidAndUniquelyIdentified() {
        List<String> duplicates = new ArrayList<>();
        Map<String, Entry> index = parseIndex(duplicates);
        assertFalse(index.isEmpty(), "the contract index must contain at least one requirement");
        assertTrue(duplicates.isEmpty(), () -> "duplicate requirement identifiers: " + duplicates);
        for (Entry entry : index.values()) {
            assertTrue(
                    contractDocumentsOf(entry).isPresent(),
                    () -> entry.id() + " must reference a section anchor in a known contract document: " + entry.doc());
            int separator = entry.test().indexOf('#');
            assertTrue(
                    separator > 0 && separator < entry.test().length() - 1,
                    () -> entry.id() + " must reference Class#method: " + entry.test());
        }
    }

    @Test
    void indexCoversTheCoreContractRequirements() {
        Map<String, Entry> index = parseIndex(new ArrayList<>());
        assertEquals(
                CORE_REQUIREMENTS,
                index.keySet(),
                "the contract index must cover exactly the core stable requirements");
    }

    @Test
    void everyReferencedTestExistsAndIsExecutable() throws Exception {
        Set<String> compiled = compiledClassNames();
        assertFalse(compiled.isEmpty(), "class-path enumeration found no classes; the verification itself is broken");
        for (Entry entry : parseIndex(new ArrayList<>()).values()) {
            String reference = entry.test();
            String className = reference.substring(0, reference.indexOf('#'));
            String methodName = reference.substring(reference.indexOf('#') + 1);
            assertTrue(
                    compiled.contains(className),
                    () -> entry.id() + " references a class missing from the compiled test classes: " + className);
            Class<?> testClass =
                    Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            List<Method> matches = Arrays.stream(testClass.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .toList();
            assertEquals(1, matches.size(), () -> entry.id() + " references a missing or ambiguous method: " + reference);
            assertNotNull(matches.get(0), entry.id());
            assertTrue(
                    matches.get(0).isAnnotationPresent(Test.class),
                    () -> entry.id() + " references a method that is not a test: " + reference);
        }
    }

    @Test
    void headingSlugsKeepUnicodeWordCharactersAndUnderscores() {
        Set<String> slugs = headingSlugs(
                """
                # Näive_header keeps word characters
                ## Näive_header keeps word characters
                ### JSON (buffered)
                """);
        assertTrue(slugs.contains("näive_header-keeps-word-characters"));
        assertTrue(slugs.contains("näive_header-keeps-word-characters-1"));
        assertTrue(slugs.contains("json-buffered"));
        assertFalse(
                slugs.contains("nive_header-keeps-word-characters"),
                "an ASCII-only filter would drop the accents and mint an anchor GitHub never renders");
    }

    @Test
    void everyReferencedDocAnchorExists() throws IOException {
        Map<String, Set<String>> anchorsByDocument = new LinkedHashMap<>();
        for (Entry entry : parseIndex(new ArrayList<>()).values()) {
            String document = contractDocumentsOf(entry)
                    .orElseThrow(() -> new IllegalStateException(entry.id() + " references an unknown document: " + entry.doc()));
            String anchor = entry.doc().substring(document.length() + 1);
            Set<String> anchors = anchorsByDocument.computeIfAbsent(document, ContractIndexTest::headingSlugsOf);
            assertTrue(
                    anchors.contains(anchor),
                    () -> entry.id() + " references a section that does not exist in " + document + ": #" + anchor);
        }
    }

    /** The known contract document an entry points into, with the anchor separator present. */
    private static Optional<String> contractDocumentsOf(Entry entry) {
        int separator = entry.doc().lastIndexOf('#');
        if (separator <= 0 || !CONTRACT_DOCUMENTS.containsKey(entry.doc().substring(0, separator))) {
            return Optional.empty();
        }
        return Optional.of(entry.doc().substring(0, separator));
    }

    private static Set<String> headingSlugsOf(String document) {
        try {
            return headingSlugs(Files.readString(CONTRACT_DOCUMENTS.get(document), StandardCharsets.UTF_8));
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    private static Map<String, Entry> parseIndex(List<String> duplicates) {
        List<String> lines;
        try {
            lines = Files.readAllLines(INDEX, StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("cannot read " + INDEX + ": " + missing.getMessage(), missing);
        }
        Map<String, Entry> index = new LinkedHashMap<>();
        String id = null;
        String doc = null;
        String test = null;
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher header = ENTRY_HEADER.matcher(trimmed);
            if (header.matches()) {
                if (id != null) {
                    flush(index, duplicates, id, doc, test, lineNumber - 1);
                }
                id = header.group(1);
                doc = null;
                test = null;
                continue;
            }
            Matcher property = ENTRY_PROPERTY.matcher(line);
            if (property.matches() && id != null) {
                boolean isDoc = "doc".equals(property.group(1));
                if ((isDoc ? doc : test) != null) {
                    throw new IllegalStateException(
                            INDEX + ":" + lineNumber + " entry " + id + " repeats its " + property.group(1) + " property");
                }
                if (isDoc) {
                    doc = property.group(2);
                } else {
                    test = property.group(2);
                }
                continue;
            }
            throw new IllegalStateException(
                    INDEX + ":" + lineNumber + " does not follow the contract-index structure: " + line);
        }
        if (id != null) {
            flush(index, duplicates, id, doc, test, lines.size());
        }
        return index;
    }

    private static void flush(
            Map<String, Entry> index, List<String> duplicates, String id, String doc, String test, int lineNumber) {
        if (doc == null || test == null) {
            throw new IllegalStateException(
                    INDEX + ":" + lineNumber + " entry " + id + " lacks its doc or test property");
        }
        if (index.containsKey(id)) {
            duplicates.add(id);
        }
        index.put(id, new Entry(id, doc, test));
    }

    private static Set<String> compiledClassNames() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Set<String> names = new HashSet<>();
        Enumeration<URL> roots = loader.getResources("io/amscotti/bravesearch");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (!"file".equalsIgnoreCase(root.getProtocol())) {
                continue;
            }
            try {
                Path directory = Path.of(root.toURI());
                try (Stream<Path> files = Files.walk(directory)) {
                    files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                        // Path.toString uses the platform separator, so normalize Windows
                        // backslashes before the slash-to-dot conversion keeps binary names right.
                        String relative = directory.relativize(file).toString().replace('\\', '/');
                        String binary = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                        names.add("io.amscotti.bravesearch." + binary);
                    });
                }
            } catch (Exception unreadableRoot) {
                throw new IllegalStateException("cannot enumerate compiled classes under " + root, unreadableRoot);
            }
        }
        return names;
    }

    private static Set<String> headingSlugs(String markdown) {
        Set<String> slugs = new HashSet<>();
        Map<String, Integer> occurrences = new HashMap<>();
        boolean insideFence = false;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (trimmed.stripLeading().startsWith("```")) {
                insideFence = !insideFence;
                continue;
            }
            if (insideFence || !trimmed.startsWith("#")) {
                continue;
            }
            String heading = trimmed.replaceFirst("^#+\\s+", "");
            if (heading.isBlank()) {
                continue;
            }
            String slug = heading
                    .toLowerCase(Locale.ROOT)
                    // github-slugger drops punctuation and symbols but keeps word
                    // characters — unicode letters, digits, and underscores — so the
                    // index can never pin an anchor GitHub would render differently
                    .replaceAll("[^\\p{L}\\p{N}_ -]", "")
                    .strip()
                    .replace(' ', '-');
            int occurrence = occurrences.merge(slug, 1, Integer::sum) - 1;
            slugs.add(occurrence == 0 ? slug : slug + "-" + occurrence);
        }
        return slugs;
    }
}
