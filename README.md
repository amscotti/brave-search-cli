# brave-search

A command line client and Java library for the Brave Search API.

Results print as concise human text by default. Pass `--output json` or
`--output jsonl` when a script or agent needs to parse them. The CLI covers
the documented Brave Search endpoints — web, news, videos, images, suggest,
spellcheck, local places, LLM context, rich-result callbacks, and grounded
Answers — and validates options locally before it spends a request. The same
module publishes `io.amscotti:brave-search-client` for JVM applications.

It stores nothing but the optional credential file: no result cache, no query
history, no telemetry.

## Install

The build uses the Java 25 toolchain pinned by [mise](https://mise.jdx.dev/)
in `mise.toml`. Clone the repository and install the JVM launcher:

```console
# illustrative: clones the repository and builds the JVM launcher
$ git clone https://github.com/amscotti/brave-search-cli.git
$ cd brave-search-cli
$ env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain installDist
```

The launcher is `build/install/brave-search/bin/brave-search`. Put that `bin`
directory on your `PATH`, or invoke the script by path.

A native executable for the machine you build on lands at
`build/native/nativeCompile/brave-search`:

```console
# illustrative: builds the native executable for this machine
$ env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain nativeCompile
```

```console
# runnable: launcher
$ brave-search --version
$ brave-search --help
$ brave-search completion bash
$ brave-search completion zsh
```

## API key

Create a Brave Search API key in the [Brave Search API
dashboard](https://api-dashboard.search.brave.com/app/documentation). This
tool never creates, requests, or validates a key for you.

**Never pass the key as a command line argument** — arguments land in shell
history and process listings. There is no `--key` or `--api-key` flag.

Credentials are resolved in this order:

1. `BRAVE_API_KEY` (preferred).
2. `BRAVE_SEARCH_API_KEY` (compatibility alias).
3. The local config file written by `brave-search config set-key`.

A source that is set but invalid is a configuration error. It is never
skipped in favor of a lower-precedence source.

```console
# runnable: launcher
$ brave-search config --help
```

Store the key interactively (typed with echo disabled), or pass exactly one
line on stdin in console-less contexts:

```console
# illustrative: waits on the reader's console and writes the credential file
$ brave-search config set-key
```

```console
# illustrative: reads the reader's stdin and writes the credential file
$ printf '%s\n' "$KEY" | brave-search config set-key --stdin
```

Inspect what is in effect without printing the key:

```console
# illustrative: output depends on the reader's stored configuration
$ brave-search config show
```

`brave-search config unset-key` removes a stored key. If the file's
permissions have drifted, `brave-search config repair-permissions` resets
the credential file to `0600` and its directory to `0700`.

On Linux the config file lives at
`${XDG_CONFIG_HOME}/brave-search/config.json` when that variable is set,
nonblank, and absolute, and otherwise at
`~/.config/brave-search/config.json`. On macOS it lives at
`~/Library/Application Support/brave-search/config.json`. Writes are atomic
with owner-only permissions. The full contract is
[docs/config-security.md](docs/config-security.md).

## Examples

Quote queries. Use `--` when a query starts like an option. Every command's
`--help` lists its options.

| Command | Use it for |
| ------- | ---------- |
| `web`, `news`, `videos`, `images` | Search those verticals |
| `suggest`, `spellcheck` | Complete or correct a query |
| `places search` | Local places, anchored by `--location` or coordinates |
| `places details`, `places describe` | Enrich place ids from a search |
| `answers` | A grounded, cited answer to one question |
| `context` | LLM-ready passages for one query |
| `rich` | Live rich results of an earlier `--enable-rich-callback` web search |
| `config` | Store or inspect the API key |

```console
# runnable: launcher
$ brave-search web --help
$ brave-search news --help
$ brave-search videos --help
$ brave-search images --help
$ brave-search suggest --help
$ brave-search spellcheck --help
$ brave-search places --help
$ brave-search rich --help
$ brave-search answers --help
$ brave-search context --help
```

```console
# illustrative: live exchange against the Brave API
$ brave-search web "heat pump efficiency"
$ brave-search news --freshness pd "central bank interest rate"
$ brave-search videos "linear algebra lecture"
$ brave-search images "glass frog"
$ brave-search suggest "vertex sha"
$ brave-search spellcheck "recieve mail"
$ brave-search places search --location "seattle wa us" coffee
$ brave-search answers "how does a heat pump work"
$ brave-search context "rust async executor comparison"
$ brave-search web --output json "heat pump efficiency"
```

`--output json` writes one document; `--output jsonl` writes one record per
line. The shapes, exit codes, and option grammar live in
[docs/cli-contract.md](docs/cli-contract.md).

## Java library

Group `io.amscotti`, artifact `brave-search-client`. The library is not
published to a registry yet: publish it locally and depend on the project's
version (`0.1.0-SNAPSHOT` unless you pass `-PreleaseVersion`):

```console
# illustrative: publishes the library to the local Maven repository
$ env -u GRAALVM_HOME mise exec -- ./gradlew --no-daemon --console=plain publishToMavenLocal
```

```groovy
// Gradle
dependencies {
    implementation "io.amscotti:brave-search-client:0.1.0-SNAPSHOT"
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.amscotti</groupId>
    <artifactId>brave-search-client</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Requires Java 25 at runtime. The library never reads the environment — the
token supplier is the only credential source:

```java
try (BraveSearchClient client = BraveSearchClient.builder()
        .tokenSupplier(() -> Credential.of(token.getBytes(StandardCharsets.UTF_8)))
        .build()) {
    Outcome<WebSearchResponse> outcome =
            client.webSearch(WebSearchRequest.builder("bacon").count(10).build());
    switch (outcome) {
        case Outcome.Success<WebSearchResponse> success ->
            System.out.println(success.value().upstream().path("web").path("results").size());
        case Outcome.Failure<WebSearchResponse> failure ->
            System.err.println(failure.kind() + ": " + failure.diagnostic());
    }
}
```

Blocking endpoints return `Outcome<ItsResponse>` with a lossless,
caller-owned `JsonNode` upstream snapshot. The durable contract is
[docs/java-api.md](docs/java-api.md).

## Documentation

| Document | Contents |
| -------- | -------- |
| [docs/architecture.md](docs/architecture.md) | Ports-and-adapters layout, package rules, failure model, cancellation ownership. |
| [docs/brave-api-contract.md](docs/brave-api-contract.md) | Wire protocol, endpoint wire forms, response bounds, SSE transport, error classification. |
| [docs/cli-contract.md](docs/cli-contract.md) | Exit codes, option grammar, output modes, per-command schemas, pagination. |
| [docs/config-security.md](docs/config-security.md) | Credential precedence, secure config writes, refusal conditions, residual threat model. |
| [docs/extending.md](docs/extending.md) | Test-layer taxonomy, shared harnesses, fixture policy, adding an endpoint command. |
| [docs/java-api.md](docs/java-api.md) | Library coordinates, builder contract, streaming surface, compatibility policy. |
| [docs/release.md](docs/release.md) | Artifact layout, checksums, SBOM scope, live protocol smoke, CI matrix. |
| [docs/upstream-contract-2026-08-30.md](docs/upstream-contract-2026-08-30.md) | Dated, checked-in Brave API reference with provenance hashes. |
| [docs/adr/](docs/adr/) | Decision records: API shape, JSON envelope, release matrix, secret storage, stream cancellation. |
| [docs/contract-index.yaml](docs/contract-index.yaml) | Every stable requirement mapped to its document section and pinning test. |
| [skills/brave-search/SKILL.md](skills/brave-search/SKILL.md) | The distributable agent skill for this CLI. |

## For agents

If you are an AI agent setting this up for your user, the flow is
non-interactive:

1. **Install.** Clone `https://github.com/amscotti/brave-search-cli.git`,
   build with the pinned mise toolchain as above, and put the launcher on
   `PATH`. Confirm with `brave-search --version`.
2. **Credential.** Get the Brave API key from the user's environment or
   secret store — never ask them to paste it into chat when an env var will
   do, and never pass it as a command argument. Export it as `BRAVE_API_KEY`
   (`BRAVE_SEARCH_API_KEY` also works). The CLI reads no `.env` files on its
   own, so source one first if that is where the key lives. Confirm with
   `brave-search config show`, which names the effective source without
   printing the key.
3. **Operate.** Always pass `--output json` (one document) or `--output jsonl`
   (one record per line) and never parse the human text. Decide on the exit
   code first, then read results from `data.projection`.
4. **Read the manual.** `skills/brave-search/SKILL.md` is the operating
   contract — research playbooks, quota discipline, and the failure table —
   and `schemas/v1` has the machine-output schemas. Start there before
   improvising.

## License

[Apache License 2.0](LICENSE)
