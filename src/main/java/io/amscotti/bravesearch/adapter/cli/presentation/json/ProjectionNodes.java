package io.amscotti.bravesearch.adapter.cli.presentation.json;

import io.amscotti.bravesearch.domain.result.Projection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Conversion of domain projection values into JSON tree nodes in insertion order. */
final class ProjectionNodes {

    private ProjectionNodes() {}

    static ObjectNode toObject(Iterable<Projection.Field> fields, JsonNodeFactory factory) {
        ObjectNode object = factory.objectNode();
        for (Projection.Field field : fields) {
            object.set(field.key(), toNode(field.value(), factory));
        }
        return object;
    }

    static JsonNode toNode(Projection.Value value, JsonNodeFactory factory) {
        return switch (value) {
            case Projection.Text text -> factory.textNode(text.text());
            case Projection.Decimal decimal -> factory.numberNode(decimal.number());
            case Projection.Flag flag -> factory.booleanNode(flag.flag());
            case Projection.Nested nested -> toObject(nested.projection().fields(), factory);
            case Projection.Sequence sequence -> sequenceNode(sequence, factory);
        };
    }

    private static JsonNode sequenceNode(Projection.Sequence sequence, JsonNodeFactory factory) {
        ArrayNode array = factory.arrayNode();
        for (Projection.Value element : sequence.values()) {
            array.add(toNode(element, factory));
        }
        return array;
    }
}
