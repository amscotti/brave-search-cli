# The public Java API of brave-search-client

This document is the durable contract of the published library: the coordinates, the
builder contract, the request and response surfaces, the streaming surface, the threading
and timeout model, the snapshot semantics, and the compatibility policy. Its
exported-types section is the normative source the executable architecture allowlist
(rule 11 of `ArchitectureTest`) mirrors — the two are kept in deliberate agreement.

## Coordinates and artifact shape

- Group `io.amscotti`, artifact `brave-search-client`, the project's version.
- Not yet published to a registry: consume the JARs from a release archive's `lib/`
  directory, or publish them locally with
  `env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain publishToMavenLocal`
  and depend on the coordinates (substitute the version):

```groovy
// Gradle
dependencies {
    implementation "io.amscotti:brave-search-client:1.2.0"
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.amscotti</groupId>
    <artifactId>brave-search-client</artifactId>
    <version>1.2.0</version>
</dependency>
```

- A plain library JAR plus `-sources.jar` and `-javadoc.jar`.
- `tools.jackson.core:jackson-databind` is published in `compile` (api) scope: the
  `JsonNode` returned by every `upstream()` is part of the public surface, so Jackson
  rides every consumer's compile classpath by design.
- Requires Java 25 at runtime.
- Release packaging verifies the artifact: JAR contents, sources, generated docs, and the
  pom facts (name, description, license) are checked by the `verifyLibraryPublication`
  task; the independent `sample-consumer` project compiles and runs against only the
  published artifact through the `sampleConsumerCheck` task.

## Quick start

```java
try (BraveSearchClient client = BraveSearchClient.builder()
        .tokenSupplier(() -> Credential.of(token.getBytes(StandardCharsets.UTF_8)))
        .build()) {
    Outcome<WebSearchResponse> outcome = client.webSearch(WebSearchRequest.builder("bacon").count(10).build());
    switch (outcome) {
        case Outcome.Success<WebSearchResponse> success -> {
            System.out.println(success.value().httpStatus());
            System.out.println(success.value().upstream().path("web").path("results").size());
        }
        case Outcome.Failure<WebSearchResponse> failure ->
            System.err.println(failure.kind() + ": " + failure.diagnostic());
    }
}
```

## Builder contract

`BraveSearchClient.builder()` is the supported composition entry; its `build()` delegates
to `api.internal.Assembler`, which instantiates the concrete adapters, so the public
package never imports an adapter and consumers never need a transport type to get a
working client.

- `tokenSupplier(Supplier<Credential>)` — **required**. The library never reads the
  environment and never applies any configuration-file precedence on a consumer's behalf:
  this supplier is the only credential source. It is resolved once per exchange; a
  supplier that yields `null` or throws makes that exchange report the
  `LOCAL_CONFIG` failure, never an environment lookup. Credentials are opaque
  bytes; `Credential.of` validates their shape, and a token carrying any character above
  U+00FF — outside the ISO-8859-1 alphabet an HTTP header value can carry — is rejected
  the same way, as a `LOCAL_CONFIG` failure, before any exchange is dispatched.
- `connectTimeout(Duration)` — the connection-establishment budget of every exchange.
  Default 10 seconds.
- `totalTimeout(Duration)` — the non-streaming total budget, dispatch through the
  complete bounded response body, for every blocking endpoint. Default 30 seconds.
- `executor(ExecutorService)` — a caller-owned executor the client's HTTP machinery rides.
  `close()` never shuts it down. The default is a client-owned executor (daemon threads)
  that `close()` does shut down.
- `baseUrl(String)` — an advanced origin override for tests and fakes, accepted only as
  a literal loopback origin (http(s) on exactly `127.0.0.1`, `[::1]`, or `localhost`, with
  an optional port, no user information, no query, no fragment, and an empty or slash
  path). The default is the production origin `https://api.search.brave.com/res/v1`. The
  origin's own routing rule can never move the supplied credential off the production
  origin, so an override can never exfiltrate credential material. The library ships no
  proxy configuration: connections go directly to the origin, and the loopback-only
  override is not a proxy seam.

Streaming budgets ride the request itself (`AnswersRequest.idleTimeout` /
`streamTimeout`); when a streaming request pins none, the exchange's documented defaults
apply: a 60-second idle window (300 for research), a headers-phase ceiling of 30 seconds,
and — for research runs that pinned their research seconds — a wall budget of those
seconds plus a 30-second grace; ordinary streams carry no default wall budget.

## Requests

Every endpoint takes its own fully validated immutable request record from
`io.amscotti.bravesearch.domain.request`; there is no union request type. Most records
carry a builder (`WebSearchRequest.builder(query)`, `AnswersRequest.builder(question)`,
…); the small ones are constructed directly (`RichRequest`, `PlaceEnrichmentRequest`).
Validation happens at construction, so an invalid request can never reach the wire.

| Facade method | Request type | Notes |
| --- | --- | --- |
| `webSearch` | `WebSearchRequest` | |
| `llmContext` | `ContextRequest` | the LLM-context endpoint |
| `newsSearch` | `NewsSearchRequest` | |
| `videoSearch` | `VideoSearchRequest` | |
| `imageSearch` | `ImageSearchRequest` | |
| `suggest` | `SuggestRequest` | |
| `spellcheck` | `SpellcheckRequest` | |
| `placeSearch` | `PlaceSearchRequest` | |
| `placeDetails` | `PlaceEnrichmentRequest` | must carry the `DETAILS` kind |
| `describePlaces` | `PlaceEnrichmentRequest` | must carry the `DESCRIPTIONS` kind |
| `rich` | `RichRequest` | the rich-result callback endpoint |
| `answers` | `AnswersRequest` | blocking; must not set `stream` |
| `answersStream` | `AnswersRequest` | must set `stream(true)` |

The Answers request builder carries a `userId(String)` convenience for the documented
`metadata.user_id` rider; it merges into the request's metadata map, and a later call
wins whenever `metadata(Map)` and `userId` both write the key.

## Responses

Every blocking endpoint returns `Outcome<ItsResponse>`: `Outcome.Success` carries the
endpoint's response record, `Outcome.Failure` carries a `FailureKind`, a redacted
diagnostic, and — when a bounded error body existed — the structured upstream error facts.
Failure kinds are stable: `USAGE`, `LOCAL_CONFIG`, `AUTHENTICATION`, `RATE_LIMITED`,
`TRANSPORT`, `UPSTREAM`, `MALFORMED`, `INTERNAL`.

Each response class exposes the same typed exchange metadata — `httpStatus()`,
`rateLimits()` (never null), `usage()`, `requestId()`, `apiVersion()` (each of the last
three null when the response headers offered none) — plus the upstream snapshot below.
The response types are endpoint-specific on purpose: one endpoint's evolution never
perturbs another's type. The upstream tree is a private field; `upstream()` is the only
public surface of the exchange's body, responses compare structurally over their metadata
and upstream tree — the comparison, hash, and debug rendering read the privately held
tree directly and never snapshot it, so hashing a response costs no copy of the body —
and no tree-holding type appears in the public shape.

### The `upstream()` snapshot

- `upstream()` returns a `JsonNode` that is a **fresh deep copy** of the lossless
  upstream body: every call returns an independent, caller-owned tree, the client never
  observes a caller's mutation, and two snapshots of the same response never share state.
- The tree was parsed tolerantly from the exact bounded bytes the server sent — unknown
  members and arbitrary structure ride along unchanged — with decimals at their exact
  scale, so numbers like `0.1000000000000000000001` or `1.10` round-trip value-exactly.
- A success outcome guarantees the body parsed as exactly one JSON document; a body that
  does not is reported as the `MALFORMED` failure, never as a broken tree or an
  exception.

## Streaming Answers

`answersStream(request)` performs the (blocking) open of one streaming exchange and
returns `Outcome<AnswersStreamHandle>`. The handle exposes:

- `frames()` — the same `Flow.Publisher<PublicStreamFrame>` on every call. It is cold and
  single-subscription: the first subscriber gets the live stream, a second subscriber is
  refused through reactive-streams conventions with an `IllegalStateException`. Demand
  travels through verbatim — one requested item delivers one projected frame; framing
  noise that carries no answer content consumes its pull on a transparent refill instead
  of a delivery, and the refills drain iteratively, so an arbitrarily long noise run under
  one demand unit costs no recursion depth. Subscriber callbacks are strictly serialized
  and never concurrent. Terminal success arrives as `onComplete`; terminal failure arrives
  exactly once as `onError` carrying `AnswersStreamException`, whose redacted diagnostic
  never leaks the internal cause and whose `kind()` returns the same stable `FailureKind`
  vocabulary the blocking endpoints use, so consumers can categorize stream failures
  programmatically. A subscriber that arrives before any live subscription but after the
  stream already terminated — the handle was closed up front — receives the terminal
  signal immediately instead of hanging; a consumer-closed stream replays as completion,
  not a failure. Cancellation (`Subscription.cancel`, the handle's cancellation, or
  `close()`) releases every resource the exchange owns and signals nothing further.
- `cancellation()` — the public `StreamCancellation`: `cancel()` latches the closed cause
  and ends the exchange from any thread, idempotently; `cancelled()` reports whether any
  terminal cause has latched.
- `close()` — idempotent; detaches from the owning client, latches the closed cause, and
  releases the exchange's body, reader, and transport. **Handles must be closed — put
  them in try-with-resources — even after a natural termination**: the handle releases
  the client's reference as soon as its stream completes or fails, but only `close()`
  latches the closed cause and releases the exchange's resources.

`PublicStreamFrame` is a sealed projection of the decoded answer: `Text(text)` runs of
answer text (their concatenation is the full answer, exactly), `Tagged(tag, payload,
payloadTree)` documented Brave tags carrying their exact payload text plus the payload
parsed as one JSON document through the api-boundary codec — parse-or-empty: a payload
that is not exactly one JSON document yields Jackson's missing node, never an exception
or a broken tree — and `UnknownTag(name, rawText, sseName, sseId, sseRetryMillis)`
well-formed undocumented tags preserved with their event-stream envelope. Passthrough
chunks — role deltas, tool-call deltas, finish markers, heartbeats — are not projected: a
public consumer observes the answer, not the transport.

A minimal consumer, mirroring the quick start:

```java
try (BraveSearchClient client = BraveSearchClient.builder()
        .tokenSupplier(() -> Credential.of(token.getBytes(StandardCharsets.UTF_8)))
        .build()) {
    Outcome<AnswersStreamHandle> opened = client.answersStream(
            AnswersRequest.builder("what is bacon").stream(true).build());
    switch (opened) {
        case Outcome.Success<AnswersStreamHandle> success -> {
            try (AnswersStreamHandle handle = success.value()) {
                CountDownLatch done = new CountDownLatch(1);
                StringBuilder answer = new StringBuilder();
                handle.frames().subscribe(new Flow.Subscriber<PublicStreamFrame>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(PublicStreamFrame frame) {
                        if (frame instanceof PublicStreamFrame.Text text) {
                            answer.append(text.text());
                        }
                        subscription.request(1);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        System.err.println("stream failed: " + failure.getMessage());
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });
                done.await();
                System.out.println(answer);
            }
        }
        case Outcome.Failure<AnswersStreamHandle> failure ->
            System.err.println(failure.kind() + ": " + failure.diagnostic());
    }
}
```

## Threading model

- Every endpoint method blocks the calling thread until the exchange completes or its
  budget elapses; `answersStream` blocks only for the open (headers) phase.
- Frames are delivered on the exchange's single reader thread; callbacks are never
  concurrent, per the reactive-streams rules above.
- The client is safe for use from multiple threads; the token supplier may be called from
  any calling thread and should be cheap and non-blocking.

## `close()` semantics

`close()` is idempotent and: cancels every live Answers stream (each handle closes
exactly once), closes the shared transport (and through it its HTTP client), and shuts
down the client-owned executor when the composition created one. A caller-supplied
executor is never shut down. A stream or resource whose release throws never strands the
others: every remaining release still runs and the first failure propagates from
`close()`. An `answersStream` open that spans a concurrent `close()` never leaks a stream:
the raced handle releases itself and the caller receives the `TRANSPORT` failure "the
client is closed". Use after close reports that same `TRANSPORT` failure for every
endpoint, never an exception.

## Compatibility policy

Semantic versioning covers the public source and binary API and the serialized shape of
the machine surfaces. The public API is exactly:

- everything in `io.amscotti.bravesearch.api` except `api.internal..`;
- the domain packages `io.amscotti.bravesearch.domain..` (requests, results, metadata,
  error kinds and `Outcome`);
- the exported application types named in the exported-types list below, as they appear
  in the public signatures;
- `tools.jackson.databind.JsonNode` and `tools.jackson.core.JacksonException` as they
  appear in the public signatures (the intentional public Jackson dependency).

Every other package in the artifact — `api.internal..`, `application..` beyond the named
exports, `adapter..`, and `bootstrap..` — is internal: a documentation-only exclusion,
not runtime-enforced, and consumers who reach into it forfeit compatibility guarantees.
If a `module-info.java` is added later, these exclusions become module boundaries.

### Exported types (rule 11 source of truth)

Classes outside `api..`/`domain..` that the public `api` package references, mirroring
the `API_EXPORT_ALLOWLIST` constant of `ArchitectureTest`:

- `tools.jackson.core.JacksonException` — caught at the boundary to map unparseable
  bodies to the malformed outcome
- `tools.jackson.databind.JsonNode` — the upstream snapshot type and the parsed tag
  payload tree

No application or adapter type is exported: the streaming composition crosses into the
public package as plain functions over domain and JDK types (the semantic-frames publisher,
the terminal-cause actions, and the release), so no internal exchange or cancellation type
appears in any public signature.

The architecture rule and this list change together, deliberately: adding an entry
without documentation fails the build's architecture gate, and documenting an entry
without the constant is a doc-only claim the gate does not honor.

## Sample consumer

The `sample-consumer` directory is an independent Gradle project that depends on nothing
but the published artifact (and its transitive Jackson): its suite proves the published
jar works end to end — web search against a loopback fake with a supplied token, upstream
snapshot independence, the blocking Answers call, the `metadata.user_id` rider reaching
the request wire, and the streaming Answers surface (projected text and tagged frames
with their parsed payload tree, the terminal signal, cancelling a live stream and
cancelling after natural completion, and a clean close). Run it through the root task
`sampleConsumerCheck`, which publishes to mavenLocal
first:

```console
env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain sampleConsumerCheck
```
