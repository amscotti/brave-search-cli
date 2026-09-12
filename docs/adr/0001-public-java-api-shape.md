# ADR 0001: Public Java API shape

- Status: Accepted
- Date: 2026-08-30
- Verified against the web tracer: 2026-08-31 (see Verification)

## Context

This repository produces two artifacts from one code base: the `brave-search` CLI and the
`brave-search-client` library published as `io.amscotti:brave-search-client`. Library consumers
embed Brave Search into their own applications; the CLI is one such consumer that additionally
owns credential resolution precedence, presentation, and exit codes.

The public API design had to resolve several forces:

- **Endpoint growth.** Brave exposes distinct endpoints (web search, an answers endpoint with
  streaming, and others over time). A union request object or a generic gateway hierarchy would
  let one endpoint's evolution churn every other endpoint's types and tests.
- **Composition without leakage.** Concrete adapters must be instantiated somewhere, but
  library consumers must never need to import an HTTP transport class to get a working client.
- **Upstream fidelity.** Brave's response schema evolves server-side; callers need a lossless
  view of what the server actually sent, not only the projection this project models.
- **Credential ownership.** The CLI resolves the API token from the environment and the secure
  config file. A library must not silently inherit a CLI's environment conventions.
- **Streaming machinery.** Streaming involves exchanges, frame types, and cancellation
  contexts with subtle lifecycle rules; publishing all of them would freeze internal design
  details into the API.
- **Internals without a module system.** The artifact is a plain library JAR; there is no
  `module-info.java` today, so "internal" needs a defined meaning.

## Decision

1. **Endpoint-specific application ports.** Each endpoint gets a small, dedicated outbound
   port in `application.port.out` with endpoint-specific request and result types in `domain`
   (today: `WebSearchPort` with `WebSearchRequest`/`WebSearchResult`). No union request object
   and no shared gateway hierarchy is introduced. Endpoints that need richer interaction
   (such as streaming answers) get their own gateway type; combining endpoints happens at the
   facade, not in the type system.

2. **A small `BraveSearchClient` facade.** `io.amscotti.bravesearch.api.BraveSearchClient`
   exposes one method per endpoint (today: `webSearch(WebSearchRequest)`), each delegating to
   the corresponding port. Its `Builder` collects collaborators and is the library-side
   composition root. The facade adds no behavior of its own.

3. **`api.internal` is the internal composition package.** `api.internal.Assembler`
    instantiates the concrete adapters and wires them into the facade builder: the public
    `Builder.build()` calls the assembler, which returns a plain wiring record (functions
    over domain types only) that the builder unpacks into the facade constructor, so
    neither package depends on the other in reverse and the package hierarchy stays
    cycle-free. The public `api` package never imports an adapter directly: it may
     reference only `api..`, `domain..`, JDK types, and a constant allowlist of exported
     types (as finalized: only the two Jackson boundary types — an earlier shape leaked
     the internal `AnswersStreamExchange` and `CancellationContext` through the facade's
     composition constructor, and that leak was repaired before the version-one freeze;
     see Finalized), whose normative home is the
     exported-types section of `docs/java-api.md`. The executable architecture test
     enforces both this allowlist and the fact that only `bootstrap..` and `api.internal..`
     may instantiate concrete adapters.

4. **Typed projections plus a caller-owned upstream snapshot.** Public responses are typed
   projection records, and each also offers `JsonNode upstream()` returning a deep-copied,
   caller-owned snapshot of the lossless upstream tree. The client never mutates returned
   trees and never retains them. This makes Jackson (the `tools.jackson.databind.JsonNode`
   type) an intentional public `api`-scope dependency of the published library — declared with
   Gradle `api` configuration, not `runtimeOnly` — while `domain` and `application` stay
   Jackson-free. The snapshot is materialized only at the API boundary by `api.internal`,
   using the tolerant upstream codec owned by `adapter.bravehttp.json`, so exactly two Jackson
   codecs exist in the code base and the public API never owns a third.

5. **The public builder requires an explicit token supplier.** Callers must provide how the
   token is obtained; the library never applies the CLI's environment-variable/config-file
   precedence and never reads the process environment on a consumer's behalf. Embedding that
   policy would couple library semantics to CLI conventions and make behavior
   host-environment-dependent.

6. **Streaming is a projected publisher plus a public cancellation handle.** The public
   streaming surface is a `Flow.Publisher<PublicStreamFrame>` — a public projection type —
   together with a public cancellation handle. The application-level `AnswersExchange`,
   `StreamFrame`, and `CancellationContext` remain internal: public consumers can observe
   projected frames and request cancellation, but cannot reach the underlying HTTP
   subscription or exchange lifecycle. Publishers exposed this way are cold and
   single-subscription, honor demand, never invoke callbacks concurrently, map cancellation
   to the underlying HTTP subscription, and signal terminal failure exactly once.

7. **Internal packages are documentation-only exclusions.** `api.internal..`, `bootstrap..`,
   and `adapter..` are excluded from the public API by documentation and by the architecture
   test's allowlist, not by runtime enforcement. If a `module-info.java` is added later, these
   packages become inaccessible through module boundaries; until then, consumers who reach
   into them forfeit compatibility guarantees.

## Consequences

- Positive: endpoints evolve independently; adding an endpoint never perturbs existing
  endpoints' types. Test doubles implement one small port each. Callers get lossless upstream
  access without importing transport types, and snapshots are safe to mutate freely.
- Cost: a new endpoint touches several small places (request, result, port, facade method,
  assembler wiring, allowlist entry). This deliberate ceremony is accepted in exchange for
  stable per-endpoint types and a mechanically checked public surface.
- Jackson rides on every consumer's compile classpath because of the `api` scope, including
  consumers who never call `upstream()`. Accepted: lossless access was judged worth the
  dependency, and hiding it behind a wrapper type would create a third codec.
- The exported-type allowlist is intentionally a small constant set; extending it is a
  deliberate act that fails the architecture test until the documentation and the constant
  agree.
- Streaming internals can be refactored (backpressure strategy, exchange lifecycle) without
  breaking public consumers, because only the projection and cancellation handle are public.
- Lifecycle ownership follows the composition: `BraveSearchClient` implements
  `AutoCloseable` and closes the closeable ports it holds, so the default assembled client
  releases its HTTP resources on close while a caller-injected port keeps its own contract.
  Closing is idempotent, and use after close reports the port's own failure outcome (the
  fixed internal status), never an unchecked exception. The CLI composition follows the same
  rule per invocation: each dispatch's gateway, transport, and HTTP client close when the
  exchange completes.

## Verification

Verified against the web tracer (2026-08-31): the first whole-process web search slice — the
JVM start script and the native binary, each driven against a scripted loopback server —
exercised every decision this record makes about the executed path.

- Endpoint-specific ports held: the slice ran end to end through `WebSearchExchange` with its
  dedicated request/result types, and no union type or shared gateway appeared or was missed.
- Composition stayed where decision 3 puts it: `bootstrap` wired the CLI run, the architecture
  rules still bar every other package from instantiating adapters, and the tracer needed no
  exception.
- Upstream fidelity held through the whole process, not only in unit tests: an exact-scale
  decimal (`0.1000000000000000000001`) and a trailing-zero decimal (`1.10`) served on the wire
  surfaced byte-for-byte in the CLI's machine documents. The projection of that exchange
  materialized as `result_count`, `page`, `upstream_offset`, and `results` — the count/page/
  results shape this record anticipated.
- The `JsonNode`-at-API-boundary decision (4) remains deferred to library publication: the
  tracer is a CLI slice, the public `api` facade is not yet consumed end to end, and no
  evidence diverges from the decision. No superseding rationale is warranted.

## Finalized

The library surface shipped, and this record now describes the as-built shape (see
`docs/java-api.md`, the durable contract):

- The facade exposes one method per endpoint — the eleven blocking endpoints plus
  `answers` (blocking) and `answersStream` — over the endpoint-specific domain request
  records, which carry their own builders and construction-time validation; the answers
  request builder additionally grew the `userId` convenience for the documented
  `metadata.user_id` rider. No api-side request wrappers were added: the domain records
  already are the endpoint-specific public request types, and wrapping them would
  duplicate their validation for no contract gain.
- Public responses are endpoint-specific classes carrying the typed exchange metadata and
  a privately held lossless tree, whose `upstream()` returns a fresh `JsonNode.deepCopy()`
  per call — pinned by tests proving two snapshots never observe each other's mutations,
  and shaped so no tree-holding type appears in the public surface (the tree holder began
  as a public record component and was demoted to a private field before the version-one
  freeze). The parse
  happens once per successful exchange through the tolerant upstream codec owned by
  `adapter.bravehttp.json`, injected into the facade by the composition; the facade never
  owns a codec, and a body that is not exactly one JSON document is the `MALFORMED`
  outcome rather than a broken tree.
- The public streaming surface settled as decided: `AnswersStreamHandle` exposes the
  memoized `Flow.Publisher<PublicStreamFrame>` and a public `StreamCancellation`
  (`cancel()`/`cancelled()`), while `AnswersExchange`, the internal publishers, and
  `CancellationContext` stay internal. `PublicStreamFrame` is sealed over `Text`,
  `Tagged`, and `UnknownTag` records whose payload text is the exact tag payload, never a
  re-encoded JSON view — passthrough framing noise is not projected at all. The `Tagged`
  frame's earlier String-only rationale was superseded by the `payloadTree` decision: the
  frame also carries the payload parsed as one JSON document through the api-boundary codec,
  which reuses the snapshot path's codec rather than widening the public Jackson surface
  past the two exported boundary types.
- The public builder requires an explicit `tokenSupplier`, never applies environment or
  config precedence, and offers the documented budgets, a caller-owned executor option,
  and a loopback-only `baseUrl` override whose origin routing can never move credentials
  off the production origin. `close()` cancels live streams, closes the shared transport,
  shuts the owned executor down, and leaves caller-supplied executors alone.
- The exported-types allowlist moved to `docs/java-api.md` as its normative home; the
  architecture rule and that list change together by construction.
- Finalized before the version-one freeze, with superseding rationale — the public
  composition constructor leaked internal types: it took the streaming exchange function
  as `Function<AnswersRequest, Outcome<AnswersStreamExchange>>`, which kept
  `AnswersStreamExchange` and the `CancellationContext` latch the handle wrapped in the
  exported allowlist and in the public signature. The composition now reduces each opened
  exchange to plain functions over domain and JDK types (`api.internal.OpenedAnswersStream`)
  and the public constructor takes
  `Function<AnswersRequest, Outcome<AnswersStreamHandle>>`; the client tracks returned
  handles by identity, handles detach from their client when the stream terminates
  naturally (completion or failure releases the client's reference; consumers still close
  every handle), and the allowlist is exactly the two Jackson boundary types. Tagged
  frames' parsed payload tree is empty when the payload is not exactly one JSON document
  (parse-or-empty), and `AnswersStreamException.kind()` exposes the stable failure
  category of a stream failure.
