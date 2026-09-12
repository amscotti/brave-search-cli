# ADR 0003: Native release matrix

- Status: Proposed (local evidence recorded; hosted-runner evidence pending, because the
  repository is not yet published on GitHub)
- Date: 2026-09-01

## Context

The CLI ships as a GraalVM native executable, so every release artifact is platform-specific:
an executable format, a minimum operating-system baseline, and a packaging layout that must
preserve the executable mode. The initial release targets three platforms, chosen for native
GitHub runners and real user demand; every other platform stays out until its build and full
process suite run green on native runners.

macOS x86_64 is explicitly not promised: the pinned GraalVM toolchain line does not
carry a tested x86_64 macOS native-image target, and cross-built Intel binaries would ship
without any runner executing them.

## Decision

The release matrix is exactly:

| Platform | Runner label | Target triple | Archive |
| --- | --- | --- | --- |
| macOS AArch64 | `macos-26` | `aarch64-apple-darwin` | `brave-search-<version>-macos-aarch64.tar.gz` |
| Linux x86_64 | `ubuntu-24.04` | `x86_64-unknown-linux-gnu` | `brave-search-<version>-linux-x86_64.tar.gz` |
| Linux AArch64 | `ubuntu-24.04-arm` | `aarch64-unknown-linux-gnu` | `brave-search-<version>-linux-aarch64.tar.gz` |

Every archive is a reproducible `tar.gz` with exactly one top-level directory named like the
archive stem, containing the executable at `bin/brave-search` (mode `0755`, preserved through
the tar and asserted by unpacking), `README.md`, `LICENSE`, the two packaged completion scripts
under `completions/`, `skills/brave-search/SKILL.md`, the published library/sources/Javadoc
JARs under `lib/`, and the runtime-only CycloneDX SBOM at `sbom/bom.json`. The release
publishes one aggregate `SHA256SUMS` covering every platform archive and SBOM; the
per-platform checksum files are staging verification inputs only.

The matrix expands only from green native-runner evidence: a new target enters after its
build and full process smoke suite pass on a runner of that exact architecture, never by
cross-compiling onto an untested host.

## Measured local evidence (macOS AArch64 only)

Recorded on the maintainer host (Apple M1 Max, macOS 26.6, Liberica NIK OpenJDK
25.0.4.1+1 through mise), for the `0.1.0-SNAPSHOT` development build. **Linux numbers do not
exist yet: no Linux host was available locally, so the Linux row is entirely pending the
hosted runners.**

- Native binary: Mach-O 64-bit executable `arm64`, 51,172,944 bytes (48.8 MiB).
- `--version` wall time, eight runs of the binary unpacked from the release archive:
  first run 752.5 ms (page-cache cold right after unpack), then 20.4–22.1 ms warm
  (median 21.8 ms). Eight runs of the build-directory binary: first 101.0 ms, warm
  20.5–22.0 ms (median 21.8 ms).
- Release archive: `brave-search-0.1.0-SNAPSHOT-macos-aarch64.tar.gz`, 25,522,077 bytes
  (24.3 MiB), SHA-256 `11c5a44f748398311d72ccd8f8d4a1696a6e2b7377c9a394e10e07db8b2db3c6`.
- Runtime SBOM: 11,497 bytes, SHA-256
  `3f00ede23f12a71215782ac65c804442416cba30c1bc02dc27f153450e8e51e4`.
- Reproducibility: deleting and rebuilding the archive and SBOM yields byte-identical
  files (fixed entry timestamps, sorted entries, deterministic modes, no SBOM serial number).
- Executable mode, `--version` behavior, checksum coverage, and runtime-only SBOM scope are
  proven at Gradle level by
  `io.amscotti.bravesearch.release.ReleaseArchiveVerificationTest`, which unpacks the real
  archive with the platform `tar`, checks the executable bit, reruns the binary, re-hashes
  every artifact against `SHA256SUMS`, and asserts test-only dependencies stay out of the
  SBOM.
- Tampered-dependency rejection is proven locally by the `dependencyTamperProof` Gradle
  drill (evidence in `build/reports/dependency-tamper-proof/evidence.txt`): an honest mirror
  of the cached artifacts resolves cleanly under the committed verification metadata, and a
  one-byte-flipped `picocli-4.7.7.jar` fails the build with a dependency-verification error.

## Baselines (documented commitments, pending hosted-runner evidence)

- **macOS:** the intended support floor is macOS 13+. The follow-up was measured locally on
  the maintainer host (macOS 26.6, the pinned Liberica NIK toolchain through mise) with the
  real project binary: exporting `MACOSX_DEPLOYMENT_TARGET=13` before `nativeCompile`
  leaves the Mach-O header unchanged at `LC_BUILD_VERSION minos 26.0` (the environment
  variable does not reach the linker invocation native-image performs), while adding
  `-H:NativeLinkerOption=-mmacosx-version-min=13.0` to `graalvmNative` `buildArgs` lowers
  the same build to `minos 13.0` (`sdk` stays 26.5, verified with `otool -l`). The
  lowering mechanism is therefore proven and is a macOS-only build argument — passed on
  Linux it would reach the GNU linker as an unrecognized option. Until it is adopted for
  the shipped builds and confirmed by hosted-runner evidence, macOS 26.0 remains the only
  shipped floor and `docs/release.md` states it.
- **Linux:** Ubuntu 24.04 with its distribution glibc (2.39) is the baseline; binaries are
  built on and verified against `ubuntu-24.04` (x86_64) and `ubuntu-24.04-arm` (AArch64) runners. No older glibc is promised.
- CI evidence across platforms — runner architecture assertions, archive verification legs,
  and the dependency-verification job — is authored in `.github/workflows/` and executes
  once the repository is published; `docs/release.md` mirrors this status.

## Consequences

- Three platform-specific archives plus `SHA256SUMS` and per-platform SBOMs are the release
  surface; anything else (packages, signing, notarization) stays out of scope until the
  matrix is green on runners.
- Local macOS measurements become the reference until hosted numbers replace them; the ADR
  is finalized from runner evidence at that point.
- Expanding the native matrix requires updating this record, the workflow matrix, and `docs/release.md` in one commit.
