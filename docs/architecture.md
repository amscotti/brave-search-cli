# Architecture

`brave-search` is a Java 25 application built as a single Gradle module organized in
ports-and-adapters (hexagonal) style. The domain and application layers form the inside of
the design and depend on nothing but themselves and the JDK; every technology choice —
picocli, Jackson, the JDK HTTP client, the filesystem, the environment — lives in adapters on
the outside. Two composition roots wire the outside to the inside: `bootstrap.Main` for the
CLI process, and `api.internal.Assembler` for library consumers. The same module publishes a
plain library JAR (`io.amscotti:brave-search-client`) and is compiled ahead-of-time into a
native CLI executable.

## Package responsibilities

| Package | Responsibility |
| --- | --- |
| `io.amscotti.bravesearch.domain` | Immutable, endpoint-specific request, projection, error, and metadata values. `domain.error.Outcome` is the sealed result type (`Success`/`Failure`), `domain.error.FailureKind` the enumerated failure category, `domain.error.FailureSignal` the precedence-ordered failure conditions that also cover user interruption, and `domain.output.OutputMode` the output-channel vocabulary shared by option parsing and presentation. `domain.output` is deliberately a domain package: its vocabulary is the CLI contract's own language — the mode words option parsing accepts, presentation renders, and the exit mapping arbitrates — so placing it anywhere else would make the innermost shared concepts depend on an outer layer. No picocli, HTTP, Jackson, filesystem, environment, console, or standard-stream access. |
| `io.amscotti.bravesearch.application` | The sequential page-walk orchestrator and its sliced pacing waiter (`application.service`) and the outbound ports the composition roots drive (`application.port.out`). Depends only on domain and JDK types; synchronous ports return a sealed `Outcome<T>` whose failures are immutable data carrying a `FailureKind` and a redacted diagnostic, never a raw secret-bearing throwable cause. `application.stream` owns the transport-agnostic streaming run machinery: the terminal-cause latch (`CancellationContext`), the registry contract for live runs (`CancellationRegistry`), the cold single-subscription body publisher, the writer relay with its broken-pipe latch, and the deadline watchdog (see `docs/adr/0005-stream-cancellation.md`). `application.exchange` owns the transport-agnostic exchange vocabulary, itemized: the loopback-verified origin policy with its credential routing, the injectable localhost-resolver seam that policy verifies loopback through, the composed request with its deterministic header assembly and product user agent, the strictly percent-encoded request URI with its redacted rendering, the completed response, the injected response byte ceilings (`ResponseLimits`), the response content-coding trichotomy (`ContentCoding`) those ceilings key off, and the typed response-rejection signal (`ResponseRejectionException`) of a bounded exchange gone malformed. |
| `io.amscotti.bravesearch.adapter.bravehttp` | The Brave HTTP transport: request serialization, response bounds, status mapping, rate metadata, SSE handling, and decoded body bytes. Its `json` subpackage owns tolerant upstream decoding and `endpoint` holds the gateway implementations of the outbound ports, including the probe gateway that opens streaming exchanges over `java.net.http`. Never imports another adapter. |
| `io.amscotti.bravesearch.adapter.cli` | picocli declarations (`command`, `option`), endpoint-valid option combinations, use-case invocation, exit mapping (`exit`), and presentation (`presentation`, with `presentation.json` for schema-versioned machine output; `DiagnosticsSink` and `ResultWriter` keep stderr diagnostics separate from stdout documents, and `ModeAwareWarnings` routes advisories to the channel the active mode owns). Presentation consumes application/domain values and is not an application port. |
| `io.amscotti.bravesearch.adapter.config` | Environment lookup, path resolution, secure reads/writes of the credential file, and console input. The only adapter that reads the API-key environment variable. |
| `io.amscotti.bravesearch.api` | The public library surface: `BraveSearchClient`, its builder, and the public response/streaming types. References only `api..`, `domain..`, JDK types, and the allowlisted exported application types. |
| `io.amscotti.bravesearch.api.internal` | The library-side composition package. `Assembler` is the only class here that instantiates concrete adapters; it wires them into a plain record of functions the public builder unpacks, and it injects the tolerant upstream codec through which the facade materializes the caller-owned `JsonNode` snapshot at the API boundary (see `docs/adr/0001-public-java-api-shape.md`). |
| `io.amscotti.bravesearch.bootstrap` | The CLI composition root and the sole process entry point (`Main`). Owns process exit, the SIGINT and SIGTERM latches for every live exchange — streaming runs and single-request dispatches alike — and the CLI's instantiation of adapters. |

## Executable dependency, transport, stream, and environment rules

Every rule below is enforced twice by
`src/test/java/io/amscotti/bravesearch/architecture/ArchitectureTest.java`: against a
dedicated violating fixture (so the rule is proven to bite) and against the production
classes (so the code base is proven clean). The fixtures live in the test source set inside
the layer packages whose isolation they violate and are never executed.

- **Domain isolation.** Nothing in `domain..` depends on `application..`, `adapter..`, or
  `bootstrap..`; the domain is the innermost layer and knows nothing of the layers that use
  it.
- **Application boundary.** Classes in `application..` depend only on `application..`,
  `domain..`, and JDK types. The application layer never sees picocli, Jackson, or HTTP.
- **Adapter dependencies.** Adapters never depend on `bootstrap..`, on `api..`, and never on
  each other: there is no exception, not even between the CLI and config adapters — they
  cooperate exclusively through ports. What the rules do not constrain is every other import:
  beyond domain and application types, adapters may use the JDK and their own technology
  choices (picocli in the CLI adapter, Jackson in the bravehttp JSON codec).
- **Composition roots.** Only `bootstrap..` and `api.internal..` may call the constructor of
  a concrete adapter. Everywhere else, collaborators arrive by constructor injection.
- **Transport containment.** No class outside `adapter.bravehttp..` may reference the
  bravehttp package or any JDK `java.net.http` type; transport types never leak inward or
  sideways.
- **Process exit ownership.** Only `bootstrap.Main` calls `System.exit`. The `execute`
  method returns an exit code without terminating the JVM, so behavior is testable
  in-process.
- **Standard-stream ownership.** Only CLI presentation classes and bootstrap access
  `System.out`/`System.err`; tests inject writers instead. Standard input is owned the same
  way from the other side: only `bootstrap` (which wires the process stream) and
  `adapter.config` (whose secret reader receives it by injection) may take `System.in`. A
  single injected `TerminalDetector` (`adapter.cli.presentation`) is the only component that
  may inspect console/TTY state, and it supplies booleans to presentation code; it never
  reads passwords.
- **Environment and console ownership.** Only `adapter.config` and the single injected
  `TerminalDetector` may call `System.getenv` or `System.console`, and only `adapter.config`
  reads the API-key variable. The adjacent seams are confined the same way: system
  properties are touched only by `bootstrap`, `adapter.config`, and the `TerminalDetector` —
  reads through `System.getProperty`/`System.getProperties` and through the `java.lang`
  convenience lookups (`Boolean.getBoolean`, `Integer.getInteger`, `Long.getLong`) as well as
  writes through `System.setProperty`/`System.setProperties`/`System.clearProperty`, because
  a property write mutates the same process-visible configuration the seam exists to own;
  and the credential-source class that owns the two API-key variable names is referenced
  only inside `adapter.config` (the bootstrap roots inject its process lookup, nothing
  more) — a compile-time `String` constant is inlined at its use sites, so the executable
  form of that confinement is the type boundary. No other class observes the process
  environment or its property-visible configuration.
- **Library isolation.** `domain..` and `application..` use neither picocli nor Jackson;
  those technologies exist only in adapters, at the public API boundary, and in bootstrap.
- **Acyclicity.** Packages are cycle-free; slices of the package hierarchy may not depend on
  each other circularly, and the same holds one level finer: no two top-level classes of a
  single package depend on each other cyclically. A class and its own nested types (its
  builders and variants) are one type's implementation, not a cycle — a nested type's
  dependencies are attributed to its top-level owner, so a cycle routed through a nested
  type is as much a cycle as a direct one.
- **Public API surface.** Classes in the public `api` package may reference only `api..`,
  `domain..`, JDK types, and the explicitly allowlisted exported types (a constant set
  consumed by the test). Internal packages (`api.internal..`, `bootstrap..`, `adapter..`) are
  documentation-only exclusions from the public API unless a `module-info.java` is added
  later.
- **No reflection.** No production package uses reflection APIs directly. All wiring is
  explicit constructor calls in the composition roots, which also keeps the native image
  configuration honest.
- **Signal-handler containment.** Only `bootstrap.Main` touches the `sun.misc` signal
  machinery: the process-wide INT and TERM latches are composition, not library or command
  behavior, as ratified in `docs/adr/0005-stream-cancellation.md`. No other class may
  register or handle OS signals.
- **No static mutable state.** No production class holds mutable state in a static field:
  static fields are immutable configuration, and process-wide state lives in instances the
  composition roots own. The executable rule rejects any static field that is not final and
  any static final whose type is one of the JDK's mutable containers or atomics, or one of
  its stateful service types — executors, timers, random sources, scanners — because a
  static executor is a service locator in disguise however final its field (a static
  final array is constant configuration the code only ever reads, and an
  interface-declared immutable constant such as `List.of(...)` is judged by its immutable
  value). The one historical cache (the product User-Agent line) computes eagerly into a
  static final, so no synchronized lazy static remains anywhere.
- **Mapper containment.** Jackson mappers are constructed and acquired only by the three
  role codecs of the two-codec policy below — the machine-document mapper, the tolerant
  upstream codec, and the credential-file codec. Every acquisition path counts as
  construction: the builder factories, the public `JsonMapper`/`ObjectMapper` constructors,
  `rebuild`, and the JVM-global `JsonMapper.shared()` (a fourth codec's settings by another
  name), constructor and method references included. Everything else parses through a role
  codec,
  because parser settings on a private mapper can silently change what a role's documents
  mean.

## Composition roots

There are exactly two places where concrete adapters are instantiated:

- **`bootstrap.Main`** — the CLI process root. Builds the adapters, injects them into the
  picocli command tree, installs the process-wide INT and TERM signal handlers for live
  exchanges (see `docs/adr/0005-stream-cancellation.md`), and owns process exit: each
  handler latches its cause on every live exchange and lets normal command flow decide the
  exit code, while a signal arriving with no live exchange terminates the process itself
  with that signal's conventional status, so no user signal is ever absorbed.
- **`api.internal.Assembler`** — the library root. `assemble(...)` instantiates the
  concrete gateways over one shared transport and returns a plain wiring record of
  functions that the public `BraveSearchClient.Builder.build()` unpacks into the facade,
  so library consumers never import an adapter and neither package depends on the other
  in reverse. The design and rationale for this split are recorded in
  `docs/adr/0001-public-java-api-shape.md`; the durable public contract lives in
  `docs/java-api.md`. The streaming decode is the one wiring both roots share:
  `api.internal.StandardSemanticStream.standard()` assembles the SSE parser, the tag
  decoder, and the processor at their documented ceilings, and each root installs exactly
  that factory, so the CLI process and the embedded library decode the same bytes into
  the same events by construction — an executable equivalence test drives both roots
  against one identical scripted stream.

## Two-codec Jackson policy

Exactly two machine-document Jackson codecs exist, each an immutable, role-specific mapper:

- **Tolerant upstream decoding** lives in `adapter.bravehttp.json`. It parses whatever the
  server actually sent — unknown fields, drift, ordering — into the lossless upstream tree,
  and retains trailing-token detection so malformed bodies fail instead of being silently
  truncated.
- **Stable schema-versioned output encoding** lives in `adapter.cli.presentation.json`. It
  writes CLI machine output with explicit field order; its settings cannot change envelope
  output.

One further private mapper exists outside that pair: the strict v1 credential-file codec inside
`adapter.config` (`JsonConfigFile`), whose bounded, duplicate- and trailing-token-rejecting
parser settings apply only to the local config document. Mapper construction and acquisition
— the public `JsonMapper`/`ObjectMapper` constructors, the builder factories, `rebuild`, and
the JVM-global `JsonMapper.shared()` — is confined to exactly these three role classes by an
executable architecture rule, and the bravehttp stream pieces — SSE chunk payloads,
documented-tag payload validation, the web continuation probe, bounded error bodies — parse
through the tolerant upstream codec
(`adapter.bravehttp.json.UpstreamJsonCodec`) instead of building private mappers, so parser
settings on any codec can never affect another role's documents. The documented-tag payload
judgment is the codec's strict exactly-one-document reader — the same setting whole bodies
satisfy. The public `JsonNode` upstream snapshot is not
a fourth codec: the public `JsonNode` upstream snapshot is consumed from the tolerant
upstream codec (`adapter.bravehttp.json.UpstreamJsonCodec`), which the composition injects
into the facade; the deep copy per `upstream()` call happens at the API boundary only.
`domain` and `application` never touch Jackson.

## Failure model and exit codes

Expected failures flow through `domain.error.Outcome.Failure`, which always carries a
`FailureKind` and a redacted diagnostic. Unexpected programming errors remain exceptions.
The mapping below is implemented, not aspirational: `adapter.cli.exit` owns the kind-to-code
mapper and the signal statuses of the contract's failure-precedence order, in agreement with
the exit table of `docs/cli-contract.md`. The CLI maps
each kind to a stable exit-code category:

| `FailureKind` | Exit code | Meaning |
| --- | --- | --- |
| `USAGE` | 2 | command usage or local validation error |
| `LOCAL_CONFIG` | 3 | missing or unsafe local configuration/credential |
| `AUTHENTICATION` | 4 | authentication, permission, payment, or entitlement failure |
| `RATE_LIMITED` | 5 | rate limited |
| `TRANSPORT` | 6 | network, DNS, TLS, connection, timeout, or output I/O failure (a downstream broken pipe is a silent success instead) |
| `UPSTREAM` | 7 | other Brave 4xx/5xx API failure |
| `MALFORMED` | 8 | malformed, oversized, or unsupported upstream response |
| `INTERNAL` | 70 | unexpected internal software error |

`0` marks success (including zero results and silent broken-pipe early consumer
termination); `130` marks interruption by SIGINT and `143` termination by SIGTERM, both of
which the process bootstrap handles through the cancellation registry rather than a failure
kind. A failure's diagnostic is always
redacted; a bounded exact upstream error body, where one exists, is carried separately from
the public diagnostic.

## Cancellation ownership

Cancellation state is owned by the composition roots, not by statics. Each streaming exchange
holds a `CancellationContext` that owns first-terminal-cause latching — SIGINT, SIGTERM,
deadline expiry, broken pipe, subscriber or transport failure, or explicit close all funnel
through it — and the registry of live contexts is a non-static, instance-owned structure
(`bootstrap.ExchangeRegistry`, implementing `application.stream.CancellationRegistry`) that
exchanges join on open and leave on close. The single-request spine registers its exchange's
context the same way, and its transport observes the latch: a signal latched mid-send or
mid-body-read unblocks the blocking exchange and fails it as cancelled, so the run renders
its failure document and exits by the signal instead of waiting out its budgets.
`bootstrap.Main` installs exactly one INT and one
TERM signal handler before the command line runs: each latches its cause on every live
context, wait-free, and normal control flow finishes the run while `Main.main` exits with
the cause-derived code (130 for SIGINT, 143 for SIGTERM; the first cause latched wins). A
signal arriving while no context is live has nothing to latch, so its handler terminates the
process itself with that signal's conventional status — the same 130 or 143 a latched run
exits by — instead of absorbing the signal. This mechanism, and the
disproof of the earlier shutdown-hook design on the native runtime, are recorded in
`docs/adr/0005-stream-cancellation.md`. No static mutable field anywhere holds cancellation or
any other global state; there is no service locator.
