# Brave API wire contract

Normative contract of the HTTP exchange with the Brave Search API for major version 1: the
base protocol every request speaks, the bounded response pipeline, gzip handling, error
decoding and classification, and the loopback rules that let contract tests run without live
credentials. `docs/contract-index.yaml` maps every stable requirement identifier to its
section here and to an executable test; the CLI-facing meaning of each failure category is
the exit-code table in `docs/cli-contract.md`.

## Base protocol

| Aspect | Rule |
| ------ | ---- |
| Production origin | `https://api.search.brave.com/res/v1` — the only origin stored production credentials ever reach. |
| Authentication | `X-Subscription-Token` on every endpoint call, Answers included; bearer authentication is not used. |
| Accept | `application/json` for JSON endpoints, `text/event-stream` for streaming Answers. |
| Accept-Encoding | `identity` in raw response mode; `gzip` in parsed modes, decoded explicitly by this client because the JDK `HttpClient` performs no transparent decompression. |
| User-Agent | `brave-search/<version>` from the product resource, configurable inside the HTTP adapter because the upstream may recommend browser-style agents for device-specific behavior. |
| Api-Version | Sent as `Api-Version: YYYY-MM-DD` only when the user pins a version. |
| Content-Type | `application/json` exactly on a POST carrying its JSON body; never on a bodyless GET. |
| Redirects | `HttpClient.Redirect.NEVER` — every 3xx is classified as an upstream failure, never followed, because following a `Location` would move a token-bearing request to an arbitrary origin. |

Header assembly is deterministic: token, accept, accept-encoding, user-agent, then the
optional API version pin and the POST content type, in that order. The token header value
travels as ISO-8859-1 bytes — the JDK client's wire alphabet for every header value — so a
credential carrying any character above U+00FF is rejected once, typed, as a
local-configuration failure before any exchange, never assembled into a request that would
die at send time.

## Origin and loopback credential routing

A hidden `--base-url` override exists for tests and proxies and accepts only literal loopback
HTTP(S) origins — `127.0.0.1`, `[::1]`, or `localhost` resolved once, verified loopback, and
rewritten to a selected literal — with no user info, query, fragment, or dot-segment
traversal. Stored production credentials are sent only to the exact production origin, so a
loopback override never receives them: loopback exchanges require `BRAVE_SEARCH_TEST_KEY`
explicitly, and its value may be a placeholder when a local proxy injects the real key. This
environment seam is the intended path for any test or proxy run that needs to authenticate;
it never reads, echoes, or stores the production credential. Origin validation rejects
everything outside these spellings — uppercase or IDNA hosts, non-http(s) schemes, dot
segments — as usage failures.

## Request building

Every query key and value is percent-encoded independently and strictly: only unreserved
characters pass through, a space becomes `%20` (never `+`), reserved characters are escaped,
and the bytes are UTF-8 so multibyte values round-trip unchanged. Parameters keep insertion
order, duplicates included, so identical inputs compose byte-identical URIs. Redacted
renderings keep scheme, host, path, and parameter names but replace every value with a fixed
marker; token-bearing headers are never rendered on any diagnostic channel.

## Goggles and the POST form

The wire field is `goggles` — a URL reference or an inline definition, single value or a
list of at most 3 per request. The deprecated `goggles_id` parameter is never sent.

- **GET form**: only URL-reference goggles ride the URI, one repeated `goggles` parameter
  per goggle in the invocation's given order, percent-encoded like every other value.
  Inline definitions never ride a GET.
- **POST trigger**: the request switches to `POST /web/search` with a JSON body exactly
  when an inline goggle is present or the fully encoded GET URI would exceed 8,000 bytes;
  at exactly 8,000 bytes the GET stays. A POST target carries no query at all.
- **POST body**: the same fields the GET would carry, serialized as one JSON object in the
  shared alphabetical wire order, with numbers (`count`, `offset`) and booleans as native
  JSON members, `result_filter` as its single joined string, and every goggle — URL
  references and inline definitions alike — as the members of one `goggles` array in the
  given order. `Content-Type: application/json` rides exactly on this POST (see the base
  protocol) and `Accept` stays `application/json`. Field serialization is equivalent
  between the methods, proven field for field by contract tests; the only inherent
  differences are the encoding of numbers as JSON numbers and the collapse of repeated
  `goggles` parameters into the body array.

CLI-side input rules behind the wire field: the four input strategies (`--goggle`,
`--goggle-file`, `--include-site`/`--exclude-site`) are mutually exclusive, site shortcuts
compile into one inline goggle so the maximum of three holds, inline text and file content
are validated against the documented limits — at most 100,000 instructions per definition
(an instruction is a nonblank line that is not a `!` comment line, the enforceable
approximation of the upstream count), at most 500 code points per instruction, at most 3
wildcards and 3 carets per instruction (a conservative pinned bound; the upstream document
records the limits without their numbers), and no control character other than newline and
tab. The input rules and their redaction policy are owned by
`docs/cli-contract.md#goggles-and-site-shortcuts`.

## News search wire form

The news endpoint is `GET /news/search` (POST per the shared trigger below) and carries
exactly the documented news inventory, alphabetical by wire name: `q` (required),
`search_lang`, `ui_lang`, `country`, `safesearch` (`off|moderate|strict`), `count`
(1–50), `offset` (the zero-based page index 0–9), `spellcheck`, `freshness` (the web
enum), `goggles` (the shared wire field and its GET/POST rules above), `operators`,
`extra_snippets`, and `include_fetch_metadata`. Every unsupplied value is omitted rather
than coerced to an upstream default. The news endpoint documents no location header group,
so no `X-Loc-*` header and no timezone ever ride a news request.

News-specific spellings: `country` accepts a two-letter code or the region-wide `ALL` and
carries it unchanged; the user-facing `--page 1..10` maps to `offset = page - 1`. The GET
and POST forms serialize the field inventory equivalently, proven field for field by
contract tests; the POST trigger and body shape are the shared goggles-and-POST-form rules.

## Videos search wire form

The videos endpoint is `GET /videos/search` (POST per the shared trigger below) and carries
exactly the documented videos inventory, alphabetical by wire name: `q` (required),
`search_lang`, `ui_lang`, `country`, `safesearch` (`off|moderate|strict`), `count`
(1–50), `offset` (the zero-based page index 0–9), `spellcheck`, `freshness` (the web
enum), `operators`, and `include_fetch_metadata`. Every unsupplied value is omitted rather
than coerced to an upstream default. The videos endpoint documents no Goggles and no
`extra_snippets`, so neither field ever rides a videos request in either method; it also
documents no location header group, so no `X-Loc-*` header and no timezone ever ride one.

Videos-specific spellings: `country` accepts a two-letter code or the region-wide `ALL`
and carries it unchanged; the user-facing `--page 1..10` maps to `offset = page - 1`.
Because no inline Goggle can exist, the 8,000-byte encoded-URI rule is the sole POST
 trigger. The GET and POST forms serialize the field inventory equivalently, proven field
 for field by contract tests; the POST body shape is the shared one with numbers and
 booleans as native JSON members.

## Images search wire form

The images endpoint is `GET /images/search` — GET only, with no POST form: the upstream
reference documents no POST for images, so the shared 8,000-byte rule never switches
methods here and an unusually long encoded value rides the GET URI exactly as assembled.
It carries exactly the documented images inventory, alphabetical by wire name: `q`
(required), `count`, `country`, `safesearch`, `search_lang`, and `spellcheck`. Every
unsupplied value is omitted rather than coerced to an upstream default. The images
endpoint documents no `ui_lang`, no `freshness`, no `operators`, no Goggles, and no
location header group, so none of those ever ride an images request; it also documents no
`offset` — one request is one non-paginated exchange whose result breadth comes from
`count` alone.

Images-specific spellings: `country` accepts a two-letter code or the region-wide `ALL`
and carries it unchanged; `count` reaches 1–200; `safesearch` accepts exactly `off` or
`strict` (the images endpoint documents no `moderate` level, and the CLI rejects that
spelling locally with the usage exit).

## Suggest search wire form

The suggest endpoint is `GET /suggest/search` — GET only, with no POST form: the live
reference documents no POST for suggest, so the shared 8,000-byte rule never switches
methods here. It carries exactly the documented suggest inventory, alphabetical by wire
name: `q` (required), `count` (1–20; Brave's default of 5 stays an upstream decision, so
an unsupplied count is omitted rather than coerced), `country`, `lang`, and `rich`
(default `false`; unsupplied stays omitted). Every unsupplied value is omitted rather
than coerced to an upstream default.

Suggest-specific spelling: the language option travels under the wire name `lang` —
never the search verticals' `search_lang`. The CLI's shared `--search-lang` flag is the
accepted alias that binds to this wire name; no `--lang` CLI spelling exists. The
country accepts a two-letter code or the region-wide `ALL` and carries it unchanged.

## Spellcheck search wire form

The spellcheck endpoint is `GET /spellcheck/search` — GET only, with no POST form: the
live reference documents no POST for spellcheck, so the shared 8,000-byte rule never
switches methods here. It carries exactly the documented spellcheck inventory,
alphabetical by wire name: `q` (required), `country`, and `lang` — and nothing else; no
`count`, no `rich`, no SafeSearch, no freshness, and no offset ever ride a spellcheck
request. Every unsupplied value is omitted rather than coerced to an upstream default.

Spellcheck-specific spelling: like suggest, the language option travels under the wire
name `lang` — never `search_lang` — with the CLI's shared `--search-lang` flag as the
accepted alias. The country accepts a two-letter code or the region-wide `ALL` and
carries it unchanged.

## Context wire form

The LLM-context endpoint is `GET /llm/context` (POST per the shared trigger below) and
carries exactly the documented context inventory, alphabetical by wire name:
`context_threshold_mode` (`strict|balanced|lenient|disabled`), `count` (1–50),
`country`, `enable_local`, `enable_source_metadata`, `freshness` (the web enum),
`goggles` (the shared wire field; the upstream documents it, the CLI deliberately
exposes no goggle options here), the `maximum_number_of_*` budget family (`…of_urls`
1–50, `…of_tokens` 1024–32768, `…of_snippets` 1–256, `…of_tokens_per_url` 512–8192,
`…of_snippets_per_url` 1–100), `q` (required), `safesearch`, and `search_lang`. Every
unsupplied value is omitted rather than coerced to an upstream default, because the
budget defaults are calibrated upstream. The request is one single non-paginated
exchange.

Context is the one endpoint whose location group rides headers rather than parameters:
exactly the seven documented `X-Loc-*` headers — latitude and longitude (paired,
range-checked like every coordinate), city, state, state name, country, postal code —
and no timezone header exists on this endpoint, so none is ever sent. The GET and POST
forms serialize the field inventory equivalently under the shared rules; the CLI-facing
grammar, local bounds, and output shapes are owned by
`docs/cli-contract.md#llm-context-options`.

## Rich callback wire form

The rich endpoint is `GET /web/rich` — GET only, with no POST form: the checked-in
upstream contract documents GET only for rich callbacks, so the shared 8,000-byte rule
never switches methods here. The request carries exactly one query parameter,
alphabetical trivially: `callback_key` (required) — the opaque key an earlier web
search that enabled rich callbacks issued. The search verticals' `q` never rides this
request, and no other parameter, default, or coercion ever joins the key, because the
endpoint documents none.

The upstream contract documents no reference page for this endpoint (the guessed
`/api-reference/web/rich/get` answers 404) and no format rules for a callback key, so
the key is carried verbatim and percent-encoded independently under the shared strict
RFC 3986 rules — reserved characters such as `& = ?` inside a key can never split it
into a second parameter. The response shape is undocumented too; the CLI's generic,
lossless treatment of it is pinned in `docs/cli-contract.md#rich-command-output`.

## Answers wire form

The answers endpoint is `POST /chat/completions` — POST only, with
`Content-Type: application/json` — and the CLI's question is exactly one `user` message
whose content is the question verbatim. The question keeps the shared query rules (the
endpoint documents none of its own): 1–400 code points, at most 50 whitespace-delimited
words, all-whitespace rejected, transmitted without normalization.

The body's top level carries exactly the chat members — `messages`, `model`, `stream`,
`max_completion_tokens`, `seed`, `metadata` — while every search-control member sits
inside the nested `web_search_options` object and never rides the top level: `country`,
`language`, `safesearch`, `enable_citations`, `enable_entities`, `enable_research`,
`research_allow_thinking`, and the `research_maximum_*` family. Both levels render in
alphabetical wire-name order, so identical requests render identical bytes:

```
{"messages":[{"content":"what is the brave search api","role":"user"}],"stream":false}
```

```
{"max_completion_tokens":1024,"messages":[{"content":"…","role":"user"}],"metadata":{"user_id":"agent-1"},"model":"brave-pro","seed":42,"stream":true,"web_search_options":{"country":"US","enable_citations":true,"enable_entities":false,"enable_research":true,"language":"en","research_allow_thinking":false,"research_maximum_iterations":3,"research_maximum_queries":10,"research_maximum_results_per_query":30,"research_maximum_seconds":60,"research_maximum_tokens_per_query":4096,"safesearch":"strict"}}
```

Every unsupplied member is omitted rather than coerced to an upstream default: the model
stays absent so Brave selects its own (`brave`, `brave-pro`, and unknown future spellings
pass through unchanged when supplied), and an options container with no supplied member
is omitted whole. The option-to-member mapping, the local validation lattice
(research members require research; research and enabled citations and entities require
streaming), and the output rendering are pinned in
`docs/cli-contract.md#answers-command` and `docs/cli-contract.md#answers-blocking-output`.

The checked-in upstream contract flags a discrepancy about this container: the live
page's OpenAI-SDK examples pass `country`, `language`, `enable_citations`, and
`enable_research` as flat `extra_body` keys, while the reconciled design research — and
this CLI — nest them inside `web_search_options`. The explicit live probe ran on 2026-09-01
with the development key and could not settle the question: both bodies — nested and flat —
answered HTTP 400 with upstream code `OPTION_NOT_IN_PLAN`, an entitlement verdict about the
key, not a wire verdict about the form. The probe stays pending an entitled key (it skips
loudly with the fixed message `answers is not included in this plan — the nested
web_search_options probe requires an entitled key` whenever the key lacks the option); until
it settles, the nested form remains the CLI's committed shape, and the blocking request's
field-for-field wire tests hold it byte-exact.

Blocking usage arrives through the `X-Request-*` response headers the shared transport
already parses (see [Rate limits and usage](#rate-limits-and-usage)); non-2xx responses
follow the standard classification table.

## Places search wire form

The place search endpoint is `GET /local/place_search` — GET only, with no POST form:
the checked-in upstream contract documents GET only for place search, so the shared
8,000-byte rule never switches methods here. It carries exactly the documented places
inventory, alphabetical by wire name: `q` (optional), `count`, `country`, `geoloc`,
`latitude`, `location`, `longitude`, `radius`, `safesearch`, `search_lang`,
`spellcheck`, `ui_lang`, and `units`. Every unsupplied value is omitted rather than
coerced to an upstream default, so Brave's count default of 20, safesearch default of
strict, and spellcheck default of true stay upstream decisions.

Places-specific spellings: the query is optional — omitting it with an anchor is
Explore mode, and omitting query and anchor both is a valid broad global search the
CLI never rejects locally. The anchor is exactly one spelling: `latitude` and
`longitude` arrive paired inside their documented ranges (the pair refuses
`location`), or `location` carries the place name — US `city state country`, others
`city country`, case-insensitive, no commas — and the CLI rejects a place name that
carries CR, LF, NUL, or any other control character, or begins or ends with
whitespace, at the option boundary before anything reaches the wire. `geoloc` is the
`latitudexlongitude` combined spelling, both components plain decimals — no exponent
spelling, at most 100 fraction digits and 1,000 significant digits each —
range-checked like the anchor coordinates and serialized exactly in that form with
the given decimal scale. `radius` is meters, a finite plain decimal at zero or
greater under the same decimal bounds, and a ranking bias rather
than a hard cutoff — the search stays global when it is omitted. `count` reaches
1–100 and budgets the total across every response bucket, not only `results`.
`country` accepts exactly a two-letter ISO 3166-1 alpha-2 code — the place endpoint
documents no region-wide `ALL` — `units` is `metric|imperial`, and `safesearch` is
`off|moderate|strict`.

## Place enrichment wire form

The place enrichment endpoints are `GET /local/pois` (CLI `places details`) and `GET
/local/descriptions` (CLI `places describe`) — GET only, with no POST form: the
checked-in upstream contract documents GET only for both. Each carries exactly one
parameter, `ids`, repeated once per id in the chunk's input order — `ids=a&ids=b` —
with every value percent-encoded independently, duplicates preserved, up to 20 ids per
request. The ids are opaque and ephemeral upstream tokens (roughly eight hours): the
CLI carries them verbatim and never validates their internal format. No other wire
parameter exists on these endpoints, so the CLI offers no search option for them — the
ids are each command's whole grammar.

## Response bounds

| Bound | Default | Enforced over |
| ----- | ------- | ------------- |
| Compressed transfer | 16 MiB | Wire bytes actually delivered when `Content-Encoding: gzip`. |
| Decoded body | 16 MiB | Inflated bytes for gzip; identity bodies count their bytes exactly once against this bound. Non-stream responses accumulate wholly inside it. |
| Structured error body | 1 MiB | Any non-2xx body, in whatever form it arrives. |

Enforcement counts delivered bytes, never an advertised `Content-Length` and never a
compression ratio: the body subscription is cancelled the moment one more byte than the
applicable bound arrives, so an oversized or endless peer is severed mid-stream and no
partial non-stream body flows onward. During gzip decoding the decoded bound aborts inflation
immediately, so a decompression bomb never materializes. The bounds are constructor-injected
— contract tests run at kibibyte scale — while production ships the defaults above.

## Content-type enforcement

Parsed response modes require the media type to be exactly `application/json` —
case-insensitive, surrounding whitespace tolerated, parameters such as
`charset=utf-8` allowed, and every other spelling including `text/json`, `+json` suffixes,
and missing headers refused — before a non-empty 2xx body may be treated as structured. A
violation is a malformed-response failure (exit `8` in the CLI mapping) whose diagnostic
carries at most a 256-byte preview of the decoded body with control characters, backslashes,
and quotes escaped, and never quotes the observed header text, because header values are
untrusted input. A bodyless 2xx such as 204 carries nothing to parse, so no type is required
of it. Raw response mode enforces status, redirect, size, and encoding rules but
intentionally bypasses both the type check and JSON parsing: a 2xx body passes through
byte-exact whatever its type or validity.

## Gzip content coding

The coding is decoded explicitly. The parsed value admits exactly one token — `identity`
(including a missing or blank header) or `gzip`, case-insensitively and
whitespace-tolerantly; anything else is rejected as an unsupported content encoding,
including stacked lists such as `gzip, gzip`, `br`, unknown tokens, and repeated
`Content-Encoding` field lines, which RFC 9110 combines into exactly such a stacked list. A
gzip body must be
exactly one well-formed member: truncation anywhere, a corrupt checksum or length, reserved
header flag bits, trailing
bytes after the member — even a single one — and a second concatenated member are all
malformed-response failures. The walker reads the container itself rather than a buffered
stream decoder, so those boundaries are exact.

## Server-sent event transport

Streaming Answers bodies are parsed incrementally at the byte level by a push parser that never
touches JSON binding — that begins one layer up. The parse is a byte-level state machine, so no
behavior may depend on where delivery boundaries fall, and the pinned tests replay every fixture
at every fixed chunk size and at every two-cut split to prove it.

| Aspect | Rule |
| ------ | ---- |
| Line endings | CRLF, LF, and a lone CR each end exactly one line; a CR followed by LF is one terminator, never two. |
| Byte-order mark | Exactly one leading UTF-8 mark is stripped; a mark anywhere else is ordinary content. |
| Comments | Lines beginning with `:` are comments and heartbeats: ignored, never dispatched, never joined. |
| Dispatch | A blank line dispatches the block; a block with no `data` line dispatches nothing; a block left unterminated at end of stream is discarded without dispatching. |
| Fields | Case-sensitive names `data`, `event`, `id`, `retry`; unknown fields are ignored, though their bytes still count against the bounds. |
| Value form | At most one leading space after the field colon is removed (`data: x` → `x`, `data:  x` → ` x`, `data:x` → `x`, a bare `data` line is an empty value); repeated `data` lines join with a newline. |
| id / retry | An `id` carrying a NUL and a non-numeric `retry` are ignored; the last valid values persist and ride every later dispatched event. |
| UTF-8 | Decoding happens after a line completes, which is exact across delivery boundaries because no UTF-8 continuation byte can collide with the ASCII delimiters; a malformed completed line is a typed encoding failure. |

Bounds are counted, never guessed from advertised framing, and every ceiling is
constructor-injected so contract tests run at kibibyte scale against the shipped code path:

| Bound | Default | Counted over |
| ----- | ------- | ------------ |
| SSE line | 64 KiB | Raw wire bytes of the current line's content — field name, colon, and value together — terminator excluded; comment lines count in full. |
| SSE event block | 1 MiB | Raw wire bytes of every line since the last dispatch, field prefixes and comments included. |
| Tag payload | 1 MiB | Decoded UTF-8 bytes of one tag's payload (tag-decoding layer). |
| Buffered accumulation | 16 MiB | Decoded UTF-8 bytes of the joined dispatched payloads — only the explicit buffered output mode accumulates at all. |

The two event-stream ceilings count raw wire bytes as delivered per line and per event block —
the field name and prefix (`data: `), the single space the value rule strips, and entire comment
lines all included — never the decoded value bytes alone, so the accounting is deliberately
stricter than the decoded-byte minimum a payload by itself would need.

A breach latches the run's subscriber-failure cause — the cancel signal the body reader observes,
which closes the connection mid-wire so an endless peer stops being read — and then surfaces as
the typed overflow; the peer observes the severed connection. This is the same unblocking
contract interruption and idle timeouts hold for the streaming body publisher.

Ending matrix: a dispatched data payload of exactly `[DONE]` is the stream terminal, and the
terminal event itself is dispatched before the stream closes. Data dispatched after the terminal
fails typed; comments and id/retry-only blocks after it are ignored. End of stream without the
terminal is the distinct typed incomplete signal — an unterminated block discarded at end of
stream can never supply the terminal, so `[DONE]` without its terminating blank line stays
incomplete. The transport itself never guesses completeness from context; a caller may treat an
incomplete ending as complete only on an already-observed documented terminal condition, such as
the final usage tag.

## Answers stream tag decoding

One layer above the transport, each dispatched data payload (the terminal excepted) is one
OpenAI-compatible chunk: parsed as a JSON object, `choices[0].delta.content` extracted, and the
content tokenized. A payload that is not a JSON object, or whose content member is not text, is
a typed malformed-chunk failure — the tolerance the transport shows unknown fields does not
extend to a chunk that cannot identify itself. A chunk without usable content — a role delta, a
tool-call delta, a finish marker, an empty `choices` array around the trailing usage, a
contentless delta, or an empty dispatched payload (a heartbeat's empty data value) — decodes to
a passthrough event whose reason names what it held (`role`, `tool_calls`, `finish_reason`,
`empty choices`, `no content`), never to invented text.

The content mixes ordinary text with Brave tags. The nine documented tags — `citation`, `entity`,
`usage`, `queries`, `analyzing`, `thinking`, `progress`, `blindspots`, `answer` (citation and
usage verified live; the rest from the reconciled research of the same retrieval date) — must
carry exactly one JSON document as their payload, judged by the same strict upstream reader
whole bodies satisfy — trailing tokens or a second concatenated document fail exactly like a
malformed payload, because silently dropping or truncating a structured payload would corrupt
the rendered answer. The decoded output is a sequence of domain-safe events — text runs,
tagged payloads, unknown tags, passthroughs — and no JSON-library type ever escapes the
decoding layer.

The tokenizer spans arbitrary splits: a tag opened in one chunk closes in a later one, across
SSE events and mid-payload, and a closer is only the exact `</name>` — angle brackets, partial
closers, and payload JSON strings containing `<` stay payload text. Because the first exact
`</name>` always closes, a documented tag's payload cannot contain its own closer: such a
payload truncates at the embedded closer and the truncated text fails JSON validation as the
typed malformed-tag-payload failure — loud, never a silent corruption of the answer. Ordinary
text is preserved exactly: the concatenation of every text event equals the content stream
character for character. A tag opens on `<name>` where the name starts with an ASCII letter and
continues with letters, digits, hyphens, or underscores; any other well-formed
`<name>...</name>` is an unknown tag surfaced verbatim with its name and raw bounded payload,
so machine surfaces can record it as an upstream event instead of dropping it. A tag still open
at stream end is a typed failure, while a partial opener that never completed flushes as
ordinary text.

## Streaming answers exchange

A streaming Answers run is one POST exchange of the chat-completions body with
`stream: true`, `Accept: text/event-stream`, and `Accept-Encoding: identity` — always
identity, never gzip. One exchange serves both representations of its single body: the raw
mode consumes the decoded-exact event-stream bytes as they were read, and the semantic mode
consumes the same bytes decoded by a subscriber-side processor. Because the raw
representation is defined as decoded-body exactness, a compressed coding would be inflated
before either representation exists, and event-stream endpoints commonly refuse compression
outright; requesting identity removes that ambiguity at the source. A response that still
answers with any non-identity content coding is a malformed-response failure named by rule,
never by the observed header text.

Response shape: a 2xx answer with a body must carry exactly the `text/event-stream` media
type — case-insensitive, surrounding whitespace tolerated, parameters such as
`charset=utf-8` allowed — and every other spelling, including a missing header, is a
malformed-response failure whose diagnostic carries at most the same 256-byte escaped body
preview the blocking content-type rule produces and never quotes the observed header value.
A bodyless 2xx such as a 204 carries nothing to stream, so no type is required of it. A
non-2xx answer keeps the shared upstream classification with its rate-limit observation, its
error body read once under the structured error limit.

Ending semantics of the semantic layer: the dispatched `[DONE]` marker and an observed,
completed `<usage>` tag are both documented terminal conditions. An end of body that arrives
without the marker is the typed incomplete failure, downgraded to normal completion only
when the usage tag was already observed — a run that reported its cost has finished its
answer, a run that never did has not. A body that breaks instead of ending — a truncation or
reset mid-body — is the distinct typed abrupt-ending failure: transport-caused, and carrying
the consequence that every usage and cost fact of the run is unknown rather than absent.

Deadline semantics of one streaming run: the idle window is the maximum silence between
decoded body bytes and resets on every decoded byte, heartbeats included; its effective
default is 60 seconds for an ordinary stream and 300 for a research stream. The wall budget
is absolute from the moment the exchange opens and heartbeats cannot extend it; a research
run that pinned its research seconds defaults to those seconds plus a 30-second grace, and
an ordinary stream has no default wall budget. The opening phase through the response
headers is bounded separately by the tighter of a fixed 30-second ceiling and the wall
budget, because no cancellation latch can interrupt a connect or a silent-header wait. Every
ending of a run — interruption, a broken output pipe, both deadlines, a failing subscriber,
a transport break, and an explicit close — converges on one shared first-terminal-cause
latch, and closing the exchange (which cancels the body, severs the peer, and retires the
reader) is the mechanism every deadline uses to unblock a reader parked inside a silent
body.

Demand semantics: the raw publisher is cold and single-subscription and reads the body only
against outstanding request credit, so `request(n)` reads exactly n decoded bytes; the
semantic layer stands on the same publisher as a processor that keeps at most one raw read
in flight at a time, so raw reads never run ahead of semantic demand, one chunk may decode
into several events with the surplus held until credit returns, and the terminal signal
waits for the buffer to drain. Exactly one representation may be subscribed per exchange,
and every subscriber callback is serialized — never concurrent.

## Rate limits and usage

Every completed exchange carries its observed rate-limit metadata, parsed nonfatally: a
malformed, missing, or misaligned metadata header never downgrades a 2xx into a failure and
never changes a failure's category, and every irregularity is preserved as a note instead. A
successful exchange carries the usage metadata too; a failing exchange carries the rate-limit
snapshot on its failure outcome — a 429 arrives with the windows that explain it — while an
exchange that broke before response headers existed carries none.

The four rate-limit headers — `X-RateLimit-Limit`, `X-RateLimit-Policy`, `X-RateLimit-Remaining`,
`X-RateLimit-Reset` — each carry one comma-separated token list, and quota window i is built
from the i-th token of each list. Header names match case-insensitively and repeated physical
header lines flatten into their list in arrival order. Token positions are never shifted: a
position where the lists disagree in length, or where a limit, remaining, or reset token is
not a plain nonnegative integer (ASCII digits only), or where the policy token is empty,
yields a dropped window and one nonfatal note naming the field and position. Policy tokens
are free-form strings kept verbatim per window; because the split is positional, a policy
token cannot itself contain a comma — an upstream format constraint with no escape — so a
policy value carrying one splits into fragments that misalign the lists, which the
misalignment note reports while the surviving fragments stay verbatim. A documented limit of
`0` means unlimited, never exhausted. `X-RateLimit-Reset` is a nonnegative whole-second
duration counted from the observation of the headers — never an epoch — and the observation
instant comes from the clock injected into the parser, so the absolute reset instant of a
window is derived per window against that clock. A reset beyond the largest renderable
whole-second horizon (`Long.MAX_VALUE / 1000` seconds) drops its window with a note, because
a duration no millisecond rendering can hold must never crash a success encoding. Parsing
also caps the window count at 64 with a truncation note, so a hostile comma list cannot
amplify into an unbounded window set. An exchange with no rate-limit headers at all carries
an empty snapshot, never an absent one. Notes quote header names, positions, and counts
only, never observed header text, so every note is safe on each diagnostic channel.

Answers usage metadata arrives on blocking calls as the documented `X-Request-Requests`,
`X-Request-Queries`, `X-Request-Tokens-In`, `X-Request-Tokens-Out`, `X-Request-Requests-Cost`,
`X-Request-Queries-Cost`, `X-Request-Tokens-In-Cost`, `X-Request-Tokens-Out-Cost`, and
`X-Request-Total-Cost` response headers (the final `<usage>` stream tag plays the same role
for streaming). Counters are nonnegative integers; costs are decimals parsed at their exact
reported scale. A malformed or negative value drops that one field with a note while every
surviving field is kept, and a repeated known header applies its first physical value with a
note. Unknown `X-Request-*` header names are preserved verbatim — the first-seen spelling
and the value text unchanged — in an ordered map that machine output renders losslessly; an
exchange with no `X-Request-*` header at all carries no usage value.

## Error decoding and classification

A non-2xx body is bounded by the structured error limit and parsed tolerantly: the Brave
error envelope's `error.code` and `error.timestamp` (kept as its original text) are extracted
when present, and the raw bounded body rides along unchanged as the upstream payload, which
is what makes unknown `error.meta` fields survive losslessly into machine output. A missing
or malformed error body yields no structured code and a generic diagnostic. Human
diagnostics name only the status — recovery-focused, quoting neither the body nor any URI or
header text. Decode integrity outranks status classification: a non-2xx body whose declared
gzip coding is truncated or corrupt classifies as a malformed response, because its body is
unintelligible and no structured fact can be drawn from it.

| Status | Structured code | Category | CLI exit |
| ------ | --------------- | -------- | -------- |
| 401, 403 | irrelevant | authentication | `4` |
| 422 | auth-bearing set (below) | authentication | `4` |
| 422 | absent or any other code | upstream | `7` |
| 429 | irrelevant | rate limited | `5` |
| every other 3xx/4xx/5xx | never overrides the status | upstream | `7` |

The auth-bearing set is matched after normalization (lower-cased, punctuation stripped):
`unauthorized`, `forbidden`, `authentication_failed`, `invalid_api_key`,
`entitlement_required`, `payment_required`, `subscription_required`. A structured code never
overrides any status other than 422 — the status is the observed fact, the body an
interpretation. The exit mapping itself is owned by `docs/cli-contract.md`.

## Sentinel-free diagnostics

Nothing the exchange can surface — diagnostics, bounded previews, structured codes,
timestamps, payload renderings — ever repeats the subscription token, other credential
material, or query text. Previews may quote decoded body bytes, which is why fixtures craft
bodies without secret material; the redaction contract covers request-derived text only.
Every contract test authenticates with a single-use sentinel token and asserts its absence
recursively across the failure it induces.
