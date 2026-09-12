# Credential and config security

Durable security contract of the brave-search credential handling: where credentials come
from, where the config file lives, how it is written and refused, how a secret may be typed
in, and what the residual risks are. The requirement identifiers in
`docs/contract-index.yaml` that begin with `CONFIG-` map to the sections below and to the
tests that pin them.

## Credential precedence

The effective credential is resolved in this fixed order:

1. `BRAVE_API_KEY` — the Brave-documented name, highest precedence.
2. `BRAVE_SEARCH_API_KEY` — a compatibility alias.
3. The config file key stored by `brave-search config set-key`.
4. Missing-credential failure (exit `3`).

A source that is present but broken — empty, whitespace-only, containing control
characters, not strictly round-tripping UTF-8 (tokens must survive header assembly
byte-exact), or larger than 4 KiB — ends resolution with a configuration error (exit `3`).
A resolved token carrying any character above U+00FF fails the same way before any
exchange: an HTTP header value travels as ISO-8859-1 bytes, so such a token can never be
sent, and the credential preflight rejects it with one redacted diagnostic line instead of
letting every exchange die at request assembly. Resolution never falls back to a lower
source, because silently ignoring an explicitly broken setting would mask the operator's
mistake, and it is never used as-is. `config show` renders exactly this precedence,
including which lower-precedence sources are shadowed.

## Credential file paths

- macOS: `${user.home}/Library/Application Support/brave-search/config.json` — the native
  path; `XDG_CONFIG_HOME` is ignored even when set.
- Linux: `${XDG_CONFIG_HOME}/brave-search/config.json` when the variable is set, nonblank,
  and absolute; otherwise `${user.home}/.config/brave-search/config.json`. A set-but-blank
  or relative `XDG_CONFIG_HOME` is a configuration error (exit `3`) — an operator who
  points the variable somewhere expects that somewhere honored or reported, never quietly
  bypassed.
- Windows is not an advertised platform for the current release. File persistence stays
  disabled there until a user-only DACL creation and verification contract exists, because
  POSIX owner-only modes do not translate; until then `%APPDATA%\brave-search\config.json`
  is reserved but unimplemented. macOS Keychain remains a possible future backend.

## Config file format

The file holds one compact JSON object, UTF-8, terminated by exactly one LF:

```json
{"schema_version":"1","api_key":"<token>"}
```

Only the schema version and the API key are stored. The file is bounded to 64 KiB; reading
accepts exactly one strict JSON object — valid UTF-8, no duplicate keys, no trailing
tokens — rejects unsupported schema versions, and ignores unknown fields as explicitly
forward-compatible. The token itself is treated as opaque: valid UTF-8 token bytes
round-trip byte-for-byte, and a token that violates the bounds above is rejected when the
file is loaded.

## Secure write algorithm

Every write — storing or clearing the key — runs the same exchange inside the managed
directory:

1. Create or validate the parent without following symlinks: a real directory, owned by
   the current user, with mode `0700` where POSIX modes exist.
2. Open the directory as a pinned `SecureDirectoryStream` and re-check its identity and
   ownership after opening, so the entry operations that follow — attribute reads, the
   temporary file, the replacement — are dir-relative and cannot be redirected by a path
   swap. Two probes remain path-based by necessity: the hard-link count and the post-move
   directory fsync (below); the count fails closed when the swap makes it unobservable.
3. Validate the existing destination (if any) the same way loads validate it, remembering
   its identity.
4. Create a random-named sibling temp file with `CREATE_NEW` and mode `0600`, write the
   document, and fsync it.
5. Revalidate the temp file (regular, `0600`, owner, single hard link) and the destination
   (same identity as remembered, or still absent) with no-follow attribute reads.
6. Atomically move the temp file over the destination within the same pinned directory.
7. Best-effort fsync of the directory afterwards. This fsync opens the directory by path only
   after the pinned stream has closed, so an ancestor swap could redirect it to a different
   directory: the written destination itself is still safe and the replacement atomic, but the
   durability claim is correspondingly best-effort.

Fail closed everywhere: when the filesystem cannot pin directory operations, cannot
fsync, or cannot perform the atomic same-directory move, the write fails with exit `3` and
the old config is retained — secret safety is never silently downgraded. On a failed or
interrupted secret read nothing is written at all, because the store is only reached after
the input was accepted.

## Refusal conditions

Both loads and writes refuse, with exit `3` and a diagnostic that names only paths,
modes, and identities:

- a destination or config file that is a symbolic link;
- a regular file with more than one hard link — the count is probed through the path-based
  `unix:nlink` attribute (there is no dir-relative accessor), and an unobservable count is
  itself a refusal rather than a pass: both advertised platforms expose it, so anything else
  is suspicious;
- a non-regular file (a directory, device, or other special file) at the config path;
- a config file or managed directory owned by another user;
- insecure permissions: the file not owner-only (`0600` or stricter), the directory not
  `0700`;
- substitution during the operation: the config file appearing, vanishing, or changing
  identity between the checks around a write.

## Repairing permissions

`brave-search config repair-permissions` resets the managed directory to `0700` and tightens
the credential file toward `0600`, reporting exactly which parts were reset. File repair is
tighten-only: an already owner-only mode stricter than `0600` (for example `0400`) is left
exactly as it is and never loosened, and the `0600` reset applies only when non-owner bits are
present, removing them and restoring the owner's read/write bits. When nothing needed
changing, the confirmation speaks only for the POSIX modes — "POSIX modes already owner-only
(macOS ACLs not inspected)" — never for the full contract.

On macOS, repair additionally attempts a bounded best-effort `ls -le` listing of the file; if
the listing shows an access-control entry beyond the owner, repair prints an advisory line
pointing the operator at it. The advisory never fails the repair and never claims the entries
were removed.

Repair restores modes only. Structural violations — symbolic links, foreign ownership, extra
hard links — are refusals of the store itself, not conditions a mode reset could fix, so a
successful repair never claims more safety than it restored.

## Secret input rules

There is deliberately no `--key` flag: process arguments are visible in shell history and
process listings, so the token must never become one.

`brave-search config set-key` reads the secret from the attached console with echo
disabled. When no console exists — a piped or detached process — the secret is read from
standard input only when `--stdin` is explicitly requested; without a console and without
`--stdin` the command fails with exit `3` rather than reading anything implicitly.

The `--stdin` contract is exact: one nonempty line of at most 4 KiB, valid UTF-8,
terminated by LF or CRLF; only that terminator is stripped, so interior and surrounding
whitespace survive byte-for-byte. End of file before the terminator, control characters,
invalid UTF-8, a line above the bound, and any non-whitespace after the line are all
rejected; blank lines and whitespace after the secret are tolerated. Detecting that trailing
non-whitespace requires consuming stdin to EOF, so by contract `--stdin` blocks until the
feeding pipe or terminal closes. An interrupted or failing read rejects the input, and the
prior configuration is left untouched on every failure path.

On the console path the typed characters are encoded straight to UTF-8 bytes through a
reporting encoder — no intermediate `String` is built, characters the encoder refuses fail as
a validation error, and the character buffer is wiped after use. The in-memory lifetime
tradeoff is accepted and bounded: the file-encoding path (`JsonConfigFile`) works through
Java `String`s that cannot be wiped, so token bytes persist on the heap between encode and
write. An attacker who can read process memory is outside the threat model; the contract
covers only where the token travels outside the process.

## Config show redaction

`brave-search config show` prints the effective source (`environment BRAVE_API_KEY`, the
alias, the config file, or `missing`), the resolved config path with its state, and which
lower-precedence sources are shadowed. It never prints the credential, a fingerprint, a
length, or any other reusable information about the token. A shadowed file that cannot be
read safely is reported as exactly that; a file holding an invalid key still counts as a
shadowed stored key.

`config show --output json` — the command's one machine mode; jsonl, raw, and `--pretty`
are usage errors — writes exactly one compact, LF-terminated, schema-versioned record
pinned by `schemas/v1/config-show.schema.json`: `command` is `config.show`, `source` is
the winning source's provenance name, `config_path` the resolved path,
`api_key_present` whether an effective credential resolved (false only for the
missing-everywhere case), and `shadowed` the overridden lower-precedence sources in
precedence order. The record is derived from the same redaction-safe projection as the
human text, so the machine channel carries presence and provenance only.

Every config command's stdout — the fixed human lines and the machine record alike —
travels through the byte-lossless result channel of `docs/cli-contract.md`'s exit-code
table: a downstream broken pipe ends the run as the silent success, and any other write
failure exits `6` with exactly one stderr diagnostic line.

## Exit 3 conditions

The local-configuration exit status `3` covers: no credential source provides a key; a
present source provides an invalid one; the config file or its directory fails a refusal
condition; the platform has no supported credential-file path or the path settings are
unusable; the filesystem cannot support the safe-access contract; and a secret that cannot
be read because no console exists and `--stdin` was not requested, or because the stdin
line violated the one-line contract.

## Residual threat model

The v1 guarantee is owner-only modes plus best-effort resistance to symlink, hard-link,
and ordinary substitution attacks, with these accepted residuals:

- **macOS ACL blind spot.** POSIX mode checks cannot see access-control lists: on macOS the
  JDK exposes no ACL file attribute view, so a group- or other-oriented ACE can coexist with
  modes that read `0600`, and neither loads nor stores detect it. Only the file's owner can
  attach ACLs, so this does not widen access by itself, but it silently breaks the
  owner-only expectation. The repair command's bounded `ls -le` advisory is the only window
  v1 offers; a future hardening may inspect ACLs by shelling out on the load/store paths too.
- A concurrent same-user attacker who replaces an ancestor directory of the managed path —
  including the managed directory's own entry in its parent — can suppress an update: the
  pinned write still lands safely inside the validated directory, but the visible path may
  keep the attacker's file. Detecting this requires pinning parent directories, which the
  JDK does not expose; the same-user attacker is out of scope.
- An atomic replacement intentionally does not preserve the old file's ACLs or extended
  attributes; re-establish such metadata explicitly after a write if it is required.
- Narrow time-of-check/time-of-use windows remain between no-follow attribute reads and
  the subsequent opens; the identity revalidation around each boundary narrows but does not
  eliminate them. The hard-link count and the post-move directory fsync are read through
  path-based probes for the same reason, and both fail closed or degrade to best effort
  rather than claiming more than they verified.
- The owner comparison uses principal names. The hard-link count is a refusal when it cannot
  be observed, because both advertised platforms expose it.
