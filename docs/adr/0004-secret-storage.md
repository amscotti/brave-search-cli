# ADR 0004: Secret storage policy

- Status: Accepted
- Date: 2026-08-30

## Context

The CLI needs an API key before it can talk to Brave, and the places that key can live have
very different security properties. Environment variables are the deployment-native
mechanism agents and CI already use; a local config file adds convenience for interactive
users but introduces an on-disk secret with filesystem-mediated attacks (symlinks, hard
links, permission drift, substitution during writes). An earlier architecture draft
additionally allowed a fallback path for filesystems without a directory-pinning API:
reading attributes and file bytes through `NOFOLLOW_LINKS` opens plus before/after
identity checks, instead of holding one pinned `SecureDirectoryStream` for the whole
write. Finally, not every platform the build reaches has POSIX owner-only modes at all —
Windows does not — so a storage decision had to state where file credentials are
advertised at all.

## Decision

### Environment credentials everywhere

`BRAVE_API_KEY` (canonical) and `BRAVE_SEARCH_API_KEY` (compatibility alias) are supported
on every platform and always win over the file. A present-but-invalid source fails closed
with exit `3` instead of falling back, so a broken explicit setting is reported, never
masked. Verbose provenance reports only source names and a shadowed flag — never a value.

### Config-file credentials on POSIX with owner-only modes

The file backend is advertised on macOS and Linux only, at the platform-native paths
(macOS ignores `XDG_CONFIG_HOME`). The stored guarantee is a user-owned `0700` managed
directory and a user-owned `0600` single-link regular file, created and replaced through
the pinned-directory algorithm of `docs/config-security.md` (random `CREATE_NEW` temp,
fsync, identity revalidation, atomic same-directory move, fail closed). A present-but-invalid
stored key is likewise a typed failure. `config repair-permissions` restores the mode
contract; structural violations stay refusals.

### Windows deferred, Keychain a future option

Windows file persistence stays disabled until a user-only DACL creation and verification
contract is implemented — the POSIX mode checks do not translate, and silently downgrading
them would be exactly the quiet weakening this policy exists to prevent. macOS Keychain is
recorded as a possible future backend, not a v1 commitment.

### Fail closed when directory pinning is unavailable

Deviation from that earlier allowance: instead of a second, `NOFOLLOW_LINKS`-plus-
identity-checks code path for filesystems that cannot return a `SecureDirectoryStream`,
the store refuses those filesystems outright. The JDK on both advertised platforms returns
a pinned `UnixSecureDirectoryStream`, so the fallback branch would exist only for
filesystems nobody runs and would double the attack surface under test. A filesystem that
cannot pin directory operations cannot honor the refusal contract reliably, so it fails
closed with exit `3` and the old config is retained. The same reasoning applies to
unsupported atomic moves: retain the old config rather than downgrade secret safety.

## Consequences

- The residual same-user directory-swap limitation and the other accepted residuals are
  documented in `docs/config-security.md` rather than claimed away; same-user attackers
  are out of scope for v1.
- An atomic replacement does not preserve the replaced file's ACLs or extended
  attributes; operators needing such metadata must re-establish it after writes.
- Secret input has no `--key` flag by policy (arguments leak through shell history and
  process listings), and `set-key` never writes on a failed or interrupted read, so the
  prior configuration survives every failure path.
- Adding any future backend (Keychain, Windows DACL) means implementing the same refusal
  and fail-closed semantics, or explicitly amending this record.
