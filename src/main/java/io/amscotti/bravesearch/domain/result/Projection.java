package io.amscotti.bravesearch.domain.result;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered projection fields this CLI owns inside {@code data.projection}.
 *
 * <p>Field order is insertion order and becomes the exact wire order of the encoded object. The
 * value vocabulary is JSON-agnostic so the domain carries no serialization library; per-command
 * projections grow by constructing richer field lists, not by changing this shape.
 *
 * <p>Names are part of the contract: keys are unique, never {@code null}-valued (a field that is
 * absent is omitted from the wire object, never emitted as {@code null}), and never one of the
 * reserved framing names, because a projection that smuggled framing keys could collide with the
 * envelope or record shape around it.
 */
public record Projection(List<Field> fields) {

    /** Member names owned by the machine framing around a projection; fields never use them. */
    public static final Set<String> RESERVED_FRAMING_KEYS = Set.of("schema_version", "type", "command");

    public Projection {
        fields = fields == null ? List.of() : List.copyOf(fields);
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            if (RESERVED_FRAMING_KEYS.contains(field.key())) {
                throw new IllegalArgumentException(
                        "projection field '" + field.key() + "' is reserved for the machine framing");
            }
            if (!seen.add(field.key())) {
                throw new IllegalArgumentException("duplicate projection field '" + field.key() + "'");
            }
        }
    }

    /** The projection with no fields, used for example by a zero-result success. */
    public static Projection empty() {
        return new Projection(List.of());
    }

    /** One named projection member; the key is unique within its projection and the value never {@code null}. */
    public record Field(String key, Value value) {

        public Field {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** A projection value: text, exact decimal, flag, nested object, or ordered sequence. */
    public sealed interface Value permits Text, Decimal, Flag, Nested, Sequence {}

    /** Free-form text member. */
    public record Text(String text) implements Value {}

    /** Exact numeric member; decimals keep their scale on the wire. */
    public record Decimal(BigDecimal number) implements Value {}

    /** Boolean member. */
    public record Flag(boolean flag) implements Value {}

    /** Nested object member. */
    public record Nested(Projection projection) implements Value {}

    /** Ordered sequence member. */
    public record Sequence(List<Value> values) implements Value {

        public Sequence {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
