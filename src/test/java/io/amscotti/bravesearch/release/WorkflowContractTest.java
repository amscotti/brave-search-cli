package io.amscotti.bravesearch.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Durability gate of the authored CI and release workflows: both files parse under the strict
 * YAML subset the repository uses, every action reference is pinned by a full commit SHA from
 * the documented pinned set, and the job structure matches the executable release contract
 * (explicit runner labels, architecture assertions, checksum-first artifact re-verification,
 * and tag verification before any privileged job).
 *
 * <p>The workflows are authored ahead of repository publication and cannot be exercised on a
 * runner from this working tree; this test is the local executable form of their contract, so a
 * renamed job, a floating label, or an unpinned action fails the build instead of waiting for a
 * hosted run.
 */
final class WorkflowContractTest {

    private static final Path CI = Paths.get(".github", "workflows", "ci.yml");

    private static final Path RELEASE = Paths.get(".github", "workflows", "release.yml");

    /**
     * The complete set of action references the workflows may use, each pinned by the full
     * commit SHA its tag resolves to on the GitHub API. Extending the set is a deliberate act
     * that fails here until the release documentation and this constant agree.
     */
    private static final Set<String> PINNED_ACTIONS =
            Set.of(
                    "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1", // v7.0.1
                    "jdx/mise-action@c2a87611a18de5b3828c5652fe268e992400cb5c", // v4.3.0
                    "actions/upload-artifact@330a01c490aca151604b8cf639adc76d48f6c5d4", // v5.0.0
                    "actions/download-artifact@018cc2cf5baa6db3ef3c5f8a56943fffe632ef53", // v6.0.0
                    "actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3", // v9.0.0
                    "actions/attest-build-provenance@e4d4f7c39adfa4c260fb5c147f0622000aa14b99"); // v4.0.0

    private static final Pattern SHA_PINNED_REFERENCE = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    @Test
    void workflowFilesParseUnderTheConstrainedYamlSubset() throws IOException {
        Map<String, Object> ci = Yaml.parse(CI);
        Map<String, Object> release = Yaml.parse(RELEASE);

        assertEquals("CI", str(ci.get("name")), "ci.yml keeps its workflow name stable");
        assertEquals("Release", str(release.get("name")), "release.yml keeps its workflow name stable");
        assertFalse(map(ci.get("jobs")).isEmpty(), "ci.yml declares jobs");
        assertFalse(map(release.get("jobs")).isEmpty(), "release.yml declares jobs");
        assertEquals(
                List.of("v*.*.*"),
                list(map(map(release.get(triggerKey())).get("push")).get("tags")),
                "release.yml triggers only on version-shaped tags");
    }

    @Test
    void ciJobsMatchThePinnedRunnerMatrixAndShaPinnedActions() throws IOException {
        Map<String, Object> ci = Yaml.parse(CI);
        Map<String, Object> jobs = map(ci.get("jobs"));

        assertEquals(
                Set.of("jvm-check", "hygiene", "native-smoke", "dependency-verification"),
                jobs.keySet(),
                "ci.yml declares exactly the documented verification jobs");

        Map<String, Object> jvmCheck = map(jobs.get("jvm-check"));
        List<Object> jvmMatrixOs = list(map(map(jvmCheck.get("strategy")).get("matrix")).get("os"));
        assertEquals(
                List.of("macos-26", "ubuntu-24.04", "ubuntu-24.04-arm"),
                jvmMatrixOs,
                "the JVM job runs the explicit documented runner matrix, never a floating label");

        Map<String, Object> nativeSmoke = map(jobs.get("native-smoke"));
        List<Object> smokeInclude = list(map(map(nativeSmoke.get("strategy")).get("matrix")).get("include"));
        assertEquals(3, smokeInclude.size(), "native smoke pins one runner per release platform");
        List<Map.Entry<String, String>> smokeLegs = new ArrayList<>();
        for (Object include : smokeInclude) {
            Map<String, Object> leg = map(include);
            smokeLegs.add(Map.entry(str(leg.get("os")), str(leg.get("expected-arch"))));
        }
        assertEquals(
                List.of(
                        Map.entry("macos-26", "arm64"),
                        Map.entry("ubuntu-24.04", "x86_64"),
                        Map.entry("ubuntu-24.04-arm", "aarch64")),
                smokeLegs,
                "each native smoke leg pairs its documented runner with the architecture that host"
                        + " reports, so a mis-paired entry fails this gate instead of a hosted run");

        String ciRuns = concatRunScripts(ci);
        assertTrue(ciRuns.contains("clean check"), "the JVM job runs the full clean check");
        assertTrue(ciRuns.contains("--warning-mode=all"), "the JVM build surfaces every Gradle warning");
        assertTrue(
                ciRuns.contains("graalvm-community-25.0.2"),
                "every build leg asserts the exact mise Java pin before Gradle runs");
        assertTrue(ciRuns.contains("hygieneCheck"), "the hygiene job runs the repository hygiene gate");
        assertTrue(
                ciRuns.contains("git ls-files"),
                "the hygiene job fails when a by-design ignored credential file is tracked");
        assertFalse(
                ciRuns.contains("git diff --cached"),
                "the hosted hygiene job never inspects the staged index: actions/checkout leaves the"
                        + " index identical to HEAD and nothing stages files afterwards, so a staged"
                        + " credential-file check is dead logic that only suggests a guarantee CI"
                        + " cannot make");
        assertTrue(
                ciRuns.contains("--write-locks resolveAndLockAll") && ciRuns.contains("git diff --exit-code"),
                "the dependency job rewrites lock state and fails on any drift or extraneous entry");
        assertTrue(
                ciRuns.contains("--refresh-dependencies"),
                "the dependency job re-verifies artifacts from the runner's clean dependency cache");
        assertFalse(
                ciRuns.contains("--info nativeCompile"),
                "no JVM leg builds the native image twice: the toolchain proof reads the"
                        + " check log, so a standalone nativeCompile invocation fails this gate");
        assertTrue(
                ciRuns.contains("--info")
                        && ciRuns.contains("--max-workers=2 clean check")
                        && ciRuns.contains("Native Image executable path:"),
                "the JVM job surfaces the image-builder selection line from its single check"
                        + " invocation and proves the pin from that log");
        assertTrue(
                ciRuns.contains("--max-workers=2 clean check") && ciRuns.contains("--max-workers=2 clean test"),
                "the heavy hosted invocations bound Gradle workers to two so build"
                        + " parallelism leaves resources available for the runner agent");
        Map<String, Object> concurrency = map(ci.get("concurrency"));
        assertTrue(
                str(concurrency.get("group")).contains("github.ref"),
                "the CI concurrency group is scoped per reference");
        assertEquals(
                "true",
                str(concurrency.get("cancel-in-progress")),
                "a superseded run of the same reference is cancelled instead of racing it");

        NavigableSet<String> uses = new TreeSet<>();
        collectUses(ci, uses);
        collectUses(Yaml.parse(RELEASE), uses);
        for (String reference : uses) {
            assertTrue(
                    SHA_PINNED_REFERENCE.matcher(reference).matches(),
                    "action references must be pinned by full commit SHA: " + reference);
        }
        assertEquals(
                PINNED_ACTIONS, uses, "the workflows use exactly the documented SHA-pinned action set");
        assertEquals(
                "read",
                str(map(ci.get("permissions")).get("contents")),
                "ci.yml only ever reads");
    }

    /**
     * A red leg must explain itself: the JVM and native-smoke jobs upload their test
     * reports when they fail, so the next failure carries its expected-against-actual
     * evidence instead of requiring a reproduction.
     */
    @Test
    void ciUploadsTestReportsWhenBuildLegsFail() throws IOException {
        Map<String, Object> jobs = map(Yaml.parse(CI).get("jobs"));
        for (String jobName : List.of("jvm-check", "native-smoke")) {
            boolean uploads = false;
            for (Object stepNode : list(map(jobs.get(jobName)).get("steps"))) {
                if (stepNode instanceof Map<?, ?> step
                        && step.get("uses") instanceof String action
                        && action.startsWith("actions/upload-artifact@")
                        && step.get("if") instanceof String condition
                        && condition.equals("failure()")) {
                    uploads = true;
                }
            }
            assertTrue(
                    uploads,
                    jobName + " uploads its test reports when the leg fails, so a red leg"
                            + " arrives with its own evidence");
        }
    }

    @Test
    void releaseWorkflowResolvesTheAnnotatedTagBeforeAnyPrivilegedJob() throws IOException {
        Map<String, Object> release = Yaml.parse(RELEASE);
        Map<String, Object> jobs = map(release.get("jobs"));

        Map<String, Object> resolver = map(jobs.get("resolve-and-verify-tag"));
        assertEquals(
                "read",
                str(map(resolver.get("permissions")).get("contents")),
                "tag resolution runs with read-only permissions");
        String resolverScript = concatRunScripts(jobs.get("resolve-and-verify-tag"));
        assertTrue(resolverScript.contains("getRef"), "the tag object is resolved through the GitHub API");
        assertTrue(
                resolverScript.contains("verification") && resolverScript.contains("verified"),
                "the tag's signature verification is inspected");
        assertTrue(
                resolverScript.contains("ref.data.object.sha") && resolverScript.contains("context.sha"),
                "the resolver reads the annotated tag object and binds its target to the"
                        + " workflow's commit sha");
        assertTrue(
                resolverScript.contains("listForRef"),
                "the resolver reuses the required CI checks of the exact commit");
        assertTrue(
                resolverScript.contains("targetCommitSha"),
                "the resolver reads the CI check runs for the peeled tag-target commit — the ref"
                        + " they are recorded against — never for the tag object's sha");
        assertTrue(
                resolverScript.contains("context.actor"),
                "the account that pushed the tag is gated against the release maintainer allowlist");
        assertTrue(
                resolverScript.contains("repos.getCommit") && resolverScript.contains("author"),
                "the tag-target commit's author login is gated as a secondary maintainer check");
        assertFalse(
                resolverScript.contains("tagger"),
                "annotated tag objects carry name, email, and date but no tagger login; reading one"
                        + " can never identify the signer against the allowlist");
        assertTrue(
                resolverScript.contains("requiredChecks"),
                "the required CI checks are named in one constant");
        assertTrue(
                resolverScript.contains("lightweight") || resolverScript.contains("type"),
                "lightweight tags are rejected before any privileged job");

        for (Map.Entry<String, Object> job : jobs.entrySet()) {
            if (job.getKey().equals("resolve-and-verify-tag")) {
                continue;
            }
            List<Object> needs = list(map(job.getValue()).get("needs"));
            assertFalse(
                    needs.isEmpty(),
                    "every release job depends on tag verification, directly or through a chain: " + job.getKey());
        }

        Map<String, Object> publisher = map(jobs.get("publish-release"));
        Set<String> needs = Set.copyOf(list(publisher.get("needs")).stream().map(WorkflowContractTest::str).toList());
        assertTrue(
                needs.containsAll(Set.of("verify-macos-aarch64", "verify-linux-x86-64", "verify-linux-aarch64")),
                "the publisher waits for all platform verifications");
        Map<String, Object> publishPermissions = map(publisher.get("permissions"));
        assertEquals(
                "write", str(publishPermissions.get("contents")), "only the publisher may write contents");
        for (Map.Entry<String, Object> job : jobs.entrySet()) {
            if (job.getKey().equals("publish-release")) {
                continue;
            }
            Map<String, Object> permissions = map(map(job.getValue()).get("permissions"));
            assertTrue(
                    permissions.isEmpty() || "read".equals(str(permissions.get("contents"))),
                    "no job but the publisher holds write permissions: " + job.getKey());
        }
    }

    @Test
    void releaseWorkflowReverifiesEachPlatformArtifactBeforePublishing() throws IOException {
        Map<String, Object> jobs = map(Yaml.parse(RELEASE).get("jobs"));

        Map<String, Object> macBuild = map(jobs.get("build-macos-aarch64"));
        assertEquals("macos-26", str(macBuild.get("runs-on")), "the macOS build runs on the pinned runner");
        assertEquals(
                List.of("resolve-and-verify-tag"),
                list(macBuild.get("needs")),
                "builds start only after tag verification");
        String macBuildRuns = concatRunScripts(macBuild);
        assertTrue(macBuildRuns.contains("uname -m"), "the build asserts its host architecture");
        assertTrue(
                macBuildRuns.contains("releaseArchive") && macBuildRuns.contains("sha256Sums"),
                "the build produces the versioned archive and its checksums");

        Map<String, Object> linuxBuild = map(jobs.get("build-linux-x86-64"));
        assertEquals("ubuntu-24.04", str(linuxBuild.get("runs-on")), "the Linux build runs on the pinned runner");

        Map<String, Object> linuxArmBuild = map(jobs.get("build-linux-aarch64"));
        assertEquals("ubuntu-24.04-arm", str(linuxArmBuild.get("runs-on")), "the Linux AArch64 build runs on the pinned runner");

        for (String buildJob : List.of("build-macos-aarch64", "build-linux-x86-64", "build-linux-aarch64")) {
            String buildRuns = concatRunScripts(jobs.get(buildJob));
            assertTrue(
                    buildRuns.contains("graalvm-community-25.0.2"),
                    buildJob + " asserts the exact mise Java pin before Gradle runs");
            assertTrue(
                    buildRuns.contains("native-image"),
                    buildJob + " cross-checks native-image resolution against the pinned toolchain");
        }

        for (Map.Entry<String, String> verificationLeg :
                List.of(
                        Map.entry("verify-macos-aarch64", "arm64"),
                        Map.entry("verify-linux-x86-64", "x86-64"),
                        Map.entry("verify-linux-aarch64", "aarch64"))) {
            String verificationJob = verificationLeg.getKey();
            Map<String, Object> verify = map(jobs.get(verificationJob));
            NavigableSet<String> verifyUses = new TreeSet<>();
            collectUses(verify, verifyUses);
            assertTrue(
                    verifyUses.contains("actions/download-artifact@018cc2cf5baa6db3ef3c5f8a56943fffe632ef53"),
                    verificationJob + " downloads the packaged artifact instead of rebuilding it");
            assertEquals(
                    "release-" + verificationJob.substring("verify-".length()),
                    downloadedArtifactName(verify),
                    verificationJob + " downloads its own platform's packaged artifact by name,"
                            + " never another leg's");
            String runs = concatRunScripts(verify);
            assertTrue(
                    runs.contains("-c SHA256SUMS") || runs.contains("SHA256SUMS"),
                    verificationJob + " validates checksums before trusting the artifact");
            assertTrue(runs.contains("tar -xzf"), verificationJob + " unpacks the release archive");
            assertTrue(
                    runs.contains("grep -q '" + verificationLeg.getValue() + "'"),
                    verificationJob + " greps the unpacked binary's architecture for "
                            + verificationLeg.getValue() + ", the token its own platform reports");
            assertTrue(runs.contains("--version"), verificationJob + " reruns the binary's version check");
            assertTrue(
                    runs.contains("RELEASE_VERSION"),
                    verificationJob + " compares the printed version against the verified tag version");
            assertTrue(
                    runs.contains("test -x") || runs.contains("-x "),
                    verificationJob + " checks the unpacked binary's executable mode");
        }

        Map<String, Object> publisher = map(jobs.get("publish-release"));
        String publishRuns = concatRunScripts(publisher);
        assertTrue(
                publishRuns.contains("SHA256SUMS") && publishRuns.contains(".tar.gz"),
                "the publisher uploads archives and checksums together");
        assertTrue(
                publishRuns.contains("sha256sum") && publishRuns.contains(".sbom.json"),
                "the publisher generates one aggregate checksum file covering every archive and SBOM"
                        + " after all platform artifacts land, and uploads it exactly once");
        assertFalse(
                publishRuns.contains("*/SHA256SUMS"),
                "per-platform checksum files never upload: their basenames collide as release assets");
        assertTrue(
                publishRuns.contains("gh release view") && publishRuns.contains("--clobber"),
                "publication is re-runnable: the release object exists before its assets finish"
                        + " uploading, so the publisher creates the release only when it is missing and"
                        + " uploads with --clobber instead of failing a re-run on 'release already"
                        + " exists'");
        NavigableSet<String> publishUses = new TreeSet<>();
        collectUses(publisher, publishUses);
        assertTrue(
                publishUses.contains("actions/attest-build-provenance@e4d4f7c39adfa4c260fb5c147f0622000aa14b99"),
                "the publisher attests build provenance where repository capabilities permit");
    }

    /**
     * The names the release resolver requires green must be exactly the check-run display
     * names ci.yml produces — a job's {@code name} with each matrix label substituted —
     * because check runs carry bare display names, never a workflow-prefixed form. This is the
     * local cross-check that fails when a job rename or matrix change leaves the release gate
     * asking for a check name GitHub never reports.
     */
    @Test
    void releaseRequiredChecksAreExactlyTheCiJobDisplayNames() throws IOException {
        Map<String, Object> ci = Yaml.parse(CI);
        String resolverScript = concatRunScripts(map(map(Yaml.parse(RELEASE).get("jobs")).get("resolve-and-verify-tag")));

        assertEquals(
                expectedCiCheckDisplayNames(ci),
                new TreeSet<>(requiredCheckNames(resolverScript)),
                "every required check name must equal a ci.yml job display name (job name plus"
                        + " matrix label), exactly and per leg");
    }

    /**
     * The release flow proves its dependency verification end to end before publishing: one
     * dedicated leg runs the tampered-dependency drill against the tagged sources, the
     * publisher waits for it beside the three platform verifications, and the drill's evidence
     * uploads like every other verification leg's.
     */
    @Test
    void releaseWorkflowRunsTheTamperDrillBeforePublishing() throws IOException {
        Map<String, Object> jobs = map(Yaml.parse(RELEASE).get("jobs"));

        Map<String, Object> drill = map(jobs.get("tamper-drill"));
        assertEquals("ubuntu-24.04", str(drill.get("runs-on")), "the drill runs on the pinned Linux runner");
        assertEquals(
                List.of("resolve-and-verify-tag"),
                list(drill.get("needs")),
                "the drill builds the same tagged sources the releases come from");
        String drillRuns = concatRunScripts(drill);
        assertTrue(
                drillRuns.contains("dependencyTamperProof"),
                "the drill leg executes the tampered-dependency proof, not a weaker echo of it");
        assertTrue(
                drillRuns.contains("graalvm-community-25.0.2"),
                "the drill asserts the exact mise Java pin before Gradle runs, like every build leg");
        NavigableSet<String> drillUses = new TreeSet<>();
        collectUses(drill, drillUses);
        assertFalse(
                drillUses.stream().anyMatch(reference -> reference.contains("wrapper-validation")),
                "the discontinued upstream wrapper-validation action stays out of every leg:"
                        + " upstream removed it, so a reference to it fails the hosted run before"
                        + " any build starts");
        assertTrue(
                drillUses.contains("actions/upload-artifact@330a01c490aca151604b8cf639adc76d48f6c5d4"),
                "the drill uploads its evidence like every verification leg");

        Map<String, Object> publisher = map(jobs.get("publish-release"));
        Set<String> publishNeeds =
                Set.copyOf(list(publisher.get("needs")).stream().map(WorkflowContractTest::str).toList());
        assertTrue(
                publishNeeds.contains("tamper-drill"),
                "the publisher waits for the tamper drill beside the three platform verifications");
    }

    /**
     * Wrapper integrity without the discontinued validation action: the wrapper validates its
     * own distribution on every invocation against the checksum committed beside it, so the
     * committed properties must pin a full SHA-256 and require URL validation.
     */
    @Test
    void gradleWrapperDistributionIsChecksumPinned() throws IOException {
        List<String> lines = Files.readAllLines(
                Paths.get("gradle", "wrapper", "gradle-wrapper.properties"), StandardCharsets.UTF_8);

        assertTrue(
                lines.stream().anyMatch(line -> line.matches("distributionSha256Sum=[0-9a-f]{64}")),
                "the wrapper distribution checksum stays pinned to a full SHA-256");
        assertTrue(
                lines.contains("validateDistributionUrl=true"),
                "the wrapper validates the distribution URL on every invocation");
    }

    /** The artifact name the job's download-artifact step requests, empty when it names none. */
    private static String downloadedArtifactName(Map<String, Object> job) {
        for (Object stepNode : list(job.get("steps"))) {
            if (stepNode instanceof Map<?, ?> step
                    && step.get("uses") instanceof String action
                    && action.startsWith("actions/download-artifact@")
                    && step.get("with") instanceof Map<?, ?> with
                    && with.get("name") instanceof String name) {
                return name;
            }
        }
        return "";
    }

    /** Derives the check-run display names ci.yml produces: job name plus every matrix label. */
    private static Set<String> expectedCiCheckDisplayNames(Map<String, Object> ci) {
        Set<String> displayNames = new TreeSet<>();
        for (Object jobNode : map(ci.get("jobs")).values()) {
            Map<String, Object> job = map(jobNode);
            String name = str(job.get("name"));
            List<Object> osLabels = matrixOsLabels(job);
            assertTrue(
                    osLabels.isEmpty() || name.contains("${{ matrix.os }}"),
                    "a matrix job's name must embed ${{ matrix.os }} so every runner leg reports its"
                            + " own check-run display name; without the label all legs collapse to one"
                            + " name and one green leg substitutes for the others in the release gate: "
                            + name);
            if (name.contains("${{ matrix.os }}") && !osLabels.isEmpty()) {
                for (Object os : osLabels) {
                    displayNames.add(name.replace("${{ matrix.os }}", str(os)));
                }
            } else {
                displayNames.add(name);
            }
        }
        return displayNames;
    }

    /** Matrix os labels from either the plain os list or the include entries. */
    private static List<Object> matrixOsLabels(Map<String, Object> job) {
        if (!(job.get("strategy") instanceof Map<?, ?> strategy) || !(strategy.get("matrix") instanceof Map<?, ?> matrix)) {
            return List.of();
        }
        if (matrix.get("os") instanceof List<?> os) {
            return list(os);
        }
        if (matrix.get("include") instanceof List<?> include) {
            List<Object> labels = new ArrayList<>();
            for (Object entry : include) {
                labels.add(map(entry).get("os"));
            }
            return labels;
        }
        return List.of();
    }

    /** The single-quoted entries of the resolver's requiredChecks constant. */
    private static List<String> requiredCheckNames(String script) {
        int keyword = script.indexOf("requiredChecks");
        assertTrue(keyword >= 0, "the resolver names its required checks in a requiredChecks constant");
        int open = script.indexOf('[', keyword);
        int close = script.indexOf(']', open);
        assertTrue(open >= 0 && close > open, "requiredChecks is an array literal");
        Matcher quoted = Pattern.compile("'([^']+)'").matcher(script.substring(open, close));
        List<String> names = new ArrayList<>();
        while (quoted.find()) {
            names.add(quoted.group(1));
        }
        assertFalse(names.isEmpty(), "requiredChecks lists at least one check name");
        return names;
    }

    /** The workflows key the trigger block under {@code on}. */
    private static String triggerKey() {
        return "on";
    }

    private static String concatRunScripts(Object node) {
        StringBuilder text = new StringBuilder();
        appendRunScripts(node, text);
        return text.toString();
    }

    /** Collects every step body: {@code run:} blocks and github-script {@code script:} blocks. */
    private static void appendRunScripts(Object node, StringBuilder text) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (("run".equals(entry.getKey()) || "script".equals(entry.getKey()))
                        && entry.getValue() instanceof String body) {
                    text.append(body).append('\n');
                } else {
                    appendRunScripts(entry.getValue(), text);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                appendRunScripts(item, text);
            }
        }
    }

    private static void collectUses(Object node, NavigableSet<String> uses) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("uses".equals(entry.getKey()) && entry.getValue() instanceof String action) {
                    uses.add(action);
                } else {
                    collectUses(entry.getValue(), uses);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectUses(item, uses);
            }
        }
    }

    private static Map<String, Object> map(Object node) {
        assertNotNull(node, "expected a mapping, found nothing");
        assertTrue(node instanceof Map, () -> "expected a mapping, found: " + node);
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) node;
        return cast;
    }

    private static List<Object> list(Object node) {
        assertNotNull(node, "expected a sequence, found nothing");
        assertTrue(node instanceof List, () -> "expected a sequence, found: " + node);
        @SuppressWarnings("unchecked")
        List<Object> cast = (List<Object>) node;
        return cast;
    }

    private static String str(Object node) {
        assertTrue(node instanceof String, () -> "expected a scalar string, found: " + node);
        return (String) node;
    }

    /**
     * Strict parser for the exact YAML subset the repository's workflows use: nested mappings,
     * sequences whose items are scalars or inline-started mappings, single-quoted and plain
     * scalars, flow sequences, literal {@code |} blocks, and blank or comment lines. Anything
     * else — tabs, inconsistent structure — fails loudly instead of being silently skipped, the
     * same durability approach the contract-index parser takes.
     */
    static final class Yaml {

        private record Line(int indent, String content, int number) {}

        private final List<Line> lines;

        private int cursor;

        private Yaml(List<Line> lines) {
            this.lines = lines;
        }

        static Map<String, Object> parse(Path file) throws IOException {
            List<String> raw;
            try {
                raw = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException missing) {
                throw new IllegalStateException("cannot read " + file + ": " + missing.getMessage(), missing);
            }
            List<Line> lines = new ArrayList<>();
            for (int number = 1; number <= raw.size(); number++) {
                String line = raw.get(number - 1);
                if (line.contains("\t")) {
                    throw new IllegalStateException(file + ":" + number + " contains a tab; the subset uses spaces");
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                String content = line.substring(indent);
                if (content.isEmpty() || content.startsWith("#")) {
                    continue;
                }
                lines.add(new Line(indent, content, number));
            }
            if (lines.isEmpty()) {
                throw new IllegalStateException(file + " has no content");
            }
            Yaml parser = new Yaml(lines);
            Object parsed = parser.parseNode(lines.get(0).indent());
            if (parser.cursor != parser.lines.size()) {
                throw new IllegalStateException(
                        file + ":" + parser.lines.get(parser.cursor).number()
                                + " trails the document at an unexpected indentation");
            }
            return map(parsed);
        }

        /** Parses the mapping or sequence that lives at exactly {@code indent}. */
        private Object parseNode(int indent) {
            Line head = lines.get(cursor);
            if (head.content().equals("-") || head.content().startsWith("- ")) {
                return parseSequence(indent);
            }
            return parseMapping(indent);
        }

        private List<Object> parseSequence(int indent) {
            List<Object> items = new ArrayList<>();
            while (cursor < lines.size()) {
                Line line = lines.get(cursor);
                if (line.indent() != indent || !(line.content().equals("-") || line.content().startsWith("- "))) {
                    break;
                }
                cursor++;
                String rest = line.content().equals("-") ? "" : line.content().substring(2);
                if (rest.isEmpty()) {
                    items.add(parseNode(indent + 1));
                    continue;
                }
                if (isMappingStart(rest)) {
                    int innerIndent = indent + 2;
                    items.add(parseInlineStartedMapping(rest, innerIndent));
                } else {
                    items.add(scalar(rest));
                }
            }
            return items;
        }

        /**
         * Parses a mapping whose first entry began inline after a sequence dash; the remaining
         * entries sit at exactly {@code innerIndent}.
         */
        private Map<String, Object> parseInlineStartedMapping(String firstEntry, int innerIndent) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            consumeEntry(mapping, firstEntry, innerIndent);
            while (cursor < lines.size() && lines.get(cursor).indent() == innerIndent) {
                Line line = lines.get(cursor);
                if (line.content().startsWith("- ")) {
                    break;
                }
                cursor++;
                consumeEntry(mapping, line.content(), innerIndent);
            }
            return mapping;
        }

        private Map<String, Object> parseMapping(int indent) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            while (cursor < lines.size()) {
                Line line = lines.get(cursor);
                if (line.indent() != indent) {
                    break;
                }
                if (line.content().startsWith("- ")) {
                    break;
                }
                cursor++;
                consumeEntry(mapping, line.content(), indent);
            }
            return mapping;
        }

        private void consumeEntry(Map<String, Object> mapping, String content, int indent) {
            int separator = content.indexOf(':');
            if (separator <= 0) {
                throw new IllegalStateException("expected a 'key: value' entry, found: " + content);
            }
            String key = content.substring(0, separator);
            String value = content.substring(separator + 1).strip();
            if (value.isEmpty()) {
                if (cursor < lines.size() && lines.get(cursor).indent() > indent) {
                    mapping.put(key, parseNode(lines.get(cursor).indent()));
                } else {
                    mapping.put(key, null);
                }
                return;
            }
            if (value.equals("|") || value.equals("|-")) {
                mapping.put(key, literalBlock(indent));
                return;
            }
            mapping.put(key, scalar(value));
        }

        private String literalBlock(int indent) {
            StringBuilder text = new StringBuilder();
            while (cursor < lines.size() && lines.get(cursor).indent() > indent) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(lines.get(cursor).content());
                cursor++;
            }
            return text.toString();
        }

        private static boolean isMappingStart(String content) {
            int separator = content.indexOf(':');
            if (separator <= 0) {
                return false;
            }
            String after = content.substring(separator + 1);
            return after.isEmpty() || after.startsWith(" ") || after.startsWith("'") || after.startsWith("\"");
        }

        private static Object scalar(String value) {
            if (value.startsWith("[") && value.endsWith("]")) {
                List<Object> items = new ArrayList<>();
                for (String item : value.substring(1, value.length() - 1).split(",")) {
                    items.add(scalar(item.strip()));
                }
                return items;
            }
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                return value.substring(1, value.length() - 1);
            }
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                return value.substring(1, value.length() - 1);
            }
            int comment = value.indexOf(" #");
            return comment >= 0 ? value.substring(0, comment).strip() : value;
        }
    }
}
