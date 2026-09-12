# Extending and testing

The durable guide for changing this code base: what each test layer is for, what the shared
harnesses can do, where fixtures live, and the full wiring a new endpoint command touches.
The architecture rules and their fixtures live in
`src/test/java/io/amscotti/bravesearch/architecture/ArchitectureTest.java`; the contract each
CLI surface promises lives in `docs/cli-contract.md` and `docs/brave-api-contract.md`, mapped
requirement-by-requirement in `docs/contract-index.yaml`.

## The six test layers

Every behavior is pinned at the cheapest layer that can prove it, and each layer only
re-proves what the cheaper layers cannot:

1. **Unit layer** (`src/test`, plain JUnit): domain and application behavior — request
   validation bounds, projection extraction, precedence tables, parser matrices — with no
   process, no network, and no I/O. Pure inputs and injected fakes; a test that needs a
   server or a launcher does not belong here.
2. **Loopback contract layer** (`src/test`, `io.amscotti.bravesearch.adapter.bravehttp..`):
   adapter behavior against the scripted byte server below — exact wire forms, response
   bounds, content-type enforcement, error classification, the SSE transport contract, and
   the streaming gateway's deadlines and cleanup. Every exchange answers from 127.0.0.1; the
   suite never touches the network.
3. **Process layer** (`src/test`, `io.amscotti.bravesearch.adapter.cli.command..` and
   `bootstrap`): the assembled CLI as a child process through the installed JVM launcher
   (`brave.search.jvm.launcher`, injected by the build after `installDist`) — exit codes,
   output documents validated against the JSON Schemas, failure precedence end to end,
   broken pipes, signal deaths, and the ANSI decision through a real pseudo-terminal.
4. **Executable architecture and hygiene gates**
   (`ArchitectureTest` and `hygieneCheck`): the package rules of
   `docs/architecture.md`, each proven against a dedicated violating fixture and against the
   production classes, plus the repository hygiene scanner over the working tree.
5. **Native smoke suite** (`src/nativeSmokeTest`): the agreeing subset of the same scenarios
   re-run against the ahead-of-time executable (`brave.search.native.binary`, injected after
   `nativeCompile`), so behavior proven on the JVM is proven again where the substrate
   differs — signals, streams, and image-time initialization.
6. **Live protocol smoke** (`@Tag("live")`, opt-in): bounded, serial, protocol-invariants-only
   exchanges against the production origin. Default builds exclude the tag entirely; the
   opt-in and its policies are the live protocol smoke section of `docs/release.md`.

## The shared harnesses

All harnesses live in `src/testFixtures/.../testsupport` and are shared by the JVM and
native suites.

- **`ScriptedSseServer`** — a scriptable HTTP byte server on an ephemeral loopback port. A
  script is a fluent sequence of steps: `writeBytes` deliveries, `flush` boundaries,
  `heartbeat` comment lines, `stallUntil(latch)` gates that freeze the body mid-stream,
  `holdHeadersUntil(latch)` gates that delay even the response head, `delay(duration)`
  pacing, `writeBytesUntilClosed` (an endless body the reader must cut), and the terminal
  `abruptClose()` that breaks the connection with the framing deliberately unfinished.
  `writeGzippedUntilClosed` streams an endless gzip member of incompressible noise until the
  connection breaks — the oversized-gzip cut. `startSequence(...)` replays one builder per
  consecutive request for multi-request walks. Every incoming request is recorded (method,
  path, headers, body bytes) for exact wire-form assertions; a request body past the
  one-megabyte recording bound fails its exchange loudly instead of being recorded truncated.
  When multiple client invocations share a server, `awaitRequestCount` waits for each new
  client's arrival before a test sends signals; an earlier request cannot satisfy that wait.
- **`ProcessHarness`** — the robust child-process runner. It drains stdout and stderr
  concurrently on dedicated threads (a child flooding both streams can never deadlock on a
  full pipe), applies a hard deadline, and on expiry kills the whole process tree —
  descendants first — so no path out of the harness leaks a live child. POSIX signal deaths
  decode as `128 + signum` (SIGINT 130, SIGTERM 143). The environment is layered over the
  inherited one after removing named variables, so tests scrub inherited credentials before
  adding their own; environment values never appear in any harness message.
- **`PtyHarness`** — runs a child on a real pseudo-terminal (allocated by an embedded
  `python3` helper) in two layouts: all streams on the terminal slave, proving the child has
  a console that is a terminal, and the stdout-pipe layout that keeps the console but routes
  stdout through a plain pipe — the case where color must stay off despite an attached
  console. The helper's streams are drained while it runs, so terminal output larger than an
  operating-system pipe buffer still completes, and a hard deadline bounds every run: on
  expiry the slave-side process group is killed outright, so a child that ignores the
  terminal's hangup signal cannot survive as an orphan. The negative process-group argument
  to `/bin/kill` follows `--`, so Linux option parsing cannot redirect cleanup signals to
  other processes, including the test runner.

## Fixture and schema policy

- Recorded upstream bodies live under `src/test/resources/fixtures/brave/<endpoint>/` and
  are hand-shaped from the documented upstream reference — trimmed to the members under
  test, never a wholesale live capture, and never carrying anything opaque or secret.
- Help output is pinned by golden snapshots under `src/test/resources/help/`, rendered from
  the assembled process command line through the process's own stdout writer, so an
  unannounced grammar change fails as a diff and any character that writer cannot carry
  fails there first: help text stays within Latin-1, the charset whose one-byte round trip
  also keeps the streaming relay byte-lossless.
- Machine documents are validated against the normative JSON Schemas under `schemas/v1/`
  through the shared `SchemaCatalog`; a new record shape means a new schema referenced from
  `schemas/v1/jsonl-record.schema.json`, not a loosened validator.

## Adding a new endpoint command

The wiring a new endpoint touches, in dependency order — each piece arrives with the tests
of the layer that proves it, and no later piece exists before its earlier piece is green:

1. **Request** — the immutable `domain.request` value with every documented bound enforced
   at construction (a usage error, never a wire value), pinned by its unit test.
2. **Port** — the outbound `application.port.out` interface and its exchange/dispatch
   records if the endpoint adds a new exchange shape; existing shapes are reused as-is.
3. **Endpoint** — the `adapter.bravehttp.endpoint` translation: query serialization, header
   assembly, the GET/POST rule. Pinned by an endpoint test against `ScriptedSseServer`
   asserting the exact encoded URI and headers.
4. **Gateway** — the port's concrete implementation over the shared transport, classified
   failures and bounded bodies included.
5. **Composition wiring** — a bootstrap composition record plus its registration in
   `Main`, and the same gateway handed to `api.internal.Assembler` when the endpoint belongs
   to the library surface; only composition roots instantiate adapters.
6. **Command and option guard** — the `adapter.cli.command` declaration mixing in
   `GlobalOptions`, `RemoteOptions`, and `CommonSearchOptions`; where the endpoint documents
   only a subset of the shared grammar, the complement guard
   (`rejectUndocumentedExcept`) refuses every undocumented shared spelling before dispatch.
7. **Presenter and extractor** — the `adapter.cli.presentation` rendering for all four
   output modes and the projection extractor for the machine documents; a family that
   renders a shared-layout human listing joins the family set of
   `HumanListingFamiliesTest`.
8. **Schemas and fixtures** — the new record schemas under `schemas/v1/`, referenced from
   `jsonl-record.schema.json`, and the shaped fixtures under
   `src/test/resources/fixtures/brave/<endpoint>/`.
9. **Scenarios, JVM and native** — the process-layer scenario class in
   `src/test/.../command/<endpoint>` and its agreeing native twin in
   `src/nativeSmokeTest/...`, both validating every emitted document against the schemas.
10. **Help golden** — regenerate the command's snapshot under `src/test/resources/help/`
    from the assembled command line; the golden's diff is the reviewable grammar change.
11. **Contract-index identifier** — the new stable requirement identifiers in
    `docs/contract-index.yaml`, each mapping one documented section to one executable test
    (and registered in `ContractIndexTest`'s core-requirement set).
12. **Documentation sections** — the command's options section and output section in
    `docs/cli-contract.md`, the wire form in `docs/brave-api-contract.md`, the README usage
    family, and the packaged skill where the surface is user-visible.

## Golden maintenance

A golden file (help snapshots, byte-exact envelope fixtures, packaged completion scripts)
changes only as the visible diff of an intentional contract change made in the same change
set: regenerate from the real source — the assembled command line, the running codec, the
packaged resource — never hand-edit the golden, and review the byte diff as part of the
change. An unexplained golden diff is a regression report, not an update to accept; if the
new behavior is wrong, fix the behavior and the golden follows.
