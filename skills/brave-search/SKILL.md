---
name: brave-search
description: Research-workflow skill for the Brave Search CLI — answer questions with citations, assemble grounded context for writing, fact-check claims, track developing stories, refine queries, research local places and media, and run multi-query deep research with a synthesized, sourced answer. Use when a task calls for web search or lookup ("search the web", "find sources", "look this up"), recent news, fact-checking a claim, image or video retrieval, query autocompletion or spellcheck, local-place search or enrichment, LLM retrieval context, or a cited answer to a question.
---

# Brave Search CLI — Research Workflow Skill

`brave-search` is a command line client for the Brave Search API with stable,
machine-parseable output designed for autonomous agents. It validates every option
locally before contacting the network, so a malformed invocation fails fast with exit
`2` and zero requests spent. Three invariants govern every invocation:

1. **Always pass an explicit output mode** — `--output json` (one LF-terminated
   envelope) or `--output jsonl` (one record per line). Never parse human output.
   `--output json`, `--output=json`, and `-o json` are interchangeable spellings.
2. **Decide on the exit code first**, then on `ok`/`error.code` inside the document.
3. **Credentials reach the CLI only through the environment** (`BRAVE_API_KEY`) or its
   secure config file — never as a command argument. There is no `--api-key` flag.

A failure that was dispatched still emits exactly one machine document (`ok: false` in
json, one `error` record ending the jsonl stream). A usage error (exit `2`) or a
missing credential (exit `3`) strikes before dispatch: stderr only, empty stdout —
parse nothing and fix the invocation or the credential.

## Reading a machine document

Every `--output json` document is one envelope with this shape (keys vary by command;
exact contracts live in `schemas/v1` of the release archive and `docs/cli-contract.md`):

```json
{"schema_version":"1","ok":true,"command":"web",
 "data":{"projection":{"result_count":2,"results":[{"title":"…","url":"…"}]},
         "upstream":{}},
 "meta":{"http_status":200,"rate_limits":[]}}
```

Read results from `data.projection.results` and quota from `meta.rate_limits` — never
from `data.upstream`, except for one diagnosed case: if `result_count` is `0` while
`data.upstream.results` is non-empty, the projection missed a live response shape. Read
the upstream array so the research is not lost, and report the mismatch (command,
`upstream` query block) instead of concluding there were no results. Envelopes embed
the full upstream tree (favicons, thumbnails) and run ~10–20 KB: extract fields
programmatically, never dump whole documents into working context.

Pipes hide the exit code: `brave-search … | parse` reports the parser's status, not the
CLI's. Run with `set -o pipefail` (or read `${PIPESTATUS[0]}`) so invariant 2 survives
every pipeline.

## Quick reference

| Command | Use it for | Cost |
| ------- | ---------- | ---- |
| `web` | Broad web search, site filters, pagination | 1 request per page |
| `news` / `videos` | Fresh results, age metadata, page walks | 1 request per page |
| `images` | Image urls and thumbnails | 1 request, count up to 200, no paging |
| `suggest` / `spellcheck` | Cheap query pre-checks before paid search | 1 inexpensive GET |
| `places search` / `details` / `describe` | Local places, then per-id enrichment | search 1 request; enrichment 1 per 20 ids |
| `context` | LLM-ready grounded passages under a token budget | 1 request |
| `rich` | Live results of an earlier `--enable-rich-callback` web search | 1 request |
| `answers` | Grounded, cited answer to one question | metered per request, query, and token |

Every row costs what it says only when the key's plan includes that endpoint — a
missing one fails fast with `OPTION_NOT_IN_PLAN` (exit `7`) before anything is spent.
Treat that as routing, not failure: skip the step and continue with the endpoints the
key covers (one observed narrow key covers `web`/`news`/`videos`/`images` while
excluding `suggest`, `spellcheck`, and `answers`).

`--output raw` captures the exact upstream body bytes of a single request only; every
multi-request walk rejects it. `--pretty` is valid with `json` only, for humans
reading along. Machine text carries upstream text unsanitized and numbers value-exact
(just re-spelled, as in `1e2` becoming `1E+2`); only `--output raw` is byte-for-byte
upstream. Human output is terminal-sanitized — when the exact upstream text or bytes
matter, read them from a machine mode.

### JSONL record inventory

Every jsonl line is `{"type":"…",…}`. Search walks emit one `result` record per logical
result, in deduplicated first-seen order as each page completes (each record carries its
`page`), then one terminal `summary` (requested/received page counts); read and act on
each `result` as it arrives instead of waiting for the summary. Streams add
`answer_delta`, `citation`, `entity`, `research_progress`, and `upstream_event` records
as their events arrive. `warning` records carry advisories that never fail the run. Any
mode ends a failed run with exactly one `error` record carrying the same classification
as the failure table below.

## Research playbooks

Pick the playbook by the research goal. Every example below is executable as printed.

### Answer a question with sources

```console
$ brave-search answers --output jsonl --citations "how does a heat pump work"
$ brave-search answers --no-stream --output json --max-completion-tokens 256 "define sitemap.xml"
```

Prefer the streaming form whenever progress matters — answers streams unless
`--no-stream` opts out, and that transport default is never an output default: the
explicit `--output jsonl` rule stands. `answer_delta`, `citation`, and `entity`
records arrive as they do, and one `summary` closes the stream with
`deltas_emitted`, `citations_seen`, `entities_seen`, and — when the final usage
event arrived — the `usage` member. Prefer `--no-stream --output json` only when a
single final document fits the pipeline better; blocking reads cost the same. Cost
notes: answers is metered per request, query, and token (cap with
`--max-completion-tokens`); a stream that ended without a usage event has an
**unknown cost, never a free one** (`cost_unknown: true` on the error record).

### Assemble grounded context for writing or summarizing

```console
$ brave-search context --output json --max-tokens 4096 "circular economy business models"
```

One request returns grounding passages sized to feed a downstream prompt. Budget by
the task: a definitional lookup needs `--max-tokens 1024`; a section or summary
`4096`; an essay `8192`; a survey or comparison up to `16384`–`32768`. Tune the shape
with `--max-urls`, `--max-snippets`, `--max-tokens-per-url`, `--max-snippets-per-url`,
and precision with `--threshold strict|balanced|lenient|disabled`. Unsupplied budgets
are omitted — Brave calibrates defaults upstream — so pass a bound only when the
context window requires one. The whole context is one logical result: one `result`
record then one `summary` in jsonl.

### Fact-check a claim

```console
$ brave-search spellcheck --output json "recieve mail attatchment"
$ brave-search web --output jsonl --count 3 --safe-search strict "aspirin cures all headaches"
$ brave-search web --output jsonl --count 3 --safe-search strict "aspirin does not cure headaches"
$ brave-search news --output jsonl --freshness pd --count 5 "aspirin headache study"
```

Spellcheck the phrasing first (an empty correction list means the query was clean),
then search the exact claim **and a negated variant** with a small `--count`:
inspect `title`/`url` in the jsonl records — independent domains agreeing on the
negation is the signal, one domain repeating a claim is not. Cross-check `news` with
`--freshness pd` for recency. Decision point: if sources conflict, report the
conflict with both citations instead of picking a winner.

### Track a developing story

```console
$ brave-search news --output jsonl --freshness pd --country us "central bank decision"
$ brave-search news --output jsonl --freshness pw --count 10 "election recount"
```

`--freshness pd` for today's developments, `pw` for the week's arc. Reach for
`--all-pages` only when coverage genuinely matters: the walk is strictly sequential,
deduplicates by exact URL automatically, and every page costs one request — bound it
with `--max-pages` (the walk stops at page 10 regardless) and prefer a bounded
`--max-pages 5` walk over an unbounded one.

### Refine a query until the results are good

```console
$ brave-search suggest --output json --count 10 "vertex sha"
$ brave-search web --output jsonl --count 1 "vertex shader"
$ brave-search web --output jsonl --count 1 "vertex shader tutorial"
$ brave-search web --output json --count 10 --include-site arxiv.org --exclude-site pinterest.com "solid state battery electrolyte"
```

Expand the partial query with `suggest`, then probe the promising variants with
`--count 1` (the cheapest read of whether a query has results at all), then deepen
the winner with `--count 10` and site filters: `--include-site`/`--exclude-site`
compile into one inline goggle (`--goggle` and `--goggle-file` are the full form, at
most 3 goggles; videos and images accept none of these spellings).

### Research local places

```console
$ brave-search places search --output json --latitude 47.6062 --longitude -122.3321 --count 10 coffee
$ brave-search places details --output jsonl a1b2c3d4e5f6g7h8 i9j0k1l2m3n4o5p6
$ brave-search places describe --output json a1b2c3d4e5f6g7h8
```

Search anchored on the coordinate pair or a `--location` place name — the name's
grammar is `city country`, US `city state country`, case-insensitive, with no commas
and no coordinates (a landmark is not a place name in that grammar: anchor landmarks
by their coordinates) — then **capture each result's `id` immediately** and enrich in
the same session: place ids are opaque and ephemeral — roughly eight hours upstream —
never validated locally, never stored for later. One enrichment invocation accepts up
to 200 ids in chunks of 20 sequential requests; duplicate ids are preserved at each of
their positions, input order is reconstructed exactly, and a missing or expired id
keeps its position as a `present: false` placeholder. `details` carries photos, urls,
and contact facts; `describe` renders AI-generated editorial text.

### Fetch the live results of an earlier rich-callback search

```console
$ brave-search rich --output json cb8f5a1e2d3c4b5a
```

After a web search requested with `--enable-rich-callback`, that search's output carries
an opaque callback key; `rich` fetches its current live result blocks. The key is a
reference, not a query — pass it verbatim, and use `--` when it starts like an option.

### Media research

```console
$ brave-search videos --output json --safe-search strict --count 5 "linear algebra lecture"
$ brave-search images --output jsonl --count 100 --safe-search strict "glassfrog"
```

Videos behaves like the search verticals (freshness, pages, age metadata). Images is
one wide request: no pagination at all — raise `--count` (up to 200) for more — and
its `--safe-search` accepts exactly `off` or `strict`, never `moderate`.

### Deep research pattern

```console
$ brave-search web --output jsonl --count 10 --freshness py "solid state battery electrolyte safety"
$ brave-search context --output jsonl --max-tokens 4096 --max-snippets 20 --threshold strict "solid-state battery electrolyte chemistry"
$ brave-search answers --output jsonl --research --research-queries 5 --research-iterations 3 --research-seconds 120 "compare low-power routing protocols"
```

The pattern: iterate `web`/`news` with freshness and site filters to collect the url
landscape; pull one token-budgeted `context` per subtopic to ground each section;
synthesize with `answers --research`, which runs its own multi-query loop. Bound the
research explicitly — `--research-queries 1..50`, `--research-iterations 1..5`,
`--research-seconds 1..300`, `--research-results-per-query 1..60`,
`--research-tokens-per-query 1024..16384`, every member requiring `--research`, and
research requiring streaming. Watch `research_progress` records as they arrive;
SIGINT ends the run with partial counts, never a fabricated completion.

## Option enumerations

Exact spellings — anything else fails locally with exit `2` before anything is spent:

| Option | Valid values |
| ------ | ------------ |
| `--output` | `json`, `jsonl`, `raw` (single-request only); default is human text, never parsed |
| `--freshness` | `pd` (day), `pw` (week), `pm` (month), `py` (year), or `YYYY-MM-DDtoYYYY-MM-DD` |
| `--safe-search` | `off`, `moderate`, `strict`; images accepts only `off` or `strict` |
| `--threshold` (context) | `strict`, `balanced`, `lenient`, `disabled` |
| `--count` | per-request result budget; images takes up to `200` with no paging |
| `--max-pages` | page-walk budget; the walk stops at page `10` regardless |

`--help` on any command names the full grammar; the table above covers the values every
research playbook reaches for.

## Budget and quota discipline

- Dev keys commonly observe a one-request-per-second window and a monthly request
  budget. Quota telemetry — the windows and each one's `remaining` — is read from
  `meta.rate_limits` of a `--output json` envelope, on success and failure alike, and
  from the jsonl `error` record's `rate_limits` when the failing exchange observed
  windows (a 429 always does); jsonl `summary` records deliberately omit windows, so
  use `--output json` whenever quota telemetry matters, and read the `remaining`
  field of the applicable window after pulls, not just on failure.
- **Pace every sequence of invocations**: sleep at least 1.1s between requests, or
  honor the larger `reset_ms` a 429's windows carry. Never fire parallel
  invocations to "speed up" paging — the CLI paces its own walks; bursts only burn
  quota and trip the window.
- Probe with `--count 1` before large pulls, and reach for `--all-pages` only when
  coverage justifies one request per page.
- Budget the month: prefer suggest/spellcheck pre-checks, small counts, and single
  answers requests over repeated large walks; the monthly window does not reset on
  retry.
- Answers is metered per request, query, and token — a research run of 5 queries × 3
  iterations costs meaningfully more than one plain answer.

## Failure playbooks

Check the exit code first; in machine modes the document's `error.code` carries the
same classification.

| Exit | `error.code` | What to do |
| ---- | ------------ | ---------- |
| 0 | — | Parse the document; zero results and a downstream closed pipe are still success — unless `result_count` is `0` with a populated `data.upstream.results`, which is the projection-miss case above. |
| 2 | — (stderr only, stdout empty) | Usage or local validation error: fix the invocation (`--help` names the grammar); nothing was requested or spent. |
| 3 | — (stderr only, stdout empty) | Credential missing or unsafe: export `BRAVE_API_KEY` (never as an argument) or store one with the piped `config set-key` invocation printed below. Do not retry the same shape. |
| 4 | `AUTHENTICATION_FAILED` | Key rejected or the plan lacks the endpoint: do not retry; report the mismatch. |
| 5 | `RATE_LIMITED` | Read the observed windows, wait out the largest applicable `reset_ms`, then retry **once**; never retry inside the window. |
| 6 | `TRANSPORT_ERROR` | Network, DNS, TLS, timeout, or output I/O: retry with backoff is reasonable; check your own pipe handling first. |
| 7 | `UPSTREAM_ERROR` | Inspect `error.upstream_code` and `error.details`; with `OPTION_NOT_IN_PLAN` (answers/context/places) the key's plan lacks the endpoint — tell the user, change nothing, do not retry. |
| 8 | `MALFORMED_RESPONSE` | The response could not be trusted: report it; retrying will not change the shape. |
| 70 | `INTERNAL_ERROR` | Unexpected tool error with one redacted stderr line: report it with the exit code. |
| 130/143 | `INTERRUPTED`/`TERMINATED` | A signal ended the run: stop, report the partial counts, do not fabricate a completion. |

Exit 3 is fixed without ever naming the key on the command line: export `BRAVE_API_KEY`,
or in a headless session store one by piping exactly one line into the credential storer —

```console
$ printf '%s\n' "$KEY" | brave-search config set-key --stdin
```

— then confirm the effective source with `config show`, which reports where the key came
from without ever printing it.

Signal nuance: a paged walk interrupted mid-run renders its transport-failure
document while the process still exits 130/143 — the exit code, not the rendered
code, names the signal.

## Anti-patterns

- **Passing or echoing the credential.** Env or `config set-key` only; `config show`
  reports the effective source without ever printing the key.
- **Parsing human output or stderr for success.** Always `--output json`/`jsonl`;
  decide on the exit code and `ok`/`error.code`.
- **Assuming results from exit 0.** Zero results is a success — check
  `result_count` (or the summary record) before concluding anything.
- **Retrying a 429 immediately**, or bursting to dodge pacing — see the budget
  section.
- **Reading unreported answers cost as free.** `cost_unknown` means unknown.
- **Burning `--all-pages` on probes** — a probe is `--count 1`, one request.
- **Hoarding place ids.** They expire in hours; enrich in the session that searched.
- **Unquoted queries.** Always quote; a leading dash needs `--` besides.
- **Buffering a jsonl stream to act only at the end.** Consume
  `answer_delta`/`research_progress` records as they arrive — that is what the
  channel is for.
- **Expecting `raw` to be structured.** It is exact upstream bytes, single-request
  only.
