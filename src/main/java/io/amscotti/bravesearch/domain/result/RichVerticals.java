package io.amscotti.bravesearch.domain.result;

import java.util.List;
import java.util.Objects;

/**
 * The tolerant projection of one rich callback body: the vertical blocks the response
 * carried and nothing more. The upstream response shape of the rich endpoint is
 * undocumented, so this projection deliberately models no item internals beyond the
 * common textual members a human listing needs — every top-level array member is one
 * vertical, named by its own member name, counted by its whole array size, and carrying
 * one item slot per object element with whatever usable textual members it offered.
 *
 * <p>Non-array top-level members — the shared {@code type} and {@code query} blocks and
 * any future member — are not verticals and never consume a position: a vertical's
 * position is its zero-based ordinal among the vertical blocks, in document order.
 */
public record RichVerticals(List<Vertical> verticals) {

    public RichVerticals {
        verticals = List.copyOf(Objects.requireNonNull(verticals, "verticals"));
    }

    /** The total number of items across every vertical block, counting non-object elements too. */
    public int itemCount() {
        return verticals.stream().mapToInt(Vertical::itemCount).sum();
    }

    /** One vertical block: its member name, its ordinal among the blocks, and its items. */
    public record Vertical(int position, String name, int itemCount, List<Item> items) {

        public Vertical {
            Objects.requireNonNull(name, "name");
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
            if (itemCount < 0) {
                throw new IllegalArgumentException("itemCount must not be negative");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * One item slot of a vertical block: the usable textual members a human listing can
     * render — title, url, description, and the third-party provider attribution — each
     * null when the item carried no textual form of it. The lossless item stays in the
     * upstream body; this slot never claims to model it.
     *
     * <p>The member set {@code title}, {@code url}, {@code description}, {@code source} is
     * this CLI's pinned assumption, not an endpoint contract: the upstream response shape
     * is undocumented, so exactly these four names are read and every other member is
     * ignored. The set widens only when the endpoint's contract is documented.
     */
    public record Item(String title, String url, String description, String source) {}
}
