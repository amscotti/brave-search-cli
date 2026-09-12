package io.amscotti.bravesearch.application.port.out;

import io.amscotti.bravesearch.domain.config.Credential;

/**
 * The effective credential plus the provenance the presentation layer may render freely: the
 * winning source's name and whether the canonical environment variable shadowed a set alias.
 * Both provenance parts carry presence only, never a value, and the credential itself renders
 * redacted — so {@link #toString()} of this record is safe on any diagnostic channel.
 *
 * @param credential the effective credential
 * @param sourceName the winning source, for example {@code environment BRAVE_API_KEY}
 * @param aliasShadowed whether a set compatibility alias was shadowed by the canonical variable
 */
public record ResolvedCredential(Credential credential, String sourceName, boolean aliasShadowed) {}
