package io.amscotti.bravesearch.domain.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Immutability contracts of the ordered projection value. */
final class ProjectionTest {

    @Test
    void fieldsAreCopiedOutOfTheCallerList() {
        List<Projection.Field> fields = new ArrayList<>();
        fields.add(new Projection.Field("count", new Projection.Decimal(BigDecimal.TEN)));
        Projection projection = new Projection(fields);
        fields.add(new Projection.Field("extra", new Projection.Flag(true)));
        assertEquals(1, projection.fields().size());
        assertThrows(
                UnsupportedOperationException.class, () -> projection.fields().add(new Projection.Field("x", new Projection.Flag(false))));
    }

    @Test
    void sequenceValuesAreCopiedOutOfTheCallerList() {
        List<Projection.Value> values = new ArrayList<>();
        values.add(new Projection.Text("first"));
        Projection.Sequence sequence = new Projection.Sequence(values);
        values.add(new Projection.Text("second"));
        assertEquals(1, sequence.values().size());
    }

    @Test
    void emptyProjectionCarriesNoFields() {
        assertEquals(List.of(), Projection.empty().fields());
    }

    @Test
    void duplicateKeysAreRejectedAtConstruction() {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Projection(
                                List.of(
                                        new Projection.Field("count", new Projection.Decimal(BigDecimal.TEN)),
                                        new Projection.Field("count", new Projection.Flag(true)))));
        assertTrue(failure.getMessage().contains("count"), () -> "the rejection must name the duplicate: " + failure.getMessage());
    }

    @Test
    void reservedFramingKeysAreRejectedAtConstruction() {
        for (String reserved : List.of("schema_version", "type", "command")) {
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new Projection(
                                    List.of(new Projection.Field(reserved, new Projection.Flag(true)))),
                            () -> reserved + " belongs to the machine framing, not to projection fields");
            assertTrue(
                    failure.getMessage().contains(reserved),
                    () -> "the rejection must name the reserved key: " + failure.getMessage());
        }
    }

    @Test
    void nullKeysAndNullValuesAreRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new Projection.Field(null, new Projection.Flag(true)));
        assertThrows(NullPointerException.class, () -> new Projection.Field("count", null));
    }

    @Test
    void nestedProjectionsEnforceTheSameFieldRules() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Projection(List.of(
                        new Projection.Field(
                                "page",
                                new Projection.Nested(new Projection(List.of(
                                        new Projection.Field("type", new Projection.Text("web")))))))));
    }
}
