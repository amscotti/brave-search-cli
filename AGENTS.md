# AGENTS.md

Working guide for agents (and humans) in this repository. `brave-search` is a Java 25 CLI and
library for the Brave Search API: a single Gradle module using picocli for the command line
and Jackson for JSON, compiled ahead-of-time to a native executable.

## Build and test

The toolchain comes from `mise.toml` (a GraalVM Community JDK 25 with native-image). Unset any ambient
`GRAALVM_HOME` so Gradle uses exactly that toolchain, avoid the configuration-cache-unfriendly
daemon in automation contexts, and keep output plain:

```console
env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain clean check
```

`check` runs the unit tests (including the executable architecture test and the packaged
release-archive verification, which build the native image first), the JaCoCo coverage
report with its line/branch floor over the JVM suite, the repository hygiene
gate, the native smoke test suite (expect several minutes), the library publication check
(`verifyLibraryPublication`: the mavenLocal artifacts, their contents, and the pom facts),
the independent `sample-consumer` suite against the published artifact, and the
verification-metadata completeness guard (`verifyMetadataComplete`: every component pinned
by the committed lock state must carry an entry in `gradle/verification-metadata.xml`).

A focused test run uses Gradle's `--tests` filter:

```console
env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain test \
  --tests 'io.amscotti.bravesearch.architecture.ArchitectureTest'
```

The hygiene gate alone:

```console
env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain hygieneCheck
```

The opt-in live protocol smoke (bounded, serial exchanges against the production origin;
every default build excludes it) and its policies: the live protocol smoke section of
`docs/release.md`. The sample consumer that proves the published library standalone lives
under `sample-consumer/` and runs through the aggregate `sampleConsumerCheck` task, which
forwards `-PreleaseVersion` so a release build consumes exactly the version being released.

Formatting is enforced by Spotless across Java sources (every `src/*/java` tree), the
Gradle Kotlin scripts (`ktlint`), Markdown under `docs/`, YAML/JSON (including the
workflow files), and the auxiliary TOML/properties/lock surfaces; run
`... spotlessApply` before pushing changes if `check` reports formatting failures.

The test layers, their shared harnesses, the fixture policy, and the wiring a new endpoint
command touches are described in `docs/extending.md`.

## Coding conventions

Time is never read from a clock global: production code that stamps anything (deadlines,
rate windows, progress marks) receives an injected `java.time.Clock`, so tests pin time
exactly. There is no logging framework and none is added: diagnostics are an injected sink
(`DiagnosticsSink` and the warnings channel) so the CLI owns what reaches stderr and machine
documents carry their own advisory members. Text is explicit about its bytes — every
reader and writer names `StandardCharsets.UTF_8`; there is no default-charset call anywhere.

## Strict RED-GREEN-REFACTOR

Development follows strict test-driven discipline. No production code is written without a
failing test that pins the behavior first.

1. **RED** — write the smallest test that expresses the missing behavior; run it and confirm
   it fails for exactly the expected reason.
2. **GREEN** — write the minimum production code that makes it pass. No speculative
   generality, no unrequested features.
3. **REFACTOR** — improve names and structure with the suite green, keeping every
   architecture and hygiene rule satisfied.

Tests are named for behavior (for example `streamParserJoinsRepeatedDataLines`), never for
process steps or work items. Test failures should read as behavioral bug reports.

## Architecture

Ports-and-adapters in a single Gradle module: `domain` and `application` form the inside;
`adapter.bravehttp`, `adapter.cli`, and `adapter.config` are the outside; `api` is the public
library surface with `api.internal` as its composition package; `bootstrap` is the CLI
composition root and the only process entry point. Only `bootstrap..` and `api.internal..`
instantiate concrete adapters. Transport types (`java.net.http`, the bravehttp package) never
leak past `adapter.bravehttp`. Only `bootstrap.Main` exits the process. Standard streams
belong to CLI presentation and bootstrap; environment and console access belong to
`adapter.config` and the single injected `TerminalDetector`. `domain` and `application` use
neither picocli nor Jackson. Packages are cycle-free, the public `api` package references
only `api`/`domain`/allowlisted exports, and no production code uses reflection.

The full behavioral description lives in `docs/architecture.md`, and every rule is executable
in `src/test/java/io/amscotti/bravesearch/architecture/ArchitectureTest.java` — each rule is
proven against a dedicated violating fixture and against the production classes. If a change
feels blocked by a rule, change the design, not the test.

## Hygiene policy

Durable artifacts (source, tests, Gradle files, workflows, scripts, skills, documentation,
release metadata) must never contain planning terminology: numbered work-item references,
numbered definitions of done, numbered stage phrases, or mentions of a planning document.
Name things for their behavior, explain comments with
behavior and rationale (never chronology), and never reference upcoming work by number. The
ordinary domain use of words such as "phase" is allowed; numbered process references are not.

The gate is `hygieneCheck` (see above); it scans the repository tree and fails with a
file/line report on any match. If it flags your text, rephrase — do not widen the exclusion
set.

The scanner approximates tracked-file enumeration without invoking git: it walks the working
tree rather than the version-controlled file list, so an untracked text file is still scanned.
It skips the tool-generated directories `.git`, `.gradle`, `build`, `out`, and the review
harness's scratch `.review` (its state files quote hygiene failure reports and would
otherwise trip the gate on their own quotations); editor
droppings (`.idea`, `.vscode`, `.settings`, `node_modules`, `.DS_Store`); secret files
(`.env`, `.env.*` except committed `*.example` templates) so secrets can never surface in a
failure report; binary and generated content by extension (jars, images, archives, native
libraries, fonts, keystores, and similar), plus `mise.lock`, `gradle.lockfile*`/`*.lockfile`,
and the root `gradle/verification-metadata.xml`; and any file whose leading bytes contain a NUL octet.

## Security rules

- The untracked `.env` at the repository root holds a **live credential**. Never print, copy,
  transmit, or commit its contents, and never open it — including from tooling or tests.
- Secrets never appear in logs, command arguments, diagnostics, test output, or failure
  reports. Credentials reach the program only through environment variables or the secure
  config file handled by the config adapter, and nowhere else.
- If a secret may have leaked into any artifact, stop and report it; do not attempt to hide
  it by rewriting history.

## Git and repository operations

Repository operations — commits, branches, merges, tags, pushes, remote changes, history
rewrites — require explicit human authorization for each operation. Never run git or alter
version-control state on your own initiative, and never create pull requests, releases, or
tags unprompted.
