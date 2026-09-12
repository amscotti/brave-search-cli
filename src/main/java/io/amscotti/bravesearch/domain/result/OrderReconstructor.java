package io.amscotti.bravesearch.domain.result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The order reconstruction of one enrichment chunk: the returned entries of one
 * completed upstream request are matched back onto the input ids of that request by
 * id, so every input position — duplicates included — reappears at its original
 * position carrying either its returned payload or the placeholder of a missing or
 * expired id.
 *
 * <p>The matching rule is id equality, nothing more: a returned id fills every input
 * position that carries it — a duplicate input id is filled at each of its positions —
 * and the first returned entry of an id is the payload those positions carry, because
 * the enrichment endpoints document one entry per requested id, so several entries of
 * one id are not an expected shape and the first one wins deterministically. Entries
 * no input position asked for are ignored; they cannot break the order. The
 * reconstruction is pure over ids and payloads — opaque ids are matched, never parsed —
 * so the JSON reading of a response stays with the extractors that feed it.
 *
 * <p>The machine representation of an absent id is the slot itself: {@link Slot#present()}
 * false with the input id and its original position, rendered by the output modes as a
 * {@code present:false} record member and a human {@code (not returned)} line. The
 * counts state the whole story: {@code returnedCount} counts filled input positions and
 * {@code missingCount} the placeholder positions, together exactly the input size.
 */
public final class OrderReconstructor {

    private OrderReconstructor() {}

    /**
     * One returned entry of an enrichment response: the opaque id it answers and the
     * payload the endpoint attached to that id.
     */
    public record Identified<E>(String id, E value) {

        public Identified {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * One input position after reconstruction: the original zero-based input position,
     * the opaque input id, and — when the response carried an entry for that id — its
     * payload; {@code null} payload means the id was not returned, and {@link #present()}
     * is the honest question to ask.
     */
    public record Slot<E>(int position, String id, E value) {

        public Slot {
            if (position < 0) {
                throw new IllegalArgumentException("position must not be negative: " + position);
            }
            Objects.requireNonNull(id, "id");
        }

        /** Whether the response carried an entry this position's id matches. */
        public boolean present() {
            return value != null;
        }
    }

    /**
     * One finished reconstruction: every input position in input order plus the counts
     * of filled and placeholder positions.
     */
    public record Reconstruction<E>(List<Slot<E>> slots, int returnedCount, int missingCount) {

        public Reconstruction {
            slots = List.copyOf(slots);
            slots.forEach(Objects::requireNonNull);
            if (returnedCount + missingCount != slots.size()) {
                throw new IllegalArgumentException("the counts must partition the slots");
            }
        }
    }

    /**
     * Reconstructs the input order of one chunk from its returned entries.
     *
     * @param inputIds the chunk's input ids, duplicates and order included
     * @param returned the entries the chunk's response carried
     */
    public static <E> Reconstruction<E> reconstruct(List<String> inputIds, List<Identified<E>> returned) {
        Objects.requireNonNull(inputIds, "inputIds");
        Objects.requireNonNull(returned, "returned");
        inputIds.forEach(id -> Objects.requireNonNull(id, "inputId"));
        Map<String, E> payloadById = new HashMap<>();
        for (Identified<E> entry : returned) {
            Objects.requireNonNull(entry, "returnedEntry");
            payloadById.putIfAbsent(entry.id(), entry.value());
        }
        List<Slot<E>> slots = new ArrayList<>(inputIds.size());
        int returnedCount = 0;
        for (int position = 0; position < inputIds.size(); position++) {
            String id = inputIds.get(position);
            E value = payloadById.get(id);
            if (value != null) {
                returnedCount++;
            }
            slots.add(new Slot<>(position, id, value));
        }
        return new Reconstruction<>(slots, returnedCount, inputIds.size() - returnedCount);
    }
}
