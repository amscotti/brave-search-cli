package io.amscotti.bravesearch.api;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The lossless upstream tree of one completed exchange, held privately and handed out only
 * as fresh deep copies.
 *
 * <p>Every public response carries one; its {@link #snapshot()} returns a caller-owned copy
 * that the client never sees again — callers may mutate, restructure, or hand off the
 * returned tree freely, and no other snapshot of the same exchange observes the change.
 * The held tree was parsed once, tolerantly, from the exact bounded body the upstream sent,
 * with decimals kept at their exact scale, so a snapshot never lies about the wire.
 */
public final class UpstreamTree {

    private final JsonNode root;

    private UpstreamTree(JsonNode root) {
        this.root = root;
    }

    /**
     * Wraps the parsed upstream tree.
     *
     * @throws NullPointerException when {@code root} is null
     */
    public static UpstreamTree of(JsonNode root) {
        return new UpstreamTree(Objects.requireNonNull(root, "root"));
    }

    /** A fresh caller-owned deep copy of the upstream tree; every call returns an independent tree. */
    public JsonNode snapshot() {
        return root.deepCopy();
    }

    /**
     * Structural equality over the held roots — never over snapshots, which would deep-copy
     * the whole tree just to compare it — while the roots themselves stay private.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof UpstreamTree that && root.equals(that.root);
    }

    /** The structural hash of the held root; equal trees hash equal without any copying. */
    @Override
    public int hashCode() {
        return root.hashCode();
    }

    /** Renders the held root's JSON; the held tree stays private and uncopied. */
    @Override
    public String toString() {
        return root.toString();
    }
}
