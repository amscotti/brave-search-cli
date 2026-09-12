# ADR 0002: JSON envelope and raw output contract

- Status: Accepted
- Date: 2026-08-30
- Verified against the web tracer: 2026-08-31 (see Verification)

## Context

Machine consumers of the CLI need two guarantees that pull in opposite directions: a **stable
schema** they can parse across releases, and **lossless fidelity** with what the Brave API
actually returned, including fields this project's projections do not model. Human output
cannot serve either role. Raw output serves byte-level pipelines and debugging, where the
question "what did the server send?" must have an exact answer. Both need unambiguous rules
for multi-request commands, for transport compression, for oversized bodies, and for streaming
output, so that behavior never depends on incidental transport details.

## Decision

1. **Two-member success envelope.** Every JSON success object carries exactly two members
   under `data`:
   - `data.projection` — the stable, schema-versioned fields this project owns and validates;
   - `data.upstream` — the lossless upstream tree exactly as decoded from the response.
   There is no third member and no merging between the two. Projection schema evolution is
   decoupled from upstream drift: new server fields appear in `data.upstream` immediately,
   without a release of this project.

2. **Multi-request envelope shape.** Commands that issue more than one upstream request store
   `data.upstream` as an ordered JSON array of `{request_index, meta, body}` objects, in
   request order. Single-request commands store the upstream tree directly.

3. **Decoded-body raw exactness.** `--output raw` emits the exact **decoded** body bytes:
   content-coding removed; for streams, the decoded SSE framing; for non-streaming responses,
   the decoded body bytes. The guarantee is decoded-body exactness, not wire-byte exactness —
   transport-layer artifacts such as compression or chunked framing are intentionally not
   reproduced.

4. **Identity encoding for raw.** Raw mode sends `Accept-Encoding: identity`, so the body the
   server produces arrives without transport compression and the decoded body equals what was
   sent on the wire. This is what makes the decoded-body guarantee concrete and testable.

5. **Size bounds with fail-closed overflow.** Non-streaming decoded bodies are bounded to
   16 MiB; structured upstream error bodies used in diagnostics and envelopes are bounded to
   1 MiB. Raw mode buffers limit-plus-one bytes: an overflow is detected at the boundary and
   nothing is emitted. A truncated body is never silently presented as a complete one;
   oversized inputs fail closed with a bounded error instead of unbounded memory use.

6. **JSONL framing.** JSON Lines output emits one independent JSON object per line. Records
   carry `schema_version`, `type`, and `command` fields; a terminal summary record follows on
   success and an error record on failure. Headings, progress chatter, and ANSI escape
   sequences never appear in JSONL output under any circumstances — every line is
   independently parseable by a naive line reader, in a pipeline or out.

## Consequences

- Consumers can migrate to projection fields at their own pace while retaining full upstream
  fidelity; additive upstream changes require no parser changes.
- The envelope under `data` is closed: adding a member is a schema change that requires a
  `schema_version` bump, not a quiet additive edit.
- Raw consumers get byte fidelity against decoded fixtures, and identity encoding removes the
  common compression divergence; tests compare against decoded fixture bytes rather than
  captured wire bytes.
- Fail-closed size handling trades the possibility of emitting very large (or silently
  partial) payloads for predictable memory and unambiguous errors.
- JSONL stays incrementally parseable and deterministic, at the cost of forbidding any
  human-oriented decoration in that mode; human formatting belongs to the human presentation
  mode only.

## Verification

Verified against the web tracer (2026-08-31): whole-process runs — the JVM start script and
the native binary against a scripted loopback server — checked every decision against the
running executables.

- The two-member split held: one LF-terminated success envelope, schema-validated in-process
  against `schemas/v1`, carried `data.projection` and `data.upstream` and nothing else;
  upstream numbers survived to stdout at exact scale, including a 22-digit significand and a
  trailing-zero cost.
- Decoded-body raw exactness held: with identity encoding, raw stdout was byte-identical to
  the served body, and a failure body rode the raw failure path unchanged.
- JSONL framing held: one independently schema-valid record per logical result in
  presentation order, then exactly one summary record, with no decoration anywhere.
- One clarification, consistent with this record and the metadata contract rather than a
  divergence: the tracer's fixture response carried an `X-Request-ID` header, and the envelope
  preserved it twice by design — as `meta.request_id` and as the one unknown field of
  `meta.usage`. Unknown-preservation did what the metadata contract says. No superseding
  rationale is warranted.
