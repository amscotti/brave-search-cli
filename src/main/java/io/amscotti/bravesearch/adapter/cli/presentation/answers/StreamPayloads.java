package io.amscotti.bravesearch.adapter.cli.presentation.answers;

import io.amscotti.bravesearch.domain.metadata.Usage;
import io.amscotti.bravesearch.domain.result.Projection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The tolerant member mapping of streamed tag payloads: the documented Brave members and
 * the chat-completions token spellings land on the values the machine surfaces carry — the
 * documented token spelling preferred over its aliases whichever order the payload names
 * them in — at exact decimal scale, while an unparseable member drops alone and leaves a note that names
 * the member — never its value, because payload text is untrusted input. The citation and
 * entity payloads project exactly the members each record family carries; a member this
 * version does not know is omitted from the projection, never guessed.
 */
final class StreamPayloads {

    private StreamPayloads() {}

    /** The usage value of a usage-tag payload; every field stays absent when unparsed. */
    static Usage usage(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        List<String> notes = new ArrayList<>();
        Long requests = null;
        Long queries = null;
        Long tokensIn = null;
        Long tokensOut = null;
        boolean tokensInDocumented = false;
        boolean tokensOutDocumented = false;
        BigDecimal requestsCost = null;
        BigDecimal queriesCost = null;
        BigDecimal tokensInCost = null;
        BigDecimal tokensOutCost = null;
        BigDecimal totalCost = null;
        Map<String, String> unknown = new LinkedHashMap<>();
        if (payload.isObject()) {
            for (Map.Entry<String, JsonNode> member : payload.properties()) {
                switch (member.getKey()) {
                    case "requests" -> requests = counter(member.getKey(), member.getValue(), notes);
                    case "queries" -> queries = counter(member.getKey(), member.getValue(), notes);
                    case "tokens_in" -> {
                        tokensInDocumented = true;
                        tokensIn = counter(member.getKey(), member.getValue(), notes);
                    }
                    case "input_tokens", "prompt_tokens" -> tokensIn =
                            alternateSpelling(tokensInDocumented, tokensIn, counter(member.getKey(), member.getValue(), notes));
                    case "tokens_out" -> {
                        tokensOutDocumented = true;
                        tokensOut = counter(member.getKey(), member.getValue(), notes);
                    }
                    case "output_tokens", "completion_tokens" -> tokensOut =
                            alternateSpelling(tokensOutDocumented, tokensOut, counter(member.getKey(), member.getValue(), notes));
                    case "requests_cost" -> requestsCost = cost(member.getKey(), member.getValue(), notes);
                    case "queries_cost" -> queriesCost = cost(member.getKey(), member.getValue(), notes);
                    case "tokens_in_cost" -> tokensInCost = cost(member.getKey(), member.getValue(), notes);
                    case "tokens_out_cost" -> tokensOutCost = cost(member.getKey(), member.getValue(), notes);
                    case "total_cost" -> totalCost = cost(member.getKey(), member.getValue(), notes);
                    default -> unknown.put(member.getKey(), scalarText(member.getValue()));
                }
            }
        }
        return new Usage(
                requests,
                queries,
                tokensIn,
                tokensOut,
                requestsCost,
                queriesCost,
                tokensInCost,
                tokensOutCost,
                totalCost,
                unknown,
                notes);
    }

    /** The projected citation members — number, url, favicon, snippet, and the two indexes — in that order. */
    static List<Projection.Field> citation(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        List<Projection.Field> fields = new ArrayList<>(6);
        long number = integer(payload.path("number"));
        if (number >= 0) {
            fields.add(new Projection.Field("number", new Projection.Decimal(BigDecimal.valueOf(number))));
        }
        String url = text(payload.path("url"));
        if (url != null) {
            fields.add(new Projection.Field("url", new Projection.Text(url)));
        }
        String favicon = text(payload.path("favicon"));
        if (favicon != null) {
            fields.add(new Projection.Field("favicon", new Projection.Text(favicon)));
        }
        String snippet = text(payload.path("snippet"));
        if (snippet != null) {
            fields.add(new Projection.Field("snippet", new Projection.Text(snippet)));
        }
        long start = integer(payload.path("start_index"));
        if (start >= 0) {
            fields.add(new Projection.Field("start_index", new Projection.Decimal(BigDecimal.valueOf(start))));
        }
        long end = integer(payload.path("end_index"));
        if (end >= 0) {
            fields.add(new Projection.Field("end_index", new Projection.Decimal(BigDecimal.valueOf(end))));
        }
        return fields;
    }

    /**
     * The projected entity members — name, url, entity_type, description — in that order. The
     * payload's kind member is spelled {@code type} upstream but lands as {@code entity_type},
     * because {@code type} is a reserved framing name no record or projection member may carry.
     */
    static List<Projection.Field> entity(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        List<Projection.Field> fields = new ArrayList<>(4);
        String name = text(payload.path("name"));
        if (name != null) {
            fields.add(new Projection.Field("name", new Projection.Text(name)));
        }
        String url = text(payload.path("url"));
        if (url != null) {
            fields.add(new Projection.Field("url", new Projection.Text(url)));
        }
        String type = text(payload.path("type"));
        if (type != null) {
            fields.add(new Projection.Field("entity_type", new Projection.Text(type)));
        }
        String description = text(payload.path("description"));
        if (description != null) {
            fields.add(new Projection.Field("description", new Projection.Text(description)));
        }
        return fields;
    }

    /**
     * An alternate spelling's reading: it fills the value only while the documented
     * spelling has not appeared and nothing else was read, whichever order the payload
     * spells its members in, so the documented member never yields to a chat-completions
     * alias — not even to repair the documented member's own failed parse.
     */
    private static Long alternateSpelling(boolean documentedSeen, Long already, Long parsed) {
        if (documentedSeen || already != null || parsed == null) {
            return already;
        }
        return parsed;
    }

    private static Long counter(String member, JsonNode value, List<String> notes) {
        long parsed = integer(value);
        if (parsed >= 0) {
            return parsed;
        }
        notes.add(member + " is not a nonnegative integer");
        return null;
    }

    private static BigDecimal cost(String member, JsonNode value, List<String> notes) {
        BigDecimal parsed = decimal(value);
        if (parsed == null) {
            notes.add(member + " is not a nonnegative decimal");
            return null;
        }
        if (parsed.signum() < 0) {
            notes.add(member + " is not a nonnegative decimal");
            return null;
        }
        return parsed;
    }

    /**
     * A nonnegative integer member: a number without fraction or a textual digit run. A
     * number beyond the long range is refused like its textual spelling, because blind
     * narrowing would wrap it into a different, valid-looking counter.
     */
    private static long integer(JsonNode value) {
        if (value.isIntegralNumber()) {
            return value.canConvertToLong() ? value.longValue() : -1;
        }
        if (value.isTextual() && value.stringValue().matches("\\d+")) {
            try {
                return Long.parseLong(value.stringValue());
            } catch (NumberFormatException oversized) {
                return -1;
            }
        }
        return -1;
    }

    /** A decimal member at its exact reported scale: a number or a textual decimal. */
    private static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.stringValue());
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode value) {
        return value.isTextual() && !value.stringValue().isEmpty() ? value.stringValue() : null;
    }

    private static String scalarText(JsonNode value) {
        if (value.isTextual()) {
            return value.stringValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().toPlainString();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        return "<non-scalar>";
    }
}
