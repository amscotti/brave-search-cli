# CLI contract

Normative contract of the brave-search command line for major version 1: process exit
codes, failure precedence, the output-mode grammar and compatibility rules, warning
routing, and the machine document framings. Field inventories of the machine documents are
normative as JSON Schema under `schemas/v1/`; this file is the durable human-facing
reference, and `docs/contract-index.yaml` maps every stable requirement identifier to its
section here and to its executable test.

## Exit codes

| Code | Meaning |
| ---- | ------- |
| 0 | Success, including zero results, and silent early termination by a downstream broken pipe (EPIPE). |
| 2 | Command usage or local validation error. |
| 3 | Missing or unsafe local configuration or credential, or a secret cannot be read without a console. |
| 4 | Authentication, permission, payment, or plan entitlement failure. |
| 5 | Rate limited. |
| 6 | Network, DNS, TLS, connection, non-signal interruption, timeout, or non-EPIPE output-I/O failure. |
| 7 | Other Brave 3xx/4xx/5xx API failure (including 400, 404, 408, and non-auth-bearing 422). |
| 8 | Malformed, oversized, or unsupported upstream response. |
| 70 | Unexpected internal software error. |
| 130 | Interrupted by SIGINT. |
| 143 | Terminated by SIGTERM. |

A downstream broken pipe is the one output-I/O condition that counts as success: the
consumer terminated the stream early, so the process produces nothing further, cancels
upstream work, stays silent, and exits `0`. Every other output-write failure exits `6`.
SIGINT and SIGTERM each latch their signal's cause on every live run through the same
process handler and exit by their conventional statuses, 130 and 143, on both the JVM and
native executables; a signal arriving while no run is live terminates the process itself with
that same conventional status, so no user signal is ever absorbed. Exit-code meanings are
stable within CLI major version 1.

An unexpected internal error — a crash no layer classified — prints exactly one redacted
stderr line, `brave-search: unexpected internal error (<exception simple name>)`, and exits
`70`: no stack trace, no internal class names beyond the exception's own simple name, and no
message text that could quote user input or credential material.

## Failure precedence

When several failure conditions are observed in the same run, exactly one decides the exit
code, chosen by the first matching row of this fixed order:

| Order | Condition | Exit |
| ----- | --------- | ---- |
| 1 | CLI parse or validation failure | 2 |
| 2 | Missing or unsafe local configuration or credential | 3 |
| 3 | User interruption or termination | 130 / 143 |
| 4 | Transport, TLS, DNS, deadline, or output-I/O failure | 6 |
| 5 | Response size, content-type, encoding, or parse failure | 8 |
| 6 | Upstream status classification | 4, 5, or 7 |
| 7 | Unexpected internal error | 70 |

The resolution is deterministic: it depends only on which conditions the run observed, never
on the order in which they were noticed.

### Observation and arbitration

The rows resolve by where each condition can be observed. Rows 1 and 2 — CLI parse or
validation, then missing or unsafe configuration or credential — reject the run before any
exchange, so no later condition can accompany them: a buffered, non-streaming run reports
exactly one condition, its failing exchange's category or its result-write verdict, and a
local rejection travels alone. A streaming run keeps one cancellation latch that records
exactly the first terminal cause observed — SIGINT, SIGTERM, a broken output pipe, an idle or
wall-clock deadline, a subscriber failure, a transport failure, or a clean close — and that
latch winner, when present, is authoritative for the run's exit code; a latched user signal
outranks the failed exchange's own category, so a run interrupted mid-exchange exits by its
signal (130 or 143) while the mode still renders the failure document. The streaming
vocabulary agrees with the table by construction: SIGINT and SIGTERM map to the interruption
and termination signals (130 and 143), every stream failure — idle timeout, wall timeout,
subscriber failure, transport failure — maps to the transport signal (6), and a broken pipe
and a clean close are successes (0), never failures.

### Upstream status classification

| Upstream observation | Kind | Exit |
| -------------------- | ---- | ---- |
| HTTP 401 or 403 | authentication | 4 |
| HTTP 429 | rate limited | 5 |
| HTTP 422 carrying a structured auth, entitlement, or payment error code (auth-bearing) | authentication | 4 |
| Any other 3xx, 4xx, or 5xx (including 400, 404, 408, and non-auth-bearing 422) | upstream | 7 |

The HTTP status wins over a structured error body unless the status is the documented
auth-bearing 422: a structured authentication code arriving with status 500 classifies as
exit 7, never exit 4.

## Global and remote options

The command line has exactly two option vocabularies. The **all-command globals** parse
anywhere — before or after the subcommand token, up to the `--` end-of-options marker — and
every command accepts them, local ones included: `--verbose`, `--quiet`, `--no-color`,
`-h/--help`, and `-V/--version`. `--verbose` and `--quiet` are mutually exclusive in both
orders (usage error, exit 2), and `--no-color` joins the environment decision of the ANSI
section. The **remote options** — `-o/--output`, `--pretty`, `--timeout`,
`--connect-timeout`, `--api-version`, and the hidden loopback-only `--base-url` — belong to
the remote grammar alone: each remote subcommand owns them after its command token, so an
occurrence before the subcommand token is an unknown option of the root (exit 2), exactly as
it is for every local command.

### Duration options

`--timeout` and `--connect-timeout` accept exactly one grammar: an unsigned integer with a
`ms`, `s`, or `m` unit — `250ms`, `1s`, `2m`. Every other spelling is a usage error (exit 2)
raised at parse time, before any dispatch: ISO-8601 forms, `ns` and `h` units, a bare
number, surrounding whitespace or junk, zero, negatives, and values that overflow the
duration range all fail with a diagnostic naming the expected form. The defaults are
`--timeout 30s`, the total budget of a non-streaming exchange, and `--connect-timeout 10s`,
the connection-establishment budget; an unsupplied budget is omitted from the wire, never
coerced into an explicit value. The streaming family's own budgets (`--idle-timeout`,
`--stream-timeout`) ride the answers grammar alone — see the Answers command section.

### API version pin

`--api-version` pins the exchange's `Api-Version` header to a real calendar date in strict
`YYYY-MM-DD` form, for example `--api-version 2026-08-30`. Impossible dates (`2026-02-30`),
loose spellings (`2026-8-30`, `20260830`), and every other format are usage errors (exit 2)
decided before any dispatch: an invalid pin is never forwarded, because a value the upstream
would only reject after the round trip must not leave the process. A valid pin travels
verbatim on every exchange of the invocation; an unpinned invocation sends no header and
accepts the server's own default — this CLI never hardcodes one (the wire behavior is the
base-protocol table of `docs/brave-api-contract.md#base-protocol`).

### Strict parsing

Parsing is strict everywhere, recursively across the whole command tree: abbreviated long
options are rejected (`--time` is not `--timeout`), unmatched options are errors — never
silently promoted to positional or option parameters — and a repeated single-valued option
is a usage error naming the flag. Together these pin the durability guarantee for scripts:
**a stored invocation never changes meaning when a new option is added**. An abbreviation
that happened to be unique cannot silently re-target a longer name, an unknown option
cannot hide inside a positional, and a duplicated scalar cannot quietly resolve to its last
value.

## Output modes

`--output` accepts exactly one of the lowercase words `human`, `json`, `jsonl`, `raw`. The
spelling is case-sensitive and admits no surrounding whitespace: `JSON`, `Json`, or ` json`
are usage errors (exit 2).

### Human document terminal safety

Every upstream-derived string a human document renders — titles, urls, descriptions,
passages, answer text, citations, entities, research progress — passes one terminal-safety
rule first: a complete escape sequence (CSI cursor controls, OSC sequences including OSC-8
hyperlinks, and the two-character `ESC` forms) is removed wholly, escape byte and payload
together, and every other C0 or C1 control character except the line feed is replaced by
U+FFFD, so upstream text can never forge results, links, or cursor movement in a terminal.
The line feed alone survives, because it carries the document's line structure. Headings
pass the same rule, so the only escape bytes a human document can ever carry are the
document's own style wraps — the bold and dim intensities of the human output styles
section — applied under the color decision and always around already-sanitized,
already-wrapped plain text. Machine documents need no such rule — their codecs escape
control characters by construction — and raw output relays the served bytes untouched.

## Human output layout

Every human listing of results — each search vertical, the place listings, the place
enrichment blocks, the rich vertical listings — renders one shared entry layout, so no
family diverges:

- One blank line follows the heading block, and one blank line separates consecutive
  entries; no blank line follows the last entry.
- Each entry line begins in a gutter: the entry index right-aligned to the widest index
  of the listing — one column wider than that widest index, so `  1  Title…` and
  ` 10  Title…` share one title column — followed by two spaces.
- The title renders bold (when color is enabled), word-wrapped at the width minus the
  gutter, with continuation lines aligned under the title's first column. An entry
  without a usable title renders the literal `(no title)` (the place enrichment commands
  head a titleless block with the invocation's own id instead).
- The url members render dim and are never wrapped: a url longer than the width
  overflows whole, because breaking a url changes what it names.
- Body-text members (descriptions, addresses, contact lines) render at default
  intensity, word-wrapped at the width minus the gutter with continuations aligned.
- Per-type metadata renders as the entry's final dim line(s), from fields the entries
  already carry: the `age` freshness member of news and videos, the `Rating:`/`Distance:`
  members of places, the `source:` attribution of rich items, the price range and
  timezone of place details. Metadata never extracts a field the machine documents do
  not already carry.
- Word wrap counts code points — an astral character is one column — and applies only to
  plain text: every upstream string passes the terminal-safety rule before it is
  wrapped, and styling is applied after wrapping, so layout code never measures an
  escape byte.
- The listing closes with its count line, then the advisory quota footer when the
  exchange earned one.

The empty-listing lines (`No results.`, `No places.`, `No suggestions.`, `No
corrections.`, `No context.`, `No rich results.`, `No answer.`) are exactly one line and
carry no layout.

## Human output width

Human layout wraps to the invocation's render width: the exported `COLUMNS` environment
variable when it parses as a plain integer in the documented 40..500 range, and 100
otherwise — unexported, non-numeric, padded, or out-of-range values all leave the
default. The width is read once by the single terminal-detection seam beside the color
decision and travels with the invocation's output state; machine documents never consult
it. `COLUMNS` is honored when exported; otherwise 100.

## Human output styles

The style set of human documents is exactly two SGR intensities — bold (`ESC[1m` …
`ESC[0m`) for headings, entry titles, and the labels of advisory lines, and dim
(`ESC[2m` … `ESC[0m`) for urls, per-type metadata, and the quota footer — and each is
emitted only when the invocation's color is enabled under the conservative decision of
the ANSI enablement section; a non-colorable render context emits the same layout with
zero escape bytes. Stderr carries one styled surface: the research progress lines of a
streaming answers run render their label (`answers: research <tag>:`) bold with the
payload plain, under the same color decision. Every other diagnostic stays plain on
every channel, and the usage-and-cost summary line of an answers document renders its
`Usage:` label bold.

## Quota footer

Every human listing that ends in a count line also ends in the advisory quota footer
when the exchange's rate-limit snapshot earned one: the most restrictive applicable
window — the lowest remaining among the windows with a real limit (`limit` 0 means
unlimited, not exhausted), the first observed on ties — with two or fewer remaining
requests renders exactly one trailing dim line after the count line:

```text
quota: 2 of 10 remaining (window resets in 3s)
```

The reset is the window's whole-second reset duration. A snapshot whose most
restrictive window holds three or more remaining requests, a snapshot without
applicable windows, and a snapshot the exchange never observed all render no footer.
`--quiet` suppresses the footer like every advisory line. The footer draws only on the
parsed windows the machine documents already carry (`meta.rate_limits`); it extracts
nothing new, and it never renders in a machine mode.

## Output mode compatibility

| Command family | human | json | jsonl | raw |
| -------------- | ----- | ---- | ----- | --- |
| Remote single-request command | yes | yes | yes | yes |
| Remote multi-request command | yes | yes | yes | rejected — raw is single-request only |
| version, help, completion | fixed | rejected | rejected | rejected |
| config mutating commands | fixed | rejected | rejected | rejected |
| config show | fixed human default | accepted — exactly one machine record pinned by `schemas/v1/config-show.schema.json` | rejected | rejected |

- `--pretty` is invalid with `--output jsonl` and with `--output raw` (usage error, exit 2).
- Local commands — version, help, completion, and the config commands — reject `--output`
  and `--pretty` before any side effect with exit 2; their text output is fixed. The one
  exception is `config show`, which accepts exactly `--output human|json` — its json mode
  writes one LF-terminated, schema-versioned record (`schema_version`, `command`
  (`config.show`), `source`, `config_path`, `api_key_present`, `shadowed`) derived from the
  same redaction-safe view as the human text, so no channel can ever carry credential
  material — while jsonl and raw stay usage errors and `--pretty` is rejected like every
  other config spelling.
- `--output raw` is valid only for operations that issue exactly one upstream request;
  multi-request operations reject it with exit 2.
- `--verbose` and `--quiet` are mutually exclusive (usage error, exit 2).

## Parse before render

A failure observed before the output mode was successfully parsed is reported as human
diagnostics on stderr only, because no machine document can exist yet: the output channel
was never agreed on. Once a mode is successfully parsed, failure reporting belongs to that
mode's own contract — json emits exactly one `ok:false` envelope on stdout with human
diagnostics on stderr, jsonl ends the stream with one `error` record, and human and raw
diagnose on stderr.

One rule crosses the mode boundary intact: a local-configuration failure resolved before
any exchange — a missing or invalid credential — explains itself with exactly one stderr
diagnostic line in every mode, jsonl included. It is a local precondition failure, not a
stream event: no dispatch happened, so machine modes emit no document for it and the
process keeps exit 3.

## Warning routing

Advisory warnings and progress belong to the channel the active mode owns:

| Mode | Warnings and progress |
| ---- | --------------------- |
| human | stderr diagnostics |
| json | collected into `meta.warnings` of the single envelope |
| jsonl | structured `warning` records on stdout |
| raw | stderr diagnostics |

`--quiet` suppresses only the advisory human and raw diagnostics. It never suppresses
machine records — collected json warnings and streamed jsonl records — and it never
suppresses the sole failure explanation of a failing run.

The streaming jsonl-is-preferable advisory is a JSON-only decision: only `--output json`
carries it, because only buffering hides progress — human and raw visibly stream already,
so they emit no such advisory on any channel.

## Machine envelope

JSON mode writes exactly one document to stdout, compact and terminated by exactly one LF;
`--pretty` switches to the stable indented form — two-space indentation, LF line breaks only,
one space after each member colon, empty containers inline as `{}` and `[]` — with the same
member order and still exactly one terminating LF. The field inventories and validation rules
are normative in `schemas/v1/envelope-success.schema.json` and
`schemas/v1/envelope-error.schema.json`.

Success members: `schema_version` (always `"1"`), `ok` (`true`), `command` (the canonical
dotted subcommand path used for dispatch), `data.projection` (stable local fields),
`data.upstream` (the lossless upstream tree), `meta.request_id`, `meta.http_status`,
`meta.api_version`, `meta.rate_limits[]`, `meta.usage`, `meta.warnings`.

`data.upstream` decimal normalization is exact and deterministic: upstream JSON numbers with
a fraction or exponent are parsed as `BigDecimal` and re-serialized as that decimal's plain
`toString`, so `1.10` keeps its trailing zero, a significand longer than a double survives
digit-for-digit (`0.1000000000000000000001` stays exactly that), exponents normalize to
uppercase scientific notation (`1e2` is emitted as `1E+2`, value-equal), and negative zero
loses its sign while keeping its scale (`-0.0` is emitted as `0.0`). Integers are untouched.
That is the whole of "lossless" here: a value-exact BigDecimal round-trip with deterministic
spelling, so two upstream readers fed the same bytes cannot change the envelope.

Projection fields this CLI does not have are omitted from `data.projection`; a member is
never emitted as JSON `null` — null-valued projection members are rejected when the
projection is built — and projection member names never collide with the framing names
`schema_version`, `type`, or `command`, nor repeat within one object.

`meta.rate_limits[].reset_ms` is a non-negative whole-millisecond count: sub-millisecond
precision is truncated toward zero when the duration is serialized, and a negative reset
duration is rejected when the window is constructed instead of ever reaching the wire.
`meta.usage` renders the Answers request, query, and token counters, the per-component and
total cost decimals at their exact reported scale, and the preserved unknown `X-Request-*`
fields as `usage.unknown`; its parse notes stay on the diagnostic channel, never in the
envelope.

Failure members: `schema_version`, `ok` (`false`), `command`, `error.code`,
`error.message`, `error.retryable`, `error.upstream_code`, `error.details`,
`meta.http_status`, `meta.rate_limits`. The failure envelope's `meta.rate_limits` carries the
windows observed on the failing exchange — for a 429, the windows that explain it — and is
empty when the exchange observed none. `error.details` is `null` for a detail-free failure
and otherwise exactly one counted shape: `{"requested_pages": M, "received_pages": N}` for a
failed multi-request pagination run, `{"requested_requests": M, "received_requests": N}` for
a failed multi-request chunk fan-out, and `{"deltas_emitted": D, "citations_seen": C,
"cost_unknown": B}` for a failed streamed answers run — the answer deltas emitted and
citations seen before the failure, and whether the stream ended without its final usage
event. The stable `error.code` strings are `USAGE_ERROR`,
`LOCAL_CONFIG_ERROR`, `AUTHENTICATION_FAILED`, `RATE_LIMITED`, `TRANSPORT_ERROR`,
`UPSTREAM_ERROR`, `MALFORMED_RESPONSE`, `INTERNAL_ERROR`; the streaming family's terminal
documents additionally carry the signal codes `INTERRUPTED` (SIGINT) and `TERMINATED`
(SIGTERM), which have no failure category of their own.

Absent secrets, raw request headers, stack traces, and internal class names are never
serialized.

## JSONL framing

Each JSON Lines line is one independent compact object whose leading members are exactly
`schema_version`, `type`, and `command`, terminated by one LF; headings and ANSI escapes
never appear. The record `type` values are `result`, `answer_delta`, `citation`, `entity`,
`research_progress`, `upstream_event`, `warning`, `summary`, `error`. A successful stream
ends in exactly one `summary` record; a failed stream ends in one `error` record while
stdout remains writable. An error record carries the observed rate-limit windows — the same
per-window rendering as the envelope's `meta.rate_limits`, as a trailing `rate_limits`
member — exactly when the failing exchange observed a snapshot: a 429 explains itself with
the windows it saw, a walk's later-page failure carries the failing page's snapshot, a
failed Answers stream carries its open exchange's headers, and a transport-local break that
predates any header observation (a connect failure, an interrupted pacing wait) omits the
member. Field inventories are normative in
`schemas/v1/jsonl-record.schema.json`.

## Raw output

Raw mode writes the exact decoded upstream body bytes to stdout: no added newline, no JSON
normalization, no content-coding bytes, and `--pretty` is invalid. On failure, a decoded
upstream error body within the bounded size is emitted byte-exactly while the classified
nonzero exit status is preserved; an over-bound error body produces no stdout, a stderr
diagnostic, and exit 8.

## Web command

`brave-search web <query>` searches the web vertical: it is the reference shape of the
command families and carries the widest search grammar. The shared spellings `--country`,
`--search-lang`, `--ui-lang`, `--safe-search`, `--freshness`, `--count`, `--page`, and
`--spellcheck` are all documented for web, and web accepts every goggle strategy of the
goggles section. The web-only options are:

```text
--text-decorations / --no-text-decorations
--result-filter <filter>          repeatable, also comma-separated
--units metric|imperial
--extra-snippets / --no-extra-snippets
--include-fetch-metadata / --no-include-fetch-metadata
--operators / --no-operators
--enable-rich-callback / --no-enable-rich-callback
--loc-lat <decimal>               pairs with --loc-long, -90 to 90
--loc-long <decimal>              pairs with --loc-lat, -180 to 180
--loc-city <text>
--loc-state <code>
--loc-state-name <text>
--loc-country <code>
--loc-postal-code <text>
--loc-timezone <IANA-zone>
```

- `--count` is 1–20 and `--page` is 1–10 (sent as its zero-based offset), both enforced
  locally at the edges before any dispatch.
- `--country` accepts exactly a two-letter code: the region-wide `ALL` is the news and
  videos spelling and is a usage error here (exit 2) before any dispatch.
- The location group travels as the endpoint's location headers; coordinates must arrive
  paired and range-checked — `NaN` and the infinities fail conversion before any dispatch —
  and `--loc-timezone` is a web-only spelling the other location endpoints refuse.
- `--result-filter` carries the endpoint's documented result-type filters; values are
  nonblank strings, repeatable or comma-separated.
- The negatable pairs are tri-state like every boolean with an upstream default: unsupplied
  stays omitted from the wire. `--enable-rich-callback` is the switch that makes the rich
  command's callback key exist.
- Wire mapping and the GET/POST trigger (long URIs and inline goggle definitions travel as
  one POST JSON body): `docs/brave-api-contract.md#goggles-and-the-post-form`.

## Web command output

The web command is the reference shape for every remote single-request command.

### Human rendering

Success is UTF-8 text, every line LF-terminated, laid out and styled by the shared human
output sections above: one bold heading line `Web results for: <query>` — suffixed `
(page N)` only when a page beyond the first was requested — then one blank line, then
one block per logical result in presentation order, blocks separated by one blank line:

```
 1  <title>
    <url>
    <description>
```

The title line shows the result title — or the literal `(no title)` when the upstream
result carried no usable title — bold and wrapped under the shared rules; the dim `url`
line appears only when the member was usable and is never wrapped; the `description`
line appears only when usable and wraps like every body text. A final count line ends
the listing (`3 results.`, singular `1 result.`), followed by the advisory quota footer
when the exchange earned one. Zero results render exactly one line: `No results.`
Exchange notes route to stderr as advisory diagnostics, suppressed by `--quiet`.

### JSON projection

`data.projection` of a web success carries exactly `result_count`, `page` (the effective
user-facing page, `1` when none was pinned), `upstream_offset` (its zero-based form), and
`results` — one object per logical result with `position` (the original zero-based upstream
array index), `title`, `url`, and `description`, each present only when usable. A success body
that is exactly one JSON document but not an object encodes `data.upstream` as parsed with a
zero-result projection; a body that is not readable JSON renders the malformed failure
document of the mode (raw never parses its passthrough).

### JSONL records

Result records carry `position`, `bucket` (always `web`), `title`, `url`, and `description`
(present only when usable). The terminal summary record carries:

| Field | Always present | Meaning |
| ----- | -------------- | ------- |
| `result_count` | yes | Logical results enumerated for this exchange. |
| `page` | yes | Effective user-facing page, `1` when none was pinned. |
| `upstream_offset` | yes | Zero-based offset of that page. |
| `http_status` | yes | Status of the exchange. |
| `request_id` | when offered | The response's request identifier. |
| `api_version` | when offered | The response's API version. |

The summary field set deliberately excludes rate-limit windows and usage: they are per-exchange
metadata owned by the JSON envelope's `meta` (and the stderr channel of human and raw modes),
and keeping terminal JSONL records lean preserves independent parseability without duplicating
the envelope contract in every command's records. Empty success emits the summary alone; a
failed single request emits exactly one `error` record and no summary. Field inventories are
normative in `schemas/v1/web-result.schema.json` and `schemas/v1/web-summary.schema.json`,
referenced from `schemas/v1/jsonl-record.schema.json`.

### Failure rendering

A failed exchange renders the mode's own failure document with the failure kind's exit status:
human and raw diagnose once on stderr (raw additionally emits the bounded upstream error body
byte-exactly when one exists), json writes exactly one `ok:false` envelope on stdout plus the
human diagnostic on stderr, and jsonl ends the stream with one `error` record whose message is
the whole explanation. `error.retryable` is true exactly for rate-limited failures.

## News command

`brave-search news <query>` searches the news vertical with the shared search grammar and the
news-only additions. The shared spellings `--country`, `--search-lang`, `--ui-lang`,
`--safe-search`, `--freshness`, `--count`, `--page`, and `--spellcheck` are all documented
for news; the complement guard still runs, so a spelling added to the shared mixin later is
refused here until the news endpoint documents it. The web-only spellings — decorations,
result filters, units, the rich callback, and the whole location group including
`--loc-timezone` — are unknown options of this command, and the news endpoint documents no
location header and no timezone to reject by name. The news-only options are:

```text
--extra-snippets / --no-extra-snippets
--include-fetch-metadata / --no-include-fetch-metadata
--operators / --no-operators
--all-pages
--max-pages <1..10>
```

- `--count` is 1–50 and `--page` is 1–10 (sent as its zero-based offset), both enforced
  locally at the edges before any dispatch.
- `--country` accepts a two-letter code or the region-wide `ALL`; every other spelling is a
  usage error (exit 2) before any dispatch.
- Goggles are supported: the shared `--goggle`, `--goggle-file`, `--include-site`, and
  `--exclude-site` strategies compile into the news request under the shared rules.
- Wire mapping and the GET/POST trigger: `docs/brave-api-contract.md#news-search-wire-form`.
- News documents no continuation field: `--all-pages` walks the user-facing pages
  sequentially through page 10 unless bounded by `--max-pages`, and a short page is never
  read as exhaustion — the page budget is the walk's sole terminator. The pagination grammar
  (`--page`/`--all-pages` exclusivity, `--max-pages` requiring `--all-pages`, the raw-output
  rejection) is the shared contract of the pagination section.

## News command output

The news output shapes mirror the web reference shapes with the news payload. The human
listing heads with `News results for: <query>` — suffixed ` (page N)` beyond the first page,
or ` (pages 1-N)` for a walk of several received pages — then numbered results in
presentation order:

```
 1  <title>
    <url>
    <description>
    <age>
```

The member lines appear only when those members were usable: the dim `url` line is
never wrapped, the `description` wraps like every body text, and `age` — the freshness
member the news endpoint attaches to a result, carried verbatim in whatever textual
form it had — renders as the entry's final dim metadata line. A non-textual upstream
member is omitted, never coerced. The entry layout, styles, width, and quota footer
follow the shared human output sections; the count line and the zero-result `No
results.` line follow the web shapes.

The JSON projection carries `result_count`, `page`, `upstream_offset`, and `results` — one
object per logical result with `position`, `title`, `url`, `description`, and `age`, each
present only when usable. JSONL result records carry `position`, `bucket` (always `news`),
and the usable textual members including `age`; the terminal summary record carries the web
summary's field set. The paged forms add the per-result `page` provenance and the counted
summary of the pagination section (`requested_pages`, `received_pages`,
`duplicates_removed`). Field inventories are normative in `schemas/v1/news-result.schema.json`,
`schemas/v1/news-summary.schema.json`, `schemas/v1/news-paged-result.schema.json`, and
`schemas/v1/news-paged-summary.schema.json`, referenced from `schemas/v1/jsonl-record.schema.json`.

## Videos command

`brave-search videos <query>` searches the videos vertical with the shared search grammar
and the videos-only additions. The shared spellings `--country`, `--search-lang`,
`--ui-lang`, `--safe-search`, `--freshness`, `--count`, `--page`, and `--spellcheck` are
all documented for videos; the complement guard still runs, so a spelling added to the
shared mixin later is refused here until the videos endpoint documents it. The videos-only
options are:

```text
--include-fetch-metadata / --no-include-fetch-metadata
--operators / --no-operators
--all-pages
--max-pages <1..10>
```

- `--count` is 1–50 and `--page` is 1–10 (sent as its zero-based offset), both enforced
  locally at the edges before any dispatch.
- `--country` accepts a two-letter code or the region-wide `ALL`; every other spelling is a
  usage error (exit 2) before any dispatch.
- The videos endpoint documents no Goggles: every goggle spelling — `--goggle`,
  `--goggle-file`, `--include-site`, `--exclude-site` — is an unknown option of this
  command (exit 2), and the goggle strategy rules never apply here. The web-only spellings
  (decorations, result filters, units, the rich callback, extra snippets, and the whole
  location group including `--loc-timezone`) are unknown options too.
- Wire mapping and the GET/POST trigger: `docs/brave-api-contract.md#videos-search-wire-form`.
- Videos documents no continuation field: `--all-pages` walks the user-facing pages
  sequentially through page 10 unless bounded by `--max-pages`, and a short page is never
  read as exhaustion — the page budget is the walk's sole terminator. The pagination grammar
  (`--page`/`--all-pages` exclusivity, `--max-pages` requiring `--all-pages`, the raw-output
  rejection) is the shared contract of the pagination section.

## Videos command output

The videos output shapes mirror the web reference shapes with the videos payload. The
human listing heads with `Videos results for: <query>` — suffixed ` (page N)` beyond the
first page, or ` (pages 1-N)` for a walk of several received pages — then numbered results
in presentation order:

```
 1  <title>
    <url>
    <description>
    <age>
```

The member lines appear only when those members were usable: the dim `url` line is
never wrapped, the `description` wraps like every body text, and `age` — the freshness
member the videos endpoint attaches to a result, carried verbatim in whatever textual
form it had — renders as the entry's final dim metadata line. A non-textual upstream
member is omitted, never coerced. The entry layout, styles, width, and quota footer
follow the shared human output sections; the count line and the zero-result `No
results.` line follow the web shapes.

The JSON projection carries `result_count`, `page`, `upstream_offset`, and `results` — one
object per logical result with `position`, `title`, `url`, `description`, and `age`, each
present only when usable. JSONL result records carry `position`, `bucket` (always
`videos`), and the usable textual members including `age`; the terminal summary record
carries the web summary's field set. The paged forms add the per-result `page` provenance
and the counted summary of the pagination section (`requested_pages`, `received_pages`,
`duplicates_removed`). Field inventories are normative in
`schemas/v1/videos-result.schema.json`, `schemas/v1/videos-summary.schema.json`,
`schemas/v1/videos-paged-result.schema.json`, and `schemas/v1/videos-paged-summary.schema.json`,
referenced from `schemas/v1/jsonl-record.schema.json`.

## Images command

`brave-search images <query>` searches the images vertical with the shared search grammar
restricted to the images subset. The shared spellings `--country`, `--search-lang`,
`--safe-search`, `--count`, and `--spellcheck` are documented for images; the complement
guard still runs, so `--ui-lang`, `--freshness`, `--page`, and any spelling added to the
shared mixin later are refused here as usage errors (exit 2) before any dispatch. There
are no images-only options.

- `--count` is 1–200 — the widest count of any vertical — enforced locally at both edges
  before any dispatch.
- `--safe-search` accepts exactly `off` or `strict`: the images endpoint documents no
  `moderate` level, so that spelling is a usage error (exit 2) before any dispatch.
- `--country` accepts a two-letter code or the region-wide `ALL`; every other spelling is a
  usage error (exit 2) before any dispatch.
- Images is a single non-paginated request: the endpoint documents no `offset`, so `--page`
  is not accepted by this command and `--all-pages`/`--max-pages` are unknown options
  (exit 2). Raise `--count` for more results; `--output raw` stays valid.
- The images endpoint documents no Goggles and no operators: every goggle spelling —
  `--goggle`, `--goggle-file`, `--include-site`, `--exclude-site` — and `--operators` are
  unknown options here, as are all web-only spellings (decorations, result filters, units,
  the rich callback, extra snippets, and the whole location group including
  `--loc-timezone`).
- Wire mapping and the GET-only rule: `docs/brave-api-contract.md#images-search-wire-form`.

## Images command output

The images output shapes mirror the web reference shapes with the images payload. The
human listing heads with `Images results for: <query>` — the endpoint is non-paginated,
so no page suffix ever appears — then numbered results in presentation order:

```
 1  <title>
    <url>
    <image>
    <thumbnail>
```

The `url`, `image`, and `thumbnail` lines appear only when those members were usable:
`url` is the result page the image was found on, `image` the full-size image url, and
`thumbnail` the thumbnail url, each carried verbatim and each dim and never wrapped,
because every one of them is a url. A non-textual upstream member is
omitted, never coerced. The count line and the zero-result `No results.` line follow the
web shapes.

The JSON projection carries `result_count`, `page` (always 1, the constant page of a
non-paginated endpoint), `upstream_offset` (always 0), and `results` — one object per
logical result with `position`, `title`, `url`, `image`, and `thumbnail`, each present
only when usable. JSONL result records carry `position`, `bucket` (always `images`), and
the usable textual members; the terminal summary record carries the web summary's field
set. Field inventories are normative in `schemas/v1/images-result.schema.json` and
`schemas/v1/images-summary.schema.json`, referenced from
`schemas/v1/jsonl-record.schema.json`.

## Suggest command

`brave-search suggest <partial-query>` completes one partial query with the suggest
endpoint's own option set. The suggest endpoint speaks a different language wire name
than the search verticals: its language option travels as `lang` (never `search_lang`).
The CLI keeps one spelling across every command, so the shared `--search-lang` flag is
this command's accepted alias that maps to wire `lang` — a `--lang` spelling exists
nowhere in this CLI and is an unknown option.

- Documented shared spellings: `--country` and `--search-lang` (the alias above);
  `--count` (1–20, Brave's default of 5 stays an upstream decision through omission).
  The complement guard refuses `--ui-lang`, `--safe-search`, `--freshness`, `--page`,
  `--spellcheck`, and any shared spelling added later: exit 2 before any dispatch.
- `--rich` / `--no-rich` is the suggest-only negatable pair, tri-state like every
  boolean with a Brave default: unsupplied means omitted from the wire, because rich
  results require a paid autosuggest subscription upstream.
- `--country` accepts a two-letter code or the region-wide `ALL`; every other spelling
  is a usage error (exit 2) before any dispatch.
- Suggest is a single non-paginated GET: `--page` is not accepted and the page-walk
  flags, the goggle family, and every search-vertical-only spelling are unknown options
  (exit 2). `--output raw` stays valid.
- Wire mapping and the GET-only rule:
  `docs/brave-api-contract.md#suggest-search-wire-form`.

## Suggest command output

The suggest output shapes mirror the shared search reference shapes with the
suggestions payload. The human listing heads with `Suggestions for: <partial-query>` —
the endpoint is non-paginated, so no page suffix ever appears — then numbered
completions in presentation order:

```
 1  <suggested completion>
    <title>
    <description>
    <img>
```

The `title`, `description`, and `img` lines appear only when a rich suggestion carried
them — the texts wrap like every body text and the `img` url renders dim and never
wrapped; a non-textual upstream member is omitted, never coerced. The count line
counts suggestions (`2 suggestions.`), and an empty suggestion list renders the single
`No suggestions.` line.

The JSON projection carries `result_count`, `page` (always 1, the constant page of a
non-paginated endpoint), `upstream_offset` (always 0), and `results` — one object per
suggestion with `position`, `query` (the completion), and — each present only when
usable — `suggestion_type` (the upstream `type` member, renamed because the record frame
already owns a `type` member), `title`, `description`, and `img`. The deprecated
upstream `is_entity` flag is not carried, because `suggestion_type` is its documented
replacement. JSONL result records carry `position`, `bucket` (always `suggest`), and the
same members; the terminal summary record carries the web summary's field set. Field
inventories are normative in `schemas/v1/suggest-result.schema.json` and
`schemas/v1/suggest-summary.schema.json`, referenced from
`schemas/v1/jsonl-record.schema.json`.

## Spellcheck command

`brave-search spellcheck <query>` asks the spellcheck endpoint for corrections of one
query with the endpoint's own option set — the smallest inventory of any command. Like
suggest, the spellcheck endpoint maps its language option to wire `lang` (never
`search_lang`), and the shared `--search-lang` flag is this command's accepted alias for
it; a `--lang` spelling exists nowhere in this CLI and is an unknown option.

- Documented shared spellings: `--country` and `--search-lang` (the alias above) — and
  nothing else. `--count`, `--ui-lang`, `--safe-search`, `--freshness`, `--page`, and
  `--spellcheck` are refused with the usage exit (2) before any dispatch, and the
  suggest-only `--rich`, the goggle family, and the page-walk flags are unknown options.
- `--country` accepts a two-letter code or the region-wide `ALL`.
- Spellcheck is a single non-paginated GET; `--output raw` stays valid.
- Wire mapping and the GET-only rule:
  `docs/brave-api-contract.md#spellcheck-search-wire-form`.

## Spellcheck command output

The spellcheck endpoint answers with a list of corrections — one `results` entry per
correction whose `query` member is the corrected query — so the output shapes mirror the
shared search reference shapes with the corrections payload. The human listing heads
with `Spellcheck results for: <query>` and numbers the corrections in the shared entry
layout — a correction is its whole title line and carries no member lines; the count
line counts corrections (`1 correction.`). An empty correction list renders the single
`No corrections.` line, because a clean query is the answer, not an absence of output.

The JSON projection carries `result_count`, `page` (always 1), `upstream_offset`
(always 0), and `results` — one object per correction with `position` and `query` (the
corrected query). JSONL result records carry `position`, `bucket` (always `spellcheck`),
and `query`; the terminal summary record carries the web summary's field set. Field
inventories are normative in `schemas/v1/spellcheck-result.schema.json` and
`schemas/v1/spellcheck-summary.schema.json`, referenced from
`schemas/v1/jsonl-record.schema.json`.

## Rich command

`brave-search rich <callback-key>` fetches the current rich results of one earlier web
search that enabled rich callbacks. The callback key is an opaque reference that search
issued — not a query, and not a credential — so the positional is the command's whole
grammar: no search options exist here at all.

- The key travels verbatim under the endpoint's own `callback_key` wire name — never
  the search verticals' `q`; quote the key and use `--` when it starts like an option
  (for example, `brave-search rich -- -opaque-key`).
- The only local boundary is that the key is not blank: a blank, missing, or extra
  positional is a usage error (exit 2) before any dispatch, because the upstream
  contract documents no format rules for a callback key.
- Every search spelling — the shared options, the goggle family, and the page-walk
  flags — is an unknown option of this command (exit 2), because the endpoint documents
  none of them.
- Rich is a single non-paginated GET; `--output raw` stays valid.
- Wire mapping and the GET-only rule:
  `docs/brave-api-contract.md#rich-callback-wire-form`.

## Rich command output

The upstream response shape of the rich endpoint is undocumented — no public reference
page exists — so the machine output stays generic and lossless while the human output
renders what this CLI can read without guessing internals. The response is treated as
one JSON object whose top-level array members are vertical blocks: each block is named
by its own member name and counted by its whole array size; non-array top-level members
are never verticals.

The JSON projection carries `vertical_count`, `item_count` (the total across blocks),
and `verticals` — one object per block with `vertical` (its member name) and
`item_count` — and deliberately nothing else: the internals of an undocumented shape are
never modeled, and the envelope's `data.upstream` carries the lossless body untouched,
so no projection decision can lose third-party data. JSONL emits one `result` record
per vertical block — the block is the largest unit this CLI can name without modeling
internals — carrying `bucket` (the vertical's own member name), `position` (its
zero-based ordinal among the blocks), and `item_count`; the terminal summary carries
`result_count` (blocks enumerated), `item_count`, and the exchange identifiers. Field
inventories are normative in `schemas/v1/rich-result.schema.json` and
`schemas/v1/rich-summary.schema.json`, referenced from `schemas/v1/jsonl-record.schema.json`.

The human document heads with `Rich results for: <callback-key>` and renders one
section per vertical. The verticals this CLI knows how to read — the pinned known set
`videos`, `images`, `faqs`, widened only when the endpoint's contract is documented —
render as listings:

```
videos (2):
 1  <title>
    <url>
    <description>
    source: <source>
```

The items of one vertical render in the shared entry layout — bold wrapped titles,
dim unwrapped urls, wrapped descriptions — and the verticals themselves are separated
by one blank line after the heading block's own. The `url`, `description`, and
`source` lines appear only when the item carried them, the `source:` attribution
rendering as the item's final dim metadata line; an item without a usable title
renders `(no title)`. The `source` line is the provider
attribution third-party rich data carries — it always renders in human output and
always stays lossless inside `data.upstream`. The item member set this CLI reads —
`title`, `url`, `description`, `source` — is a pinned assumption of this contract, not
an endpoint contract: the upstream response shape is undocumented, exactly these four
names are read, and the set widens only when the endpoint's contract is documented.
Every other vertical renders the one-line
generic fallback `<name>: <N> items (unrecognized vertical; the json envelope carries
the lossless body)`. The count line counts both axes (`4 verticals, 7 items.`), and a
body without vertical blocks renders the single `No rich results.` line.

## Answers command

`brave-search answers <question>` asks one question and prints the grounded answer
through the chat-completions endpoint. Streaming is the transport default and
`--no-stream` the only explicit blocking switch — never inferred from the output mode.
Every output mode belongs to the streaming family: human, JSONL, buffered JSON, and raw
each render the live stream in their own channel (see [Answers streaming output](#answers-streaming-output));
`--output jsonl` is valid only on a streaming invocation, because the JSONL channel is the
record stream itself.

- The question is the single `user` message. The endpoint documents no question limits of
  its own, so the shared query rules apply: 1–400 code points, at most 50
  whitespace-delimited words, all-whitespace rejected, valid bytes transmitted without
  normalization (exit 2 before any dispatch).
- `--model` is omitted by default so Brave selects its own; `brave`, `brave-pro`, and
  unknown future model strings pass through unchanged. `--max-completion-tokens` must be
  positive (the endpoint documents no upper bound); `--seed` accepts every integer.
- Locale: `--country` is a two-letter code (the search controls ride the web-search
  options container, whose country spelling documents no region-wide `ALL`), `--language`
  is this endpoint's own language spelling — never the search verticals' `--search-lang`,
  which is refused here — and `--safe-search` is `off|moderate|strict`.
- `--citations`/`--no-citations` and `--entities`/`--no-entities` are tri-state: enabled
  citations and entities require streaming, so `--citations` or `--entities` with
  `--no-stream` is a usage error (exit 2) before any dispatch, while `--no-citations` and
  `--no-entities` stay valid blocking requests. Blocking answers is incompatible with
  `--output jsonl` (exit 2 before any dispatch); raw stays valid for the single blocking
  request.
- The research family: every `--research-*` option requires `--research`, and `--research`
  requires streaming, so on a blocking request each of them — research, research
  thinking included, transitively — is a usage error (exit 2) before any dispatch. The
  bounds: tokens-per-query 1024–16384, queries 1–50, iterations 1–5, seconds 1–300,
  results-per-query 1–60.
- `--timeout` is the non-streaming total deadline of the blocking exchange and never
  applies to a stream; `--idle-timeout` and `--stream-timeout` are the streaming roles'
  deadlines, accepted and inert for a blocking answer. A stream left unpinned runs with a
  60-second idle window (300 for research) that every decoded body byte resets, and no
  wall deadline unless research pinned its seconds — then those seconds plus 30. Both
  deadlines exit `6` with one diagnostic line, and `--verbose` failures additionally
  report the exchange's last transport activity and last semantic progress. A consumer that stops reading a full stdout pipe stalls the writer, which stalls the
  body reads with it — no decoded byte arrives to reset the idle clock — so the idle
  watchdog may cut an otherwise healthy stream once the idle window passes; a consumer
  that keeps up, or a broken pipe that ends the run, never meets this interaction. Wire mapping
  and the nested POST body: `docs/brave-api-contract.md#answers-wire-form`.

## Answers blocking output

The documented blocking response is the OpenAI chat-completions shape: the answer rides
`choices[0].message.content`, and the usage counters arrive through the `X-Request-*`
response headers. A top-level `citations` array is read tolerantly when a response
carries one — the upstream contract documents citation carriage for the streaming family
— and unknown members never break extraction; only a body that is not one readable JSON
document is the malformed failure (exit 8).

The JSON projection carries `answer` (omitted when the response offered none, never
emitted as null), `citation_count`, and `citations` — one object per carried citation
with `number`, `url` (the one required member), `favicon`, `snippet`, `start_index`, and
`end_index`, each present only when offered — while the envelope's `data.upstream`
carries the lossless document and `meta.usage` carries the header-observed counters and
exact-scale costs. Field inventories are normative in
`schemas/v1/answers-blocking.schema.json` and
`schemas/v1/answers-blocking-citation.schema.json`. Raw writes the decoded body bytes
exactly; there is no blocking JSONL record stream.

The human document heads with the bold `Answer for: <question>` line, renders the
answer text verbatim (`No answer.` when the response offered none), then separates the
terminal sections from the answer by one blank line: the `Citations:` section as
numbered url lines with indented snippets exactly when the response carried citations,
and the usage summary line exactly when the exchange observed usage headers — the bold
`Usage:` label followed by `requests 1, queries 2, tokens 900 in / 120 out, total cost
0.0042`, carrying precisely the members observed in that fixed order — the sections
themselves separated by one blank line each.

## Answers streaming output

A streaming invocation renders the decoded event stream of one exchange. The decoded
stream is this CLI's semantic reading of the SSE body: ordinary answer text runs, the
documented Brave tags (`citation`, `entity`, `usage`, `queries`, `analyzing`, `thinking`,
`progress`, `blindspots`, `answer`), well-formed unknown tags preserved verbatim, and
passthrough chunks that carried no content. The ending resolves through one shared
terminal-cause latch — the first cause wins: a real SIGINT exits `130` and renders the
mode's interruption document, never a fabricated completion; a downstream broken pipe is
silent `0`; a typed decode failure — a malformed tag payload, a malformed chunk, an
unfinished tag at the terminator, an over-ceiling buffered accumulation — exits `8`; the
idle and wall deadlines, subscriber failures, and transport breaks exit `6` with one
diagnostic line. A real SIGTERM exits `143` the same way SIGINT exits `130`: the mode
renders its signal document — the interruption or termination message with its partial
counts — never a fabricated completion. An upstream failure at stream open keeps the shared
status table (`401`→4, `429`→5, `5xx`→7) rendered per mode exactly like a blocking failure,
unless a user signal won the latch during the connect window — then the failure document
still renders but the signal's exit (130 or 143) decides, cause-authoritative like every
stream path.

Streaming opens observe usage only through the final `<usage>` stream tag: the blocking
family's `X-Request-*` response headers are a blocking-only channel (live evidence shows
streaming opens answer none), so a stream without a final usage event reports the cost as
unknown, never as free.

### Human

The bold heading `Answer for: <question>` is written once, then every text delta is
written and flushed as its own document — the output is visibly incremental, and the
delta bytes are the sanitized upstream text unchanged in content. Research-family
events (`queries`, `analyzing`, `thinking`, `progress`, `blindspots`) render as concise
stderr progress lines — one per event, non-verbose, because they are the UX of a
research stream — with the line's label (`answers: research <tag>:`) bold and the
payload plain under the color decision; `--quiet` suppresses them and the unknown-tag
lines alike. The documented `answer` tag and well-formed unknown tags render as one
stderr line each (`answers: upstream event <name>`); their payload semantics await live
evidence and nothing is invented. At completion the terminal sections append, separated
from the answer text and from each other by one blank line each: the `Citations:`
numbered list with indented snippets, the `Entities:` list, and the usage summary line
exactly as the blocking document renders it, bold label included. The final usage tag's
parse notes render as stderr diagnostics on the same warning routing, `--quiet` included.
A failed stream
writes one stderr diagnostic and no completion sections; when it ended without a final
usage event the diagnostic states the cost is unknown, never free.

### JSONL

One record per decoded event, each line independently schema-validated by
`schemas/v1/jsonl-record.schema.json`: `answer_delta` (zero-based `sequence`, exact
`text`), `citation` and `entity` (the projected payload members — the entity payload's
`type` kind member lands as `entity_type`, because the record's framing owns `type` as
the discriminator), `research_progress`
(the `tag` beside its parsed `payload`), `upstream_event` (an unknown tag's name and raw
payload text, or the not-yet-pinned `answer` tag; when the tag's server-sent-event block
carried an `id`, a `retry`, or an `event` name, the record preserves them as the optional
`sse_id`, `sse_retry_ms`, and `event_name` members, each present exactly when the block
offered it), and `warning`. The `usage` tag emits no
event record — its payload feeds the terminal record, while its parse notes ride as one
`warning` record each ahead of the summary. A successful stream closes with exactly one
`summary` record: the open exchange's `http_status` and observed identifiers, the
`deltas_emitted`/`citations_seen`/`entities_seen` counts, `cost_unknown` — true exactly
when the stream ended without a final usage event, so an unreported cost never reads as
free — and, exactly when a final usage event arrived, the `usage` member at exact decimal
scale. A failed stream closes
with one `error` record carrying the failure code (`INTERRUPTED` for SIGINT, `TERMINATED`
for SIGTERM, the shared table otherwise), the partial counts, `cost_unknown`: true exactly
when no final usage arrived — an unreported cost never reads as zero — and the open
exchange's observed `rate_limits` windows, which a live open always has. When the downstream
consumer
departs mid-write, the final JSONL line may be truncated at the consumer's departure and
the process still exits `0` silently — the broken-pipe rule of every streaming mode.

### JSON (buffered)

A streaming JSON invocation buffers the whole decoded event stream and emits exactly one
LF-terminated envelope at completion. `data.upstream` is the ordered array of decoded
stream events — a streaming exchange has no single JSON body, so the array of decoded
events (each entry carrying `event_index`, its `kind`, and the event's exact text or
payload) is the lossless representation this CLI defines; the buffered accumulation is
bounded by a 16 MiB ceiling whose breach is the malformed failure (exit 8). The ceiling
counts the decoded payload bytes of the accumulated events, so peak heap use during a
buffered run is a rough multiple — two to three times — of the counted bytes.
`data.projection` carries the assembled `answer`, `delta_count`, and the citation and
entity projections, pinned by `schemas/v1/answers-streaming.schema.json` — a streamed
citation is projected on arrival, so unlike the blocking form its `url` is optional and
its `number` may be zero; `meta.usage` carries the usage observed in the final stream events.
Buffering hides the progress every other mode streams, so `meta.warnings` carries the
advisory `streaming answers with --output json buffer until completion; jsonl is
preferable for progress` — beside any parse notes the final usage tag collected — and on
machine stdout nothing but the envelope is written. The
streaming envelope satisfies both `envelope-success.schema.json` and the pinning
`envelope-success-streaming.schema.json`.

### Raw

Raw writes the exact decoded SSE body bytes as they arrive — each chunk flushed, no
parsing, no added LF — and stdout stays byte-identical to the served stream. Raw streams
emit no progress lines and stay silent on stderr on success.

## Places command

`brave-search places search [query]` is the first subcommand of the `places` group: a
local-place search over `GET /local/place_search`. The query is always optional, and
the invocation's anchor decides the mode — a query-less run with an anchor is Explore
mode; a query-less, anchor-less run is a valid broad global search that the CLI never
rejects locally, because Brave sources its results more broadly than the anchored
forms.

- The anchor is exactly one spelling: the paired `--latitude`/`--longitude` (both
  required together, latitude −90..90, longitude −180..180, edges included, with `NaN`
  and the infinities failing conversion), the
  place-name `--location` (US `city state country`, others `city country`,
  case-insensitive, no commas), or neither. The coordinate pair supplied together with
  `--location` is a usage error, as is one coordinate alone.
- `--location` is rejected at the option boundary when it carries CR, LF, NUL, or any
  other control character, or when it begins or ends with whitespace — nothing
  adversarial may ride a place-name string onto the wire.
- `--geoloc <lat>x<long>` is the device-geolocation hint: both components are
  plain decimals — an exponent spelling such as `1e2` is refused, and each component
  carries at most 100 fraction digits and 1,000 significant digits — range-checked
  exactly like the anchor coordinates, and the value serializes exactly as
  `latitudexlongitude`, keeping its given decimal scale.
- `--radius <meters>` accepts every finite plain decimal at zero or greater and is a
  ranking bias, not a hard boundary — the human listing says so whenever a radius was
  requested; `NaN` and the infinities fail conversion, and so does every exponent
  spelling or digit count beyond the shared decimal bounds (100 fraction digits,
  1,000 significant digits), because a decimal's wire form is its plain rendering and
  an exponent spelling would explode a tiny option value into a giant digit string.
- `--count <1..100>` budgets the total across every response bucket, not only
  `results`; the endpoint's default of 20 stays upstream's decision through omission.
- Documented shared spellings: `--country` (a two-letter code — the places endpoint
  documents no region-wide `ALL`), `--search-lang`, `--ui-lang`, `--safe-search`
  (`off|moderate|strict`), and the tri-state `--spellcheck`. `--freshness` and
  `--page` are refused with the usage exit (2) before any dispatch; the goggle
  family, the page-walk flags, and the web location header group are unknown options.
- `--units metric|imperial` selects the measurement system of distance-bearing
  results; unsupplied stays omitted.
- Place search is a single non-paginated GET; `--output raw` stays valid. Place ids
  are opaque and ephemeral (roughly eight hours); the CLI never validates their
  internal format.
- Wire mapping and the GET-only rule: `docs/brave-api-contract.md#places-search-wire-form`.

## Places command output

One place search response carries several buckets — `results`, `cities`, `countries`,
`regions`, `neighborhoods`, `addresses`, `streets` — and any bucket may be null or
omitted. The projection enumerates every documented bucket in that order, so one
logical listing spans them all: each entry carries its own bucket word and its
original zero-based position inside that bucket's array. The `mixed` ordering hints
and every unknown top-level member are ignored; per entry the projection carries the
opaque `id`, the `title`, the `address`, the textual forms of the `rating` block's
`rating_value` and `rating_count` (numbers keep their exact textual form), the
`distance`, and the `phone` and `website` contact members — everything else the
response carries is deliberately not projected.

The human listing heads with the mode's own heading — `Places for: <query>`,
`Places near: <anchor>` (the coordinates, place name, or geoloc hint), or `Places
everywhere` for the broad global search — and numbers the places of every bucket in
one sequence with their member lines indented:

```text
 1  <title or (no title)>
    <address>
    <phone>
    <website>
    Rating: <rating_value> (<rating_count> reviews)
    Distance: <distance>
```

Each member line appears only when the place carried it: the address and phone wrap
like every body text, the website renders dim and never wrapped, and the rating and
distance members render as the entry's final dim metadata lines — the rating line's
review count appears only with it. The count line counts places across every bucket
(`3 places.`), the advisory quota footer may follow it, an empty response renders the
single `No places.` line, and a requested radius appends the note `Radius biases
ranking; it is not a hard boundary.` after the count line and footer.

The JSON projection carries `result_count`, `page` (always 1), `upstream_offset`
(always 0), and `results` — one object per place with `position`, `bucket`, and the
usable members above. JSONL result records carry `position`, `bucket` (the entry's
own response bucket), `id`, and the usable members; the terminal summary record
carries the web summary's field set with the total across every bucket. Field
inventories are normative in `schemas/v1/places-result.schema.json` and
`schemas/v1/places-summary.schema.json`, referenced from
`schemas/v1/jsonl-record.schema.json`.

## Places details and describe

`brave-search places details <id>...` and `brave-search places describe <id>...`
enrich places by their opaque ids over `GET /local/pois` and `GET
/local/descriptions`. The ids are each command's whole grammar: one or more
positional ids — `--` lets an id start like an option — no search option applies to
this endpoint family, so every shared search spelling, every page-walk flag, and the
goggle family are unknown options here.

- **Chunking:** the endpoints accept up to 20 ids per request; one invocation accepts
  1 through 200 ids and automatically chunks larger lists into consecutive requests
  of at most 20 ids in input order. Forty-five ids compose requests of 20, 20, and
  5; 200 ids compose exactly ten. The chunks travel strictly sequentially under the
  shared multi-request walk: before each subsequent chunk the latest rate-limit
  snapshot is inspected, and a window with remaining 0 paces the next chunk behind a
  cancellable wait that runs until that window's reset instant — anchored at the
  observation of its headers, so only the reset still outstanding at wait start is waited —
  and the paced request reports `waited_ms` in the JSON envelope's upstream entry.
- **Order reconstruction:** duplicate input ids are preserved and the input order is
  reconstructed by matching returned ids: a returned id fills every input position
  that carries it, and a missing or expired id keeps its original position as a
  placeholder. The machine representation of a placeholder is `present:false` beside
  the position and id; the human listing renders `(not returned)` under the id.
- **Invocation cap:** the 201st id is a usage error (exit 2) before any dispatch.
- **Partial failure:** a later chunk failure follows the pagination family's rules
  exactly, with requests counted where that family counts pages: JSONL keeps the
  already-streamed records of completed chunks and ends in one counted error record
  (`requested_requests`/`received_requests`); human and JSON buffer and emit no
  result payload — only the failure document with the counts — and every mode keeps
  the failed request's `4`–`8` exit code. The walk's own diagnostic for a cancelled
  or interrupted pacing wait names the next chunk in the page vocabulary of the
  shared walk machinery.
- **Raw rejected:** an enrichment invocation fans out, so `--output raw` is the
  single-request channel and is refused with the usage exit (2) before any dispatch.
- Ids are opaque and ephemeral (roughly eight hours) and interchangeable with web
  search location result ids; the CLI never validates their internal format.
- Wire mapping: `docs/brave-api-contract.md#place-enrichment-wire-form`.

## Places details and describe output

Every rendered form is one record or block per input position, in input order — a
duplicate id appears at each of its positions, and a missing or expired id appears at
its original position as a placeholder.

The human listing heads with `Place details for N ids` or `Place descriptions for N
ids` and numbers every input position: a present detail block heads with the entry's
title (or the id when the entry carried none) and shows the id beside it plus the
usable members — url, description, display address, phone, email, price range,
timezone, thumbnail; a present description block heads with the id and shows the
AI-generated text. A placeholder block heads with the id and shows `(not returned)`.
The count line always includes the missing count: `4 ids, 3 returned, 1 not
returned.`

The JSON projection carries `id_count`, `returned_count`, `missing_count`,
`requested_requests`, `received_requests`, and `results` — one object per input
position with `position`, `id`, `present`, and the usable payload members when
present. The JSON envelope's `data.upstream` is the ordered per-request array of the
completed chunks, the multi-request form. JSONL streams one result record per input
position as each chunk completes — `position`, `bucket` (`details` or
`descriptions`), `id`, `present`, then the payload members — and ends in one summary
record carrying the same counts as the projection plus `http_status` and, when
offered, `request_id` and `api_version`. Field inventories are normative in
`schemas/v1/places-details-result.schema.json`, `places-details-summary.schema.json`,
`places-describe-result.schema.json`, and `places-describe-summary.schema.json`,
referenced from `schemas/v1/jsonl-record.schema.json`.

## LLM context options

`brave-search context <query>` retrieves LLM-ready search context for one query as one
single non-paginated request: `--output raw` stays valid, and the page-walk flags of the web
command are unknown spellings here.

```text
--count <1..50>
--max-urls <1..50>               default follows Brave
--max-tokens <1024..32768>       default follows Brave
--max-snippets <1..256>          default follows Brave
--max-tokens-per-url <512..8192> default follows Brave
--max-snippets-per-url <1..100>  default follows Brave
--threshold strict|balanced|lenient|disabled
--source-metadata / --no-source-metadata
--local auto|on|off
--country <two-letter code>
--search-lang <tag>
--safe-search off|moderate|strict
--freshness pd|pw|pm|py|YYYY-MM-DDtoYYYY-MM-DD
--loc-lat <decimal>              pairs with --loc-long, -90 to 90
--loc-long <decimal>             pairs with --loc-lat, -180 to 180
--loc-city <text>
--loc-state <code>
--loc-state-name <text>
--loc-country <code>
--loc-postal-code <text>
```

- Every numeric bound is enforced locally at both edges; an unsupplied budget is omitted
  from the wire, because the Brave defaults are calibrated upstream and never coerced into
  explicit values by this CLI.
- Coordinates must arrive paired and range-checked, with `NaN` and the infinities failing
  conversion; location headers are never inferred
  from the host. Context accepts exactly the seven documented location headers — latitude,
  longitude, city, state, state name, country, postal code — and `--loc-timezone` is a
  usage error (exit 2) before any dispatch: this endpoint documents no timezone header.
- `--country` accepts exactly two letters, so `ALL` — documented only for other endpoints —
  is refused. `--search-lang` accepts documented language subtags from two characters up
  (`en`, `en-US`, `zh-Hans`) without forcing length two. `--safe-search` unset means no
  filtering upstream; `--local auto` and an absent `--local` both omit `enable_local` so
  Brave auto-detects from the presence of location headers.
- The shared search grammar's `--page`, `--ui-lang`, and `--spellcheck` are not documented
  for context: supplying any of them is a usage error (exit 2) naming the supplied spellings,
  decided before any dispatch. Every web-only option (pagination, Goggles, decorations,
  filters, units) is an unknown spelling.
- Wire mapping: `--count→count`, `--max-urls→maximum_number_of_urls`,
  `--max-tokens→maximum_number_of_tokens`, `--max-snippets→maximum_number_of_snippets`,
  `--max-tokens-per-url→maximum_number_of_tokens_per_url`,
  `--max-snippets-per-url→maximum_number_of_snippets_per_url`,
  `--threshold→context_threshold_mode`, `--source-metadata→enable_source_metadata`,
  `--local→enable_local`, and the shared country, search language, SafeSearch, and freshness
  names; the location group travels as the seven `X-Loc-*` headers.
- The upstream documents a `goggles` field for this endpoint, but this CLI deliberately
  exposes no goggle options on context; the option set above is the whole grammar.
- The request is GET with percent-encoded parameters unless the fully encoded URI would
  exceed 8,000 bytes, in which case the same fields travel as one POST JSON body.

## Context command output

The context command is a remote single-request command: its output-mode compatibility,
warning routing, failure rendering, raw passthrough, and machine framings are the shared
contract above; this section fixes the context-specific shapes.

### Human rendering

Success is UTF-8 text, every line LF-terminated, styled only under the shared human
output sections: one bold heading line `Context for: <query>`, one blank line, then
every usable passage of the `grounding.generic[]` array in presentation order — each
passage word-wrapped at the render width and keeping its internal line breaks, passages
separated by one blank line — then one trailing count line `<S> snippets across <U>
sources.` (singular forms `1 snippet across 1 source.`) and the advisory quota footer
when the exchange earned one. A body whose generic array yields no usable passage
renders exactly one line: `No context.`

### JSON projection

`data.projection` of a context success carries exactly `snippet_count` — the usable
`grounding.generic[]` passages — and `source_count` — the entries of the `sources` map.
`data.upstream` stays the lossless tree, including the optional `grounding.poi` and
`grounding.map[]` local-recall members and the per-source metadata when served. The
documented response shape provides no token estimate, so none is invented in the projection.

### JSONL records

A successful exchange emits exactly one `result` record of bucket `context` — the whole
context document is one logical result — carrying `content`, the usable passages joined with
LF in presentation order; an exchange with no usable passage emits the summary alone. The
terminal summary record carries `snippet_count`, `source_count`, `http_status`, and the
request identifiers the response offered. Field inventories are normative in
`schemas/v1/context-result.schema.json` and `schemas/v1/context-summary.schema.json`,
referenced from `schemas/v1/jsonl-record.schema.json`.

## Goggles and site shortcuts

The web command accepts four mutually exclusive goggle input strategies; endpoints that
document no Goggles reject every one of these spellings as a usage error (exit 2):

```text
--goggle <url-or-inline>      repeatable; an absolute http(s) URL is a URL reference,
                              any other value is an inline definition
--goggle-file <path>          repeatable; one regular UTF-8 file of at most 2 MiB
--include-site <domain>       repeatable; mixes only with --exclude-site
--exclude-site <domain>       repeatable; mixes only with --include-site
```

- **Exclusivity**: supplying values of two different strategies — or of all three — is a
  usage error (exit 2) decided before any file is read and before any dispatch.
- **Maximum of three**: each strategy alone yields at most 3 goggles, the documented
  upstream maximum. Site shortcuts compile into exactly one inline goggle — one
  `+site:<domain>` rule per included domain, then one `-site:<domain>` rule per excluded
  domain, in their given order — so the strategy can never exceed it. A fourth `--goggle`
  or `--goggle-file` is a usage error naming the supplied count and the maximum.
- **Site injection safety**: site values must be IDNA-normalizable domains, normalized
  with strict `IDN.toASCII` under `USE_STD3_ASCII_RULES` and lowercased. Schemes, paths,
  and ports (any `: / \ ? # @`), commas, `$`, whitespace, newlines, control characters,
  and empty domain labels are rejected, so a site value can only contribute one inert
  domain token and no goggle DSL structure can be smuggled through it.
- **File bounds**: `--goggle-file` accepts only paths that resolve (symlinks included) to
  existing regular files; FIFOs, devices, directories, and missing paths are refused
  before a byte is read. Content is read through a 2 MiB bound — one byte more is a usage
  error naming the path and the byte size — and must decode as strict UTF-8. The loaded
  text is validated against the documented inline limits: at most 100,000 instructions
  (a nonblank line that is not a `!` comment line), at most 500 code points per
  instruction, at most 3 wildcards and 3 carets per instruction (a conservative pinned
  bound), and no control character other than newline and tab.
- **Redaction**: goggle content — inline values and file text alike — never appears in
  logs, errors, or any diagnostic. Rejections name the rule, the counts, and at most a
  path plus byte length; no usage message quotes a rejected value, and the mutual-exclusion
  rejection names only the strategies the invocation actually supplied. Debug output
  reports a file-derived goggle as exactly one `--verbose` diagnostics line carrying its
  source path and byte length, nothing else.

The wire behavior these inputs produce — the GET form for URL references, the POST form
with the JSON body for inline definitions and long URIs — is owned by
`docs/brave-api-contract.md#goggles-and-the-post-form`.

## Completion command

`brave-search completion bash|zsh` prints one completion script to stdout and exits 0. The
script is generated by picocli's generator from the fully registered command model, then
pinned to one byte order by sorting the generated option-name lists, candidate
declarations, and previous-word case blocks, because the model's own option order
originates in JVM reflection and is unspecified across Java runtimes; the pinned bytes are
a function of the grammar alone. The bash form is the generator's script verbatim in that
canonical order; the zsh form prepends a `#compdef` registration header to the same body,
which zsh's `bashcompinit` emulation executes when sourced. Output is LF-only text ending
in exactly one line feed, and carries only names from the grammar — subcommands, options,
and their documented value sets — never a value from the environment: a run under
sentinel-bearing credential variables prints no sentinel material.

The shell argument accepts exactly one value, `bash` or `zsh`; a bare invocation, a second
positional, any other shell's name, or a machine-output flag is a usage error (exit 2)
before any script is printed, and the hidden `--base-url` override is offered by neither
form. Packaged copies ship as `completions/brave-search.bash` and
`completions/brave-search.zsh` resources byte-identical to the command's output, and both
the printed and packaged scripts pass `bash -n` and `zsh -n`; when the grammar changes,
the packaged copies are regenerated from the command and reviewed through the byte-identity
test.

The script travels through the byte-lossless result channel of the exit-code table's
output-I/O row, so a failed write keeps the operating system's own identity: piping the
script into an early-exiting consumer such as `head -1` is the silent broken-pipe success
(exit 0), while any other stdout write failure exits 6 with exactly one stderr diagnostic
line.

## Version command

`brave-search version`, `--version`, and `-V` print exactly one LF-terminated line —
`brave-search <version>`, with the version read from the generated version resource — to
stdout, write nothing to stderr, and exit `0`; all three spellings print identical bytes.
Version is a local command of the output-mode compatibility table: `--output` and
`--pretty` are rejected before any effect with exit `2`, exactly like help and completion.

## ANSI enablement

Color is a conservative stdout decision. Human documents style exactly the elements of
the human output styles section — headings and entry titles bold through `ESC[1m` …
`ESC[0m`, urls, per-type metadata, and the quota footer dim through `ESC[2m` …
`ESC[0m` — and only when the invocation's color is enabled: the process console exists
and reports itself a terminal through `Console.isTerminal()`, `NO_COLOR` is unset or
empty, `TERM` is not `dumb`, and `--no-color` was absent. A missing console, a console
that is not a terminal, a failing probe, or any other uncertainty leaves color off: a
maybe never enables, and the layout stays byte-identical with zero escape bytes.
Machine documents never carry escapes regardless of the decision. The render width of
the human layout follows the human output width section — the exported `COLUMNS` when
sane, otherwise 100 — read by the same single terminal-detection seam as the color
decision.

The streams are treated by what was proven about each: the process-wide `Console` API
cannot distinguish a redirected stderr from a terminal one, so stderr diagnostics carry
no styling except the one labeled surface of the styles section — the research progress
lines of a streaming answers run, whose bold labels ride the same process-wide color
decision and stay plain behind any pipe — and stdout styling follows stdout's own
terminal proof — a console-attached process whose stdout leaves through a pipe keeps its
listing plain, which the stdout-pipe process test pins beside the positive
pseudo-terminal proof. Local commands (help, version, completion, config) keep fixed
plain human text; their contracts carry no styling.

## Help snapshots

Every command's usage help — root, each remote command, each `places` and `config`
subcommand, `completion`, and `version` — is pinned by a golden snapshot rendered from the
assembled process command line, so an unannounced grammar change fails as a diff. The
snapshots carry the full option inventory, the hidden `--base-url` override never appears,
and the remote-only output options (`--output`, `--pretty`, `--timeout`) appear only under
remote commands, where the grammar itself places them after the remote subcommand token.

The `--` end-of-options separator is documented where it applies: in the parameter
description of every positional-accepting command ("quote it and use -- when it starts
like an option"), rather than in a duplicated footer, so the guidance stays beside the
argument it protects. Numeric limits stay visible where users pick values — the place id
budget in the `details`/`describe` positional descriptions, the page and walk-budget
ranges in the `<1..10>` parameter labels.

## Deferred contract notes

Where the unfinished edges of this contract will settle, recorded so consumers can see them
coming:

- Upstream metadata headers that are malformed, negative, or misaligned are dropped or
  clamped where the headers are parsed, nonfatally with notes; the metadata diagnostic lines
  reach a human output channel when a verbose flag exists to carry them.

## Pagination

`--all-pages` opts a search command into a sequential multi-request page walk: web, news,
and videos. `--max-pages <1..10>` bounds the walk (default 10, the documented maximum
user-facing page); it requires `--all-pages`, and `--page` together with `--all-pages` is a
usage error (exit 2, before any request). `--output raw` is rejected the same way — raw is
the single-request channel, so `--all-pages` switches the command to the multi-request
output family where raw never validates; the rejection happens before dispatch with zero
upstream requests.

Requests are strictly sequential — page N+1 never starts before page N completed — so a walk
never bursts a user's quota. Web continues only while the completed body's top-level
`query.more_results_available` reads boolean `true`; false, absent, unreadable, or lookalike
values stop the walk after their page, and a short page is never read as exhaustion. News
and videos document no continuation field at all: their walks request every page of the
budget — through the user-facing page 10 unless bounded lower by `--max-pages` — and never
treat a short page as exhaustion. The `--max-pages` budget always caps every walk.

The walk deduplicates its logical results by the exact API-provided URL string — no
decoding, lowercasing, fragment stripping, or query-parameter dropping, because any
transformation could merge distinct resources — keeping first-seen order. An entry whose URL
is missing, null, or empty is retained on every page and never deduplicated. Every mode
reports the counts: `requested_pages` (the page budget, whether or not the walk stopped
early), `received_pages` (the completed pages, which is also the completed request count),
and `duplicates_removed`.

Rate pacing: before each subsequent request, the most recent rate-limit snapshot is
inspected; any window with remaining `0` and a nonzero limit (a documented limit of `0` is
an unlimited window) paces the next request behind a cancellable wait that runs until the
latest such window's reset instant — the instant the headers were observed plus the
window's reset, because the reset counts from the observation, never from the wait — so the
wait spans only the reset still outstanding when it starts, and a reset the previous page's
body read already outlived waits nothing. The wait performed is exposed as `waited_ms` on
that request's upstream entry.

Interruption: a walk interrupted by SIGINT ends as its mode's transport-failure document —
human's one stderr diagnostic and json's `ok:false` envelope carry the completed-page
counts, jsonl ends its already-streamed records in the counted `error` record — while the
process itself exits `130` by the run's cancellation latch, which is authoritative over the
rendered failure kind. The interrupt is observed at the walk's cancellation points: before
each subsequent request and inside every pacing slice, so a pacing wait ends within a
slice. An in-flight request's body read is not interrupted mid-read; the walk observes the
latch at the cancellation point after that page completes. A downstream consumer closing the
pipe during the walk — the `head -1` over a paged JSONL stream — is the documented silent
early termination: the run latches the broken-pipe cause, writes nothing further, and exits
`0`; any other output-write failure during the walk keeps the transport status (exit 6)
with one stderr diagnostic.

Aggregate memory: a walk buffers what it aggregates. Each completed page contributes its
bounded body (up to the 16 MiB decoded ceiling) and its parsed tree, and a maximal walk of
ten pages holds all of them plus the aggregate until the process ends. The single-shot CLI
accepts this footprint; library consumers embedding the walk should size for it.

Partial failure — the first failed page aborts the walk and its kind owns the exit status
(4–8):

| Mode | Behavior on a later page failure |
| ---- | -------------------------------- |
| human | One stderr line — the failure plus `(N of M requested pages completed)` — and no result payload. |
| json | Exactly one `ok:false` envelope; `error.details` is `{"requested_pages": M, "received_pages": N}`; the failing exchange's rate windows ride `meta.rate_limits`. |
| jsonl | The result records already streamed from completed pages stand; the stream ends in one `error` record carrying the same two count fields and, exactly when the failing exchange observed a snapshot, its `rate_limits` windows. |
| raw | Unreachable: raw was rejected before dispatch. |

Human and json buffer the aggregate, so a failed walk emits no partial results at all;
jsonl is the streaming channel, emitting each completed page's deduplicated records the
moment the page completes.

Success shapes:

- Human: the heading `Web results for: <query>` — suffixed ` (pages 1-N)` when more than one
  page was received — then the deduplicated numbered listing, then one counts line:
  `<R> results across <N> of <M> requested pages, <D> duplicates removed.` (singular forms
  for `1 result` and `1 duplicate removed`; a zero-result walk prints the counts line
  alone).
- JSON: one envelope whose `data.projection` carries exactly `result_count`,
  `requested_pages`, `received_pages`, `duplicates_removed`, and `results` — one object per
  retained result with `page` (its user-facing page of origin), `position` (its original
  zero-based index in that page's upstream array), and the usable textual members. The
  single-request `page`/`upstream_offset` projection fields are deliberately absent: with
  several pages they are ambiguous, and per-result `page` carries the provenance.
- JSONL: one `result` record per retained result, streamed per completed page, carrying
  `position`, `page`, `bucket`, and the usable textual members (pinned by
  `schemas/v1/web-paged-result.schema.json`); the terminal `summary` record carries
  `result_count`, `requested_pages`, `received_pages`, `duplicates_removed`, `http_status`
  of the most recent exchange, and that exchange's offered identifiers (pinned by
  `schemas/v1/web-paged-summary.schema.json`).

The multi-request upstream array: a multi-request command stores `data.upstream` as an
ordered array of `{request_index, meta, body}` objects — one per completed request, in
request order. `request_index` is the zero-based index inside the invocation; `meta` carries
that exchange's `request_id`, `http_status`, `api_version`, `rate_limits`, `usage`, and the
optional `waited_ms`; `body` is the lossless decoded tree of that response. The array form
is the discriminator of a multi-request invocation — a single-request command always emits
the bare body value — and the element shape is pinned with `additionalProperties: false` by
`schemas/v1/upstream-entry.schema.json`, referenced from `envelope-success.schema.json`.
