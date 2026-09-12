# Upstream Contract — Brave Search API

**Retrieval date:** 2026-08-30 (live spot-check of public Brave documentation; re-verified again at bootstrap on 2026-08-30)
**Live-run notes:** 2026-09-01 — live protocol observations against the production origin with the development key amended the web response shape, the llm/context, places, and answers entitlement gates, and the version-pinning observation; each amendment is marked inline with its date.
**Status:** Normative offline reference for this CLI. Live pages were fetched where reachable; entries marked **per plan, unverified** come from the reconciled design research of the same date and were not visible in the fetched page.

## 1. Base protocol

| Aspect | Contract |
| --- | --- |
| Production base URL | `https://api.search.brave.com/res/v1` |
| Authentication | `X-Subscription-Token: <key>` header on every endpoint, including Answers (verified live; cURL examples use `X-Subscription-Token` / `x-subscription-token`, case-insensitive). Bearer auth is not used by this CLI. |
| JSON requests | `Accept: application/json` |
| Streaming Answers | `Accept: text/event-stream` |
| Content-Encoding | Parsed modes send `Accept-Encoding: gzip` and decode `Content-Encoding: gzip` manually (JDK `HttpClient` does not promise transparent decompression); raw mode sends `Accept-Encoding: identity` |
| Version pinning | `Api-Version: YYYY-MM-DD` header sent only when the user explicitly pins; the pin is an observation, never a served-version guarantee (live-observed 2026-09-01: the doc-baseline pin `2026-08-30` answered 404 with upstream code `API_VERSION_NOT_FOUND`, proving the header reaches the server and is evaluated; the baseline date is a doc-retrieval date, and no server default is hardcoded) |
| User-Agent | `brave-search/<version>`, configurable inside the HTTP adapter |
| URI construction | Encode every query key/value independently; never log credential-bearing headers |
| Response limits (CLI-enforced) | 16 MiB decoded non-stream body, 16 MiB compressed transfer, 1 MiB structured error preview; streaming bounded incrementally except explicit buffered JSON mode (16 MiB) |
| Rate-limit headers | `X-RateLimit-Limit`, `X-RateLimit-Policy`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (comma-aligned multi-window values; `Reset` is a nonnegative duration, not an epoch; limit `0` means unlimited) |
| Answers usage metadata | `X-Request-Requests`, `X-Request-Queries`, `X-Request-Tokens-In`, `X-Request-Tokens-Out`, `X-Request-Requests-Cost`, `X-Request-Queries-Cost`, `X-Request-Tokens-In-Cost`, `X-Request-Tokens-Out-Cost`, `X-Request-Total-Cost` — response headers for blocking calls, final `<usage>` stream tag for streaming (verified live) |
| Error envelope | Preserve HTTP status, `error.code`, `error.detail`, arbitrary `error.meta`, optional timestamp |

## 2. Method selection rule (GET vs POST)

Live docs publish both GET and POST reference pages for `/web/search`, `/llm/context`, `/news/search`, and `/videos/search` (POST takes the same parameters as a JSON body with `Content-Type: application/json`; verified live on the LLM Context page, which states POST is for complex queries and URL-length limits). The CLI chooses deterministically:

- Use **POST** with JSON body whenever inline Goggles are present or the fully encoded URI would exceed 8,000 bytes.
- Otherwise use **GET** with encoded query parameters.
- Field serialization must be proven equivalent for both methods by contract tests.

`/images/search`, `/local/place_search`, `/local/pois`, `/local/descriptions`, `/suggest/search`, and `/spellcheck/search` are GET-only (verified live). `/chat/completions` is POST-only (verified live).

## 3. Shared parameter semantics (verified live across endpoint pages)

- `q`: required everywhere it applies except Place Search (optional there); 1–400 characters, at most 50 words. The CLI additionally validates 1–400 Unicode code points before dispatch and rejects all-whitespace input without normalizing or trimming valid queries.
- `offset`: zero-based **page index** 0–9 (not a record offset); pages may overlap. CLI `--page <1..10>` maps to `offset = page - 1`.
- `country`: 2-character country code; news/videos/images/suggest/spellcheck also document `ALL` (reconciled 2026-08-31: the images page states "`ALL` for worldwide" and the suggest/spellcheck enums list it; the original 2026-08-30 row named only news/videos). Defaults `US` (web/news/videos/images/suggest/spellcheck/places; llm/context page shows lowercase `us`).
- `search_lang`: 2+ character language code, default `en`.
- `ui_lang`: locale, default `en-US`.
- `safesearch`: `off|moderate|strict` except images (`off|strict`). Defaults differ per endpoint: web `moderate`, news `strict`, videos `moderate`, images `strict`, places `strict`, context unset (no filtering; local recall stays strict).
- `freshness`: `pd|pw|pm|py|YYYY-MM-DDtoYYYY-MM-DD`.
- `spellcheck`: boolean, default `true` everywhere it exists; the altered query is returned in `query.altered`.
- `goggles`: URL or inline definition, single value or list of up to 3. `goggles_id` is **deprecated** upstream (verified live: "This parameter is deprecated. Please use the goggles parameter") and is never exposed by the CLI.

## 4. Endpoint families

### 4.1 `web/search` — CLI `brave-search web <query>`

- Source: https://api-dashboard.search.brave.com/api-reference/web/search/get (and `/post`)
- Retrieval: 2026-08-30, verified live
- Methods: GET/POST (see §2)

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `country` | string | 2-char; default `US` | live |
| `search_lang` | string | default `en` | live |
| `ui_lang` | string | default `en-US` | live |
| `count` | int | 1–20; default 20; applies to web results only | live |
| `offset` | int | 0–9; default 0; page index; pages may overlap | live |
| `safesearch` | enum | `off|moderate|strict`; default `moderate` | live |
| `spellcheck` | bool | default `true` | live |
| `freshness` | string | `pd|pw|pm|py|YYYY-MM-DDtoYYYY-MM-DD`; default `""` | live |
| `text_decorations` | bool | default `true` | live |
| `result_filter` | string (comma list) | `discussions,faq,infobox,news,query,summarizer,videos,web,locations`; omitted = all | live || `units` | enum | `metric|imperial` | live |
| `goggles` | string/list | URL or inline; up to 3 | live |
| `goggles_id` | string | **deprecated upstream**; never sent | live |
| `summarizer` | bool | enables summary key generation; not exposed (Summarizer out of scope) | live |
| `enable_rich_callback` | bool | default `false`; requires Search plan | live |
| `operators` | bool | default `true` | live |
| `extra_snippets` | bool | default false | per plan, unverified |
| `include_fetch_metadata` | bool | default false | per plan, unverified |
| `X-Loc-Lat`, `X-Loc-Long`, `X-Loc-City`, `X-Loc-State`, `X-Loc-State-Name`, `X-Loc-Country`, `X-Loc-Postal-Code`, `X-Loc-Timezone` | headers | location group; `X-Loc-Timezone` is **web only** and validated with `ZoneId` | per plan, unverified (header set verified on context page) |

Wire-form note on `result_filter`: the live page types the field as "string (comma list)" without showing the serialized request, which is ambiguous between repeated query parameters and one comma-joined value. This CLI pins the comma-joined reading — one `result_filter` parameter whose value is the list joined with `,` in given order (strictly percent-encoded, so the separator travels as `%2C`); an empty list omits the parameter. Pinned by `WebSearchEndpointTest` and the loopback gateway contract.

CLI mapping: `--country→country`, `--search-lang→search_lang`, `--ui-lang→ui_lang`, `--safe-search→safesearch`, `--freshness→freshness`, `--count→count`, `--page→offset` (page−1), `--spellcheck→spellcheck`, `--text-decorations→text_decorations`, `--result-filter→result_filter` (repeatable/comma), `--units→units`, `--extra-snippets→extra_snippets`, `--include-fetch-metadata→include_fetch_metadata`, `--operators→operators`, `--enable-rich-callback→enable_rich_callback`, `--loc-*→X-Loc-*`, `--loc-timezone→X-Loc-Timezone`, Goggles: `--goggle/--goggle-file/--include-site/--exclude-site→goggles` (mutually exclusive input strategies; site shortcuts compile into one inline Goggle).

Pagination: Web stops aggregation on `query.more_results_available=false`; dedupe by exact API-provided URL string; preserve first-seen order.

Response shape (live-verified 2026-09-01): one JSON object with `type`, a `query` object (`query.more_results_available` live-observed), a `mixed` ordering object, `videos` as a nested object, and `web` as an **object** — `type`, `family_friendly` — carrying the web result list in its `results` array; the array may be empty. The live smoke suite asserts exactly this structural presence (`WebSearchBodyShape`), never live content.

### 4.2 `llm/context` — CLI `brave-search context <query>`

- Source: https://api-dashboard.search.brave.com/documentation/services/llm-context (links to `/api-reference/summarizer/llm_context/get`)
- Retrieval: 2026-08-30, verified live
- Methods: GET/POST (see §2). Single non-paginated request.

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; 1–400 chars, ≤50 words | live |
| `country` | string | 2-char; default `us` (live page shows lowercase) | live |
| `search_lang` | string | default `en` | live |
| `count` | int | 1–50; default 20 | live |
| `freshness` | string | same enum as web | live |
| `maximum_number_of_urls` | int | 1–50; default 20 | live |
| `maximum_number_of_tokens` | int | 1024–32768; default 8192 | live |
| `maximum_number_of_snippets` | int | 1–256; default 50 | live |
| `maximum_number_of_tokens_per_url` | int | 512–8192; default 4096 | live |
| `maximum_number_of_snippets_per_url` | int | 1–100; default 50 | live |
| `context_threshold_mode` | enum | `strict|balanced|lenient|disabled`; unset = API-calibrated default | live |
| `safesearch` | enum | `off|moderate|strict`; unset = no filtering (local recall stays `strict`) | live |
| `enable_local` | bool/tri-state | `true|false|null`; `null` auto-detects from presence of location headers | live |
| `goggles` | string/list | URL or inline | live |
| `enable_source_metadata` | bool | default `false`; adds `site_name`/`favicon`/`thumbnail`/`description` to `sources[url]` | live |
| `X-Loc-Lat` | header float | −90.0..90.0 | live |
| `X-Loc-Long` | header float | −180.0..180.0 | live |
| `X-Loc-City` | header string | city name | live |
| `X-Loc-State` | header string | ISO 3166-2 code | live |
| `X-Loc-State-Name` | header string | state/region name | live |
| `X-Loc-Country` | header string | 2-letter code | live |
| `X-Loc-Postal-Code` | header string | postal code | live |

Notes (verified live): token budgets are the binding limit — when they allow more snippets than the counts above, the snippet counts do not constrain. Coordinates pair with each other; timezone is rejected locally for context (no `X-Loc-Timezone` here). Response carries `grounding.generic[]`, optional `grounding.poi`/`grounding.map[]` with local recall, and `sources{url→meta}`.

Entitlement (live-observed 2026-09-01): the development key answers HTTP 400 with upstream code `OPTION_NOT_IN_PLAN` — the endpoint is plan-gated, so the live protocol probe (path, status, body shape) stays pending an entitled key; the live smoke suite records this entitlement shape as a loud assumption skip with the fixed message `llm context is not included in this plan`.

CLI mapping: `--count→count`, `--max-urls→maximum_number_of_urls`, `--max-tokens→maximum_number_of_tokens`, `--max-snippets→maximum_number_of_snippets`, `--max-tokens-per-url→maximum_number_of_tokens_per_url`, `--max-snippets-per-url→maximum_number_of_snippets_per_url`, `--threshold→context_threshold_mode`, `--source-metadata→enable_source_metadata`, `--local auto|on|off→enable_local` (auto = omit), `--loc-*→X-Loc-*` (coordinates paired and range-checked; never inferred from host).

### 4.3 `news/search` — CLI `brave-search news <query>`

- Source: https://api-dashboard.search.brave.com/api-reference/news/news_search/get (and `/post`)
- Retrieval: 2026-08-30, verified live
- Methods: GET/POST

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `search_lang` | string | default `en` | live |
| `ui_lang` | string | default `en-US` | live |
| `country` | string | 2-char or `ALL`; default `US` | live |
| `safesearch` | enum | `off|moderate|strict`; default `strict` | live |
| `count` | int | 1–50; default 20 | live |
| `offset` | int | 0–9; default 0 | live |
| `spellcheck` | bool | default `true` | live |
| `freshness` | string | same enum as web | live |
| `goggles` | string/list | up to 3 | live |
| `operators` | bool | default `true` | live |
| `extra_snippets` | bool | default false | per plan, unverified |
| `include_fetch_metadata` | bool | default false | per plan, unverified |

CLI mapping mirrors web for the shared set. News has no documented continuation field: `--all-pages` requests sequentially through user-facing page 10 unless bounded by `--max-pages`; never treat a short page as exhaustion.

### 4.4 `videos/search` — CLI `brave-search videos <query>`

- Source: https://api-dashboard.search.brave.com/api-reference/videos/video_search/get (and `/post`)
- Retrieval: 2026-08-30, verified live
- Methods: GET/POST

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `search_lang` | string | default `en` | live |
| `ui_lang` | string | default `en-US` | live |
| `country` | string | 2-char or `ALL`; default `US` | live |
| `safesearch` | enum | `off|moderate|strict`; default `moderate` | live |
| `count` | int | 1–50; default 20 | live |
| `offset` | int | 0–9; default 0 | live |
| `spellcheck` | bool | default `true` | live |
| `freshness` | string | same enum as web | live |
| `operators` | bool | default `true` | live |
| `include_fetch_metadata` | bool | default false | per plan, unverified |

No Goggles, no `extra_snippets`, no `text_decorations`, no `result_filter` (confirmed by absence on the live page). Pagination semantics as News (no continuation field).

Response shape (re-verified live 2026-08-31): a top-level `type` of `"videos"`, a required `query` object, a required `extra` object, and the logical result list as the top-level `results` array — not nested under a bucket key. Each element carries `type` (`"video_result"`), required `url` and `title`, nullable textual `description` and `age` (a human-readable freshness string), nullable `page_age`/`page_fetched`/`fetched_content_timestamp` fetch metadata, and nullable `video`, `meta_url`, and `thumbnail` objects whose interiors the live page leaves collapsed.

### 4.5 `images/search` — CLI `brave-search images <query>`

- Source: https://api-dashboard.search.brave.com/api-reference/images/image_search
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `search_lang` | string | default `en` | live |
| `country` | string | 2-char or `ALL`; default `US` | live |
| `safesearch` | enum | `off|strict` only; default `strict` | live |
| `count` | int | 1–200; default 50; **not paginated, no `offset`** (live note: raise count for more results) | live |
| `spellcheck` | bool | default `true` | live |

No `ui_lang`, no `freshness`, no `operators`, no Goggles, no pagination (confirmed live). CLI rejects `--page`/`--all-pages` for images locally (exit 2).

Response shape (pinned 2026-08-31, mirroring §4.4's videos note): a top-level `type` of `"images"`, a required `query` object, and the logical result list as the top-level `results` array — not nested under a bucket key — beside the shared `extra` object. Each element carries `type` (`"image_result"`), textual `title`, `url` (the page the image was found on), `image` (the full-size image url), and `thumbnail` (the thumbnail url), plus a structured `dimension` object — the shape this CLI's fixtures and tolerant projection rely on, reading only the textual members. The 2026-08-31 re-render of the live page models the same fields more richly (`thumbnail` as an object with `src`/`width`/`height`, a `properties` object with `url`/`placeholder`/`width`/`height`, and `source`/`page_fetched`/`meta_url`/`confidence` members); the projection's per-member tolerance keeps a future switch to that rendering a rendering question, not a framing one.

### 4.6 `local/place_search` — CLI `brave-search places search [query]`

- Source: https://api-dashboard.search.brave.com/documentation/services/place-search
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | optional; omit with an anchor for Explore mode | live |
| `latitude` | float | −90.0..90.0; required together with `longitude` | live |
| `longitude` | float | −180.0..180.0; required together with `latitude` | live |
| `location` | string | place name; US `city state country`, non-US `city country`; case-insensitive, no commas | live |
| `radius` | float | meters; ranking bias, not a hard cutoff; no upper limit; search is global if omitted | live |
| `count` | int | 1–100; default 20; CLI treats it as total budget across every response bucket | live |
| `country` | string | ISO 3166-1 alpha-2; default `US` | live |
| `search_lang` | string | default `en` | live |
| `ui_lang` | string | default `en-US` | live |
| `units` | enum | `metric|imperial`; default `metric` | live |
| `safesearch` | enum | `off|moderate|strict`; default `strict` | live |
| `spellcheck` | bool | default `true` | live |
| `geoloc` | string | `latitude`x`longitude` combined; CLI serializes exactly `lat<x>lon`, both components range-checked | per plan, unverified |

Notes (verified live): query AND anchor both omitted is a valid broad global search — do not reject locally. Response buckets: `results`, `cities`, `countries`, `regions`, `neighborhoods`, `addresses`, `streets`, plus `mixed` ordering hints; any bucket may be null/omitted. Result `id`s are opaque, ephemeral (~8 hours), and interchangeable with Web Search location result IDs. Location/`X-Loc-*`-like string values are rejected by the CLI at the option boundary for CR/LF/NUL/control characters and leading/trailing whitespace.

Entitlement (live-observed 2026-09-01): the development key answers HTTP 400 with upstream code `OPTION_NOT_IN_PLAN` — the place service is plan-gated, so the live protocol probe (path, status, body shape) stays pending an entitled key; the live smoke suite records this entitlement shape as a loud assumption skip with the fixed message `place search is not included in this plan`.

CLI mapping: `--latitude/--longitude→latitude/longitude` (pair required; mutually exclusive with `--location`), `--location→location`, `--radius→radius` (finite ≥ 0, plain decimal — exponent spellings and digit counts beyond the CLI's decimal bounds are refused), `--count→count`, `--geoloc→geoloc` (plain-decimal components), `--units→units`, plus the standard `--country/--search-lang/--ui-lang/--safe-search/--spellcheck`.

### 4.7 `local/pois` — CLI `brave-search places details <id>...`

- Source: https://api-dashboard.search.brave.com/documentation/services/place-search ("Fetching Additional POI Details")
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `ids` | string (repeated query param) | opaque POI IDs; up to **20** per request | live |

Returns photos, web result mentions, profiles, and more for each requested POI. CLI: one invocation caps at 200 IDs and chunks automatically into ≤20-ID requests preserving input order and duplicates; missing/expired IDs are represented at their original position; later chunk failures follow pagination partial-failure rules. IDs are opaque — never validate internal format; never store (ephemeral ~8 h).

### 4.8 `local/descriptions` — CLI `brave-search places describe <id>...`

- Source: https://api-dashboard.search.brave.com/documentation/services/place-search ("AI-Generated Descriptions")
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `ids` | string (repeated query param) | opaque POI IDs; up to **20** per request | live |

AI-generated descriptions per location. Same CLI chunking/preservation rules as `/local/pois`.

### 4.9 `chat/completions` — CLI `brave-search answers <question>`

- Source: https://api-dashboard.search.brave.com/documentation/services/answers (links to `/api-reference/summarizer/answers`)
- Retrieval: 2026-08-30, verified live
- Methods: POST only, `Content-Type: application/json`, OpenAI-compatible endpoint `https://api.search.brave.com/res/v1/chat/completions`

Request body (verified live unless noted):

| Body field | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `messages` | array | exactly one `user` message from the CLI | live (examples) |
| `model` | string | `"brave"` in examples; CLI omits `--model` by default and lets Brave select | live |
| `stream` | bool | streaming is the CLI default; `--no-stream` always explicit | live |
| `max_completion_tokens` | int | CLI `--max-completion-tokens` | per plan, unverified |
| `seed` | int | CLI `--seed` | per plan, unverified |
| `metadata` | object | CLI passthrough | per plan, unverified |
| `web_search_options` | object | container for ALL search-control fields per the reconciled design research: `country`, `language`, `safesearch`, `enable_citations`, `enable_entities`, `enable_research`, `research_allow_thinking`, `research_maximum_tokens_per_query` (1024–16384), `research_maximum_queries` (1–50), `research_maximum_iterations` (1–5), `research_maximum_seconds` (1–300), `research_maximum_results_per_query` (1–60; 2026 default 30) | per plan, unverified |

**Discrepancy flag (verified live):** the live page's OpenAI-SDK examples pass `country`, `language`, `enable_citations`, `enable_research` as **flat** `extra_body` keys, and states "citations and research mode require streaming mode to be `true`". The reconciled design research asserts these fields belong inside `web_search_options:{...}`. The CLI ships the **nested** form as its committed shape (held byte-exact by the blocking request's field-for-field wire tests, per `docs/brave-api-contract.md#answers-wire-form`); the exact wire form is pinned by the opt-in live probe `io.amscotti.bravesearch.adapter.bravehttp.live.LiveAnswersTest`. Live observation 2026-09-01: on the development key both probe bodies — nested and flat — answered HTTP 400 with upstream code `OPTION_NOT_IN_PLAN`, an entitlement verdict that cannot settle the nested-versus-flat question; the probe skips loudly with the fixed message `answers is not included in this plan — the nested web_search_options probe requires an entitled key` when the key lacks the answers entitlement, and the discrepancy stays **pending** (nested retained as the committed shape). Do not trust either form until an entitled key settles the probe.

Streaming semantics (verified live): OpenAI-compatible chunks; `choices[0].delta.content` carries ordinary text or Brave-tagged JSON payloads — `<citation>{"start_index","end_index","number","url","favicon","snippet"}</citation>` and `<usage>{X-Request-*}</usage>` (final message). Additional documented tags per design research: entity, queries, analyzing, thinking, progress, blindspots, answer; unknown tags surface as `upstream_event` records. Usage metadata for blocking calls arrives as `X-Request-*` response headers.

CLI mapping: `--stream/--no-stream→stream`, `--citations→enable_citations`, `--entities→enable_entities`, `--research→enable_research`, `--research-thinking→research_allow_thinking`, `--research-tokens-per-query→research_maximum_tokens_per_query`, `--research-queries→research_maximum_queries`, `--research-iterations→research_maximum_iterations`, `--research-seconds→research_maximum_seconds`, `--research-results-per-query→research_maximum_results_per_query`, `--model→model`, `--max-completion-tokens→max_completion_tokens`, `--seed→seed`, `--country→country`, `--language→language`, `--safe-search→safesearch` (all search-control fields inside `web_search_options`). Every `--research-*` option requires `--research`; `--research` requires streaming; blocking Answers is incompatible with `--output jsonl`; timeouts: `--idle-timeout` (default 60 s / 300 s research, resets on decoded body bytes) and `--stream-timeout` (research default `research-seconds + 30 s`).

### 4.10 `web/rich` — CLI `brave-search rich <callback-key>`

- Source: no public reference page located; the guessed `/api-reference/web/rich/get` returns 404 — **per plan, unverified**
- Methods: GET only
- Auth: `X-Subscription-Token`
- Input: callback key obtained from a Web Search response when `enable_rich_callback=true`; returns real-time rich results for the originating query
- CLI mapping: positional `<callback-key>` only plus global options

### 4.11 `suggest/search` — CLI `brave-search suggest <partial-query>`

- Source: https://api-dashboard.search.brave.com/api-reference/other/suggestions
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `country` | string | 2-char or `ALL`; default `US` (enum incl. `ALL` re-verified live 2026-08-31) | live |
| `lang` | string | language hint; default `en` (wire name `lang` confirmed live 2026-08-31 from the page's OpenAPI schema, upgrading the earlier per-plan spelling) | live |
| `count` | int | 1–20; default 5 | live |
| `rich` | bool | default `false`; requires a paid autosuggest subscription | live |

CLI mapping: language option maps to wire `lang` (**never** `search_lang`); the shared `--search-lang` flag is accepted as an alias mapping to `lang` for this endpoint only; `--count→count`; `--rich→rich`. No SafeSearch, freshness, Goggles, or pagination.

Response shape (verified live 2026-08-31 from the page's OpenAPI schema): a top-level `type` of `"suggest"`, a required `query` object (the shared search `query` model, `query.original` among its members), and the suggestion list as the top-level `results` array (default `[]`). Each element carries required `query` (the suggested query completion), nullable `type` (kind of suggestion — `query` or `entity`, with unrecognized kinds to be treated as plain query suggestions), nullable deprecated `is_entity` (superseded by `type`; this CLI does not carry it), and the nullable rich members `title`, `description`, and `img` (enriched title, description, and image url). Errors use the shared error envelope.

### 4.12 `spellcheck/search` — CLI `brave-search spellcheck <query>`

- Source: https://api-dashboard.search.brave.com/api-reference/other/spell_check
- Retrieval: 2026-08-30, verified live
- Methods: GET only

| Wire name | Type | Constraints / default | Verified |
| --- | --- | --- | --- |
| `q` | string | required; ≤400 chars, ≤50 words | live |
| `country` | string | 2-char or `ALL`; default `US` (enum incl. `ALL` re-verified live 2026-08-31) | live |
| `lang` | string | language hint; default `en` (wire name `lang` confirmed live 2026-08-31, as suggest) | live |

CLI mapping: language option → `lang` (same special case as suggest); `--country→country`; nothing else is exposed.

Response shape (verified live 2026-08-31 from the page's OpenAPI schema): a top-level `type` of `"spellcheck"`, a required `query` object (the shared search `query` model), and the corrections as the top-level `results` array. Each element carries exactly one member, required `query` — the spellcheck-corrected query — so the correction list is the answer: an empty `results` array means the query needed no correction. Errors use the shared error envelope.

## 5. Goggles input rules (CLI-side, from the reconciled design research)

- `--goggle <url-or-inline>`, `--goggle-file <path>` (repeatable), `--include-site <domain>`, `--exclude-site <domain>` are mutually exclusive input strategies.
- Site shortcuts compile into **one** inline Goggle so the upstream maximum of 3 Goggles stays enforceable; site values must be IDNA-normalized domains and reject schemes, paths, ports, whitespace, control characters, commas, `$`, and newlines (DSL injection).
- Goggle files: regular UTF-8 files only, read through a 2 MiB bound, FIFOs/devices rejected; validate the documented limits (100,000 instructions; 500 code points per instruction; wildcard and caret limits). File-derived content never appears in logs or error text — report source and byte length only.

## 6. Cross-cutting CLI validation summary

- Query validation: 1–400 Unicode code points, ≤50 whitespace-delimited words (word = nonempty run under `Character.isWhitespace`/`isSpaceChar`), reject all-whitespace, transmit valid bytes without normalization.
- `--page` and `--all-pages` are mutually exclusive; `--max-pages` requires `--all-pages`; machine metadata reports user-facing `page` plus zero-based `upstream_offset`.
- `--output raw` only for single-response operations; rejected before dispatch with `--all-pages`, >20 Place enrichment IDs, or any fan-out (exit 2).
- Multi-page requests are sequential (never parallel bursts); rate-limit windows with `0` remaining are awaited cancellably up to the window reset; abort on first failed page; JSONL may end in an error record, human/JSON emit only failure plus counts; raw aggregation prohibited.
- Endpoint-inapplicable options are rejected locally with exit 2.

## 7. Provenance

Each source URL below was fetched with `curl` (browser user agent) and its SHA-256 computed over the exact response bytes (`curl <url> | shasum -a 256`) on the stated retrieval date. A hash pins the fetched rendering of the page (HTML plus embedded JSON), not its semantic content: a later re-fetch may legitimately produce different bytes (embedded build IDs, asset digests, etc.). URLs marked `hash: omitted` could not be fetched as meaningful page bytes and record the reason instead.

| Fetched URL | Retrieval date | SHA-256 |
| --- | --- | --- |
| https://api-dashboard.search.brave.com/api-reference/web/search/get | 2026-08-30 | f91871d8b14f6322a95f575dbd0d0f999d04a30bed775629b72c17aa4bd48cdb |
| https://api-dashboard.search.brave.com/api-reference/web/search/post | 2026-08-30 | 6a4cc53d39b88c893b3a655186c959d832b682ac3c16cd2c846ff7b28b4d72de |
| https://api-dashboard.search.brave.com/documentation/services/llm-context | 2026-08-30 | c48b7de7c738fc6291f651f6a6bd2331cbce93ca58f1fcf9a8707bad8914d1ae |
| https://api-dashboard.search.brave.com/api-reference/summarizer/llm_context/get | 2026-08-30 | 0938329443d190b99b88595a3a5a909eae3547e5dacc1b74d33e20068df05b43 |
| https://api-dashboard.search.brave.com/api-reference/news/news_search/get | 2026-08-30 | 406c41ad9ea155da76143aa8f4ebe1c7bbf0d383b626e3d2e66092b9719f0ad5 |
| https://api-dashboard.search.brave.com/api-reference/news/news_search/post | 2026-08-30 | 7cf8a17e8547f3c5afaace2577ea2f2436b66b3eee162da53a8d5800aca641bb |
| https://api-dashboard.search.brave.com/api-reference/videos/video_search/get | 2026-08-30 | 9996ba1cde0a5fef9508cf773a370a7e5a0a49d53bb92d328c82ce77dc3b0e48 |
| https://api-dashboard.search.brave.com/api-reference/videos/video_search/post | 2026-08-30 | cd21f61304db71107d9ef44f65ac08686fa818b6a1063a8cb032feb9762f1ec5 |
| https://api-dashboard.search.brave.com/api-reference/images/image_search | 2026-08-30 | 184fe6bf87d9b8a70bb1939439e1aad044d64b3805527bfb5657742e8a785e2b |
| https://api-dashboard.search.brave.com/documentation/services/place-search | 2026-08-30 | cdca473b07186b84326c6067e62c0f7796fe490b7a71316eb447041656e55ec0 |
| https://api-dashboard.search.brave.com/documentation/services/answers | 2026-08-30 | fc7b5075dc36caa763fb8bc4685306d4fded4eb4f12a38e38aba7672737d362d |
| https://api-dashboard.search.brave.com/api-reference/summarizer/answers | 2026-08-30 | 098553627f18b49d3f5c4815c5362cbdfd0ae8c75bacff2d28e3464ed94e82bc |
| https://api-dashboard.search.brave.com/api-reference/other/suggestions | 2026-08-30 | d7c6ad693dd9a80b5c171b6ac87cbfe2ae45d203062d02accd17b43e75d6827d |
| https://api-dashboard.search.brave.com/api-reference/other/spell_check | 2026-08-30 | 7ccb8b96c6bb5c7214f26371872f8cc8dc8d184ba13e1478bb1a770a30768a9d |
| https://api-dashboard.search.brave.com/api-reference/web/rich/get | 2026-08-30 | hash: omitted (HTTP 404 — no public reference page exists; consistent with §4.10) |
