package io.amscotti.bravesearch.domain.output;

import java.util.Objects;

/**
 * One member line of a human listing entry: the member's plain upstream text and the
 * treatment the shared human layout renders it with.
 *
 * <p>Lives beside {@link OutputRequest} as shared output-contract vocabulary — like the
 * projection field types, it is a renderer-neutral value the presentation adapters
 * assemble, so no adapter instantiates another adapter by describing its entry members.
 */
public record HumanMember(String text, Kind kind) {

    /** How the shared human layout treats one member line of an entry. */
    public enum Kind {

        /** A url: dim, and never wrapped — a url that exceeds the width overflows whole. */
        URL,

        /** Body text: default intensity, word-wrapped at the width minus the gutter. */
        TEXT,

        /** Per-type metadata: dim, word-wrapped, and rendered after every other member. */
        META
    }

    public HumanMember {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
    }
}
