package io.amscotti.bravesearch.hygiene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a repository tree for build-step terminology that must not leak into durable artifacts:
 * references to numbered work items, numbered definitions of done, numbered stage words,
 * planning-stage phrases, and mentions of the planning document file itself. Ordinary domain use
 * of words such as "phase" is allowed.
 *
 * <p>The scan starts at the given root and covers every textual file, with these exclusions:
 *
 * <ul>
 *   <li>tool-generated directories {@code .git}, {@code .gradle}, {@code build}, and {@code out},
 *       including the review harness's own scratch directory {@code .review}, whose state files
 *       quote hygiene failure reports and would otherwise trip the gate on their quotations;
 *       they are skipped below the scanned root, which itself is never excluded,
 *   <li>IDE and editor droppings ({@code .idea}, {@code .vscode}, {@code .settings}, {@code
 *       node_modules}, {@code .DS_Store}) as a simple approximation of ignore-file semantics,
 *   <li>secret files ({@code .env} and {@code .env.*}) are never opened, so secrets cannot leak
 *       into failure output; committed {@code *.example} templates remain in scope,
 *   <li>binary and generated files by name or extension ({@code *.jar}, images, archives, native
 *       libraries, fonts, keystores, {@code mise.lock}, {@code gradle.lockfile*} and {@code
 *       *.lockfile}, and {@code gradle/verification-metadata.xml} at the repository root),
 *   <li>any file whose leading bytes contain a NUL octet is treated as binary and skipped even
 *       without a known extension,
 *   <li>files larger than {@link #MAX_SCANNABLE_BYTES} are never opened; the cap is enforced
 *       against the entry's followed size, so a symbolic link is bounded by its target, and the
 *       scan report states how many were skipped.
 * </ul>
 *
 * <p>Files are decoded as UTF-8 with malformed input replaced, so odd-but-textual files still
 * scan. Violations report the file path relative to the scanned root, the 1-based line number,
 * the matched text, and the full offending line; every occurrence on a line is reported. A
 * numbered keyword must start a word of its own (it may not be glued to a preceding word, as in
 * "multitask 4" or "multi-step 3"), so ordinary compound words stay allowed. A file that cannot
 * be visited or read (for example by missing permissions) fails the scan with a message naming
 * the path and the reason; entries that are not regular files once links are followed (broken
 * symlinks, links to directories, FIFO and device entries) and entries that vanish mid-scan
 * carry no scannable content and are skipped.
 */
public final class HygieneScanner {

    /** One rejected occurrence: {@code file} is relative to the scanned root when possible. */
    public record Violation(Path file, int lineNumber, String matchedText, String lineText) {}

    /** Outcome of one scan: the violations found plus how many oversized files were skipped. */
    public record ScanReport(List<Violation> violations, int skippedOversizedFiles) {

        public ScanReport {
            violations = List.copyOf(violations);
        }
    }

    /** Rejects a numbered keyword only when it starts a word of its own, not inside a compound. */
    private static final String WORD_START = "(?<![A-Za-z0-9_-])";

    private static final List<Pattern> REJECT_PATTERNS =
            List.of(
                    Pattern.compile(WORD_START + "Task[ _-]?[0-9]+", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WORD_START + "DoD[ _-]?[0-9]+", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WORD_START + "Phase[ _-]?[0-9]+", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WORD_START + "Stage[ _-]?[0-9]+", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WORD_START + "Step[ _-]?[0-9]+", Pattern.CASE_INSENSITIVE),
                    // the phrase must end at a word boundary, so ordinary continuations such as
                    // "per the planning conventions" stay allowed
                    Pattern.compile(WORD_START + "per the (implementation )?plan(?![A-Za-z])", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(WORD_START + "PLAN\\.md", Pattern.CASE_INSENSITIVE));

    private static final Set<String> EXCLUDED_DIRECTORIES =
            Set.of(".git", ".gradle", "build", "out", ".idea", ".vscode", ".settings", "node_modules", ".review");

    private static final Set<String> BINARY_OR_GENERATED_EXTENSIONS =
            Set.of(
                    "jar",
                    "png",
                    "jpg",
                    "jpeg",
                    "gif",
                    "bmp",
                    "ico",
                    "icns",
                    "webp",
                    "pdf",
                    "zip",
                    "tar",
                    "gz",
                    "tgz",
                    "7z",
                    "bz2",
                    "xz",
                    "class",
                    "so",
                    "dylib",
                    "dll",
                    "exe",
                    "bin",
                    "jks",
                    "p12",
                    "keystore",
                    "woff",
                    "woff2",
                    "ttf",
                    "otf",
                    "eot",
                    "mp3",
                    "mp4",
                    "mov");

    /** A NUL octet anywhere in this leading byte window marks a file binary. */
    private static final int BINARY_SNIFF_LIMIT = 8192;

    /** Files strictly larger than this many bytes are never opened; they are counted as skipped. */
    static final int MAX_SCANNABLE_BYTES = 4 * 1024 * 1024;

    public ScanReport scan(Path root) throws IOException {
        ScanVisitor visitor = new ScanVisitor(root);
        Files.walkFileTree(root, visitor);
        visitor.violations.sort(Comparator.comparing(Violation::file).thenComparingInt(Violation::lineNumber));
        return new ScanReport(visitor.violations, visitor.skippedOversizedFiles);
    }

    /** Tree walker that collects violations and counts skipped oversized files. */
    static final class ScanVisitor extends SimpleFileVisitor<Path> {

        private final Path root;
        private final List<Violation> violations = new ArrayList<>();
        private int skippedOversizedFiles;

        ScanVisitor(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            // the scanned root itself is never excluded (a tree named build/ must still be
            // scannable), and a root-only path such as / has no file name to test
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            Path name = dir.getFileName();
            return name != null && EXCLUDED_DIRECTORIES.contains(name.toString())
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            Path relative = root.relativize(file);
            if (isExcluded(relative)) {
                return FileVisitResult.CONTINUE;
            }
            // without FOLLOW_LINKS the walker's attributes describe a link, FIFO, or device
            // itself: only entries whose followed target is a regular file carry scannable
            // content, and the followed size — not the link's — is what the byte cap bounds,
            // so a link to a large file is skipped by the same never-opened rule
            if (!Files.isRegularFile(file)) {
                return FileVisitResult.CONTINUE;
            }
            long followedBytes;
            try {
                followedBytes = Files.size(file);
            } catch (NoSuchFileException vanished) {
                return FileVisitResult.CONTINUE;
            } catch (IOException unmeasurable) {
                throw new IllegalStateException(
                        "hygiene scan cannot size " + file + ": " + reasonOf(unmeasurable), unmeasurable);
            }
            if (followedBytes > MAX_SCANNABLE_BYTES) {
                skippedOversizedFiles++;
                return FileVisitResult.CONTINUE;
            }
            violations.addAll(scanFile(file, relative));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            throw new IllegalStateException("hygiene scan cannot visit " + file + ": " + reasonOf(exception), exception);
        }
    }

    private static List<Violation> scanFile(Path file, Path display) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException vanished) {
            // entries that vanish mid-scan (broken symlinks, deleted files) carry no durable content
            return List.of();
        } catch (IOException unreadable) {
            throw new IllegalStateException("hygiene scan cannot read " + file + ": " + reasonOf(unreadable), unreadable);
        }
        if (isBinary(bytes)) {
            return List.of();
        }
        List<Violation> found = new ArrayList<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = stripTrailingCarriageReturn(lines[index]);
            for (Pattern pattern : REJECT_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    found.add(new Violation(display, index + 1, matcher.group(), line));
                }
            }
        }
        return found;
    }

    private static String reasonOf(IOException exception) {
        if (exception instanceof FileSystemException fileSystemException) {
            String reason = fileSystemException.getReason();
            if (reason != null) {
                return reason;
            }
            String message = exception.getMessage();
            if (message == null || message.equals(fileSystemException.getFile())) {
                return exception.getClass().getSimpleName();
            }
            return message;
        }
        String message = exception.getMessage();
        return message != null ? message : exception.getClass().getSimpleName();
    }

    private static boolean isExcluded(Path relative) {
        String name = relative.getFileName().toString();
        if (name.equals(".env") || (name.startsWith(".env.") && !name.endsWith(".example"))) {
            return true;
        }
        if (name.equals(".DS_Store") || name.equals("mise.lock")) {
            return true;
        }
        if (name.startsWith("gradle.lockfile") || name.endsWith(".lockfile")) {
            return true;
        }
        // compared by path components so the exclusion holds wherever the separator is not '/'
        if (relative.getNameCount() == 2
                && relative.getName(0).toString().equals("gradle")
                && relative.getName(1).toString().equals("verification-metadata.xml")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BINARY_OR_GENERATED_EXTENSIONS.contains(extension);
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_LIMIT);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
