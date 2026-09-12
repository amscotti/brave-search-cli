package io.amscotti.bravesearch.adapter.bravehttp.endpoint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.BraveApiRequest;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.request.AnswersRequest;
import io.amscotti.bravesearch.domain.request.SafeSearch;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Wire form of the Answers chat-completions request, pinned field for field: POST only,
 * {@code Content-Type: application/json}, one {@code user} message, and a body whose
 * every search-control member sits inside the nested {@code web_search_options} object —
 * never flat at the top level — with both levels in alphabetical wire-name order and every
 * unsupplied member omitted. The minimal request proves the omission lattice; the full
 * request proves the exact bytes of every member at once.
 */
final class AnswersEndpointTest {

    private static final ObjectMapper READER = new ObjectMapper();

    private static final BraveApiOrigin ORIGIN = BraveApiOrigin.production();

    private static final Credential TOKEN = Credential.of("token-bytes".getBytes(UTF_8));

    @Test
    void theMinimalBlockingBodyIsExactlyOneUserMessageAndTheStreamFlag() {
        BraveApiRequest request = AnswersEndpoint.assemble(
                AnswersRequest.builder("what is the brave search api").stream(false).build(), ORIGIN, TOKEN);

        assertEquals(BraveApiRequest.Method.POST, request.method());
        assertEquals("https://api.search.brave.com/res/v1/chat/completions", request.uri().toString());
        assertEquals(
                "{\"messages\":[{\"content\":\"what is the brave search api\",\"role\":\"user\"}],\"stream\":false}",
                new String(request.bodyBytes(), UTF_8),
                "the minimal body carries exactly the one user message and the stream flag");
        assertTrue(
                request.headers().stream()
                        .anyMatch(line -> line.name().equals("Content-Type") && line.value().equals("application/json")),
                "a POST carrying its JSON body closes the fixed header assembly with the body type");
        assertTrue(
                request.headers().stream().anyMatch(line -> line.name().equals("Accept") && line.value().equals("application/json")),
                "a blocking answers exchange accepts application/json");
    }

    @Test
    void theFullRequestBodyIsTheExactNestedAlphabeticalBytes() {
        BraveApiRequest request = AnswersEndpoint.assemble(fullRequest(), ORIGIN, TOKEN);

        assertEquals(
                "{\"max_completion_tokens\":1024,"
                        + "\"messages\":[{\"content\":\"what is the brave search api\",\"role\":\"user\"}],"
                        + "\"metadata\":{\"user_id\":\"agent-1\"},"
                        + "\"model\":\"brave-pro\","
                        + "\"seed\":42,"
                        + "\"stream\":true,"
                        + "\"web_search_options\":{"
                        + "\"country\":\"US\","
                        + "\"enable_citations\":true,"
                        + "\"enable_entities\":false,"
                        + "\"enable_research\":true,"
                        + "\"language\":\"en\","
                        + "\"research_allow_thinking\":false,"
                        + "\"research_maximum_iterations\":3,"
                        + "\"research_maximum_queries\":10,"
                        + "\"research_maximum_results_per_query\":30,"
                        + "\"research_maximum_seconds\":60,"
                        + "\"research_maximum_tokens_per_query\":4096,"
                        + "\"safesearch\":\"strict\"}}",
                new String(request.bodyBytes(), UTF_8),
                "the full body is pinned byte for byte: top-level members and web_search_options both alphabetical, every search control nested");
    }

    @Test
    void noSearchControlEverRidesTheTopLevelOfTheBody() throws Exception {
        JsonNode body = READER.readTree(
                AnswersEndpoint.assemble(fullRequest(), ORIGIN, TOKEN).bodyBytes());

        List<String> topLevel = new java.util.ArrayList<>();
        body.properties().forEach(field -> topLevel.add(field.getKey()));
        assertEquals(
                List.of("max_completion_tokens", "messages", "metadata", "model", "seed", "stream", "web_search_options"),
                topLevel.stream().sorted().toList(),
                "the top level carries exactly the chat members plus the one nested options container");
        for (String flat : new String[] {
            "country", "language", "safesearch", "enable_citations", "enable_entities", "enable_research",
            "research_allow_thinking", "research_maximum_iterations", "research_maximum_queries",
            "research_maximum_results_per_query", "research_maximum_seconds", "research_maximum_tokens_per_query"
        }) {
            assertFalse(body.has(flat), "the search control " + flat + " must never ride the top level");
            assertTrue(
                    body.path("web_search_options").has(flat),
                    "the search control " + flat + " rides inside web_search_options");
        }
    }

    @Test
    void anEmptyOptionsContainerAndAbsentMembersAreOmittedNotEmptied() throws Exception {
        BraveApiRequest request = AnswersEndpoint.assemble(
                AnswersRequest.builder("q").stream(true).model("future-model-x").build(), ORIGIN, TOKEN);

        JsonNode body = READER.readTree(request.bodyBytes());
        assertFalse(body.has("web_search_options"), "an unsupplied options container is omitted entirely");
        assertFalse(body.has("max_completion_tokens"));
        assertFalse(body.has("seed"));
        assertFalse(body.has("metadata"));
        assertEquals("future-model-x", body.path("model").asText(), "an unknown model spelling passes unchanged");
    }

    @Test
    void exactlyOneUserMessageRidesEveryRequest() throws Exception {
        for (boolean stream : new boolean[] {true, false}) {
            JsonNode messages = READER.readTree(
                            AnswersEndpoint.assemble(AnswersRequest.builder("q").stream(stream).build(), ORIGIN, TOKEN)
                                    .bodyBytes())
                    .path("messages");
            assertEquals(1, messages.size(), "exactly one message rides the request");
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("q", messages.get(0).path("content").asText());
        }
    }

    @Test
    void aStreamingAssemblyAcceptsTheEventStreamAndNegotiatesIdentityEncoding() {
        BraveApiRequest request = AnswersEndpoint.assembleStream(
                AnswersRequest.builder("q").stream(true).build(), ORIGIN, TOKEN, "2026-08-30");

        assertTrue(
                request.headers().stream()
                        .anyMatch(line -> line.name().equals("Accept") && line.value().equals("text/event-stream")),
                "a streaming exchange accepts the event-stream media type");
        assertTrue(
                request.headers().stream()
                        .anyMatch(line -> line.name().equals("Accept-Encoding") && line.value().equals("identity")),
                "a streaming exchange negotiates identity encoding, because one body serves the raw mode that needs decoded-exact event-stream bytes");
        assertFalse(
                request.headers().stream()
                        .anyMatch(line -> line.name().equals("Accept-Encoding") && !line.value().equals("identity")),
                "no compressed encoding is ever negotiated for a stream");
        assertTrue(
                request.rawResponseMode(),
                "the streaming assembly travels in raw response mode: identity coding, no structured parsing on the transport");
        assertTrue(
                request.headers().stream().anyMatch(line -> line.name().equals("Api-Version") && line.value().equals("2026-08-30")),
                "the version pin rides a streaming assembly exactly as spelled");
        assertEquals(
                "{\"messages\":[{\"content\":\"q\",\"role\":\"user\"}],\"stream\":true}",
                new String(request.bodyBytes(), UTF_8),
                "the streaming body is the same chat-completions form with the stream flag true");
    }

    @Test
    void aStreamingAssemblyRefusesABlockingRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AnswersEndpoint.assembleStream(
                        AnswersRequest.builder("q").stream(false).build(), ORIGIN, TOKEN, null),
                "only a stream=true request may open a streaming exchange");
    }

    @Test
    void theVersionPinAndMetadataOrderingReachTheWireDeterministically() throws Exception {
        BraveApiRequest pinned = AnswersEndpoint.assemble(fullRequest(), ORIGIN, TOKEN, "2026-08-30");
        assertTrue(
                pinned.headers().stream().anyMatch(line -> line.name().equals("Api-Version") && line.value().equals("2026-08-30")),
                "the pinned version travels on the wire exactly as spelled");

        Map<String, String> metadata = new TreeMap<>();
        metadata.put("user_id", "agent-1");
        metadata.put("alpha", "first");
        BraveApiRequest request = AnswersEndpoint.assemble(
                AnswersRequest.builder("q").stream(false).metadata(metadata).build(), ORIGIN, TOKEN);
        JsonNode wireMetadata = READER.readTree(request.bodyBytes()).path("metadata");
        assertEquals(
                List.of("alpha", "user_id"),
                java.util.stream.StreamSupport.stream(wireMetadata.properties().spliterator(), false)
                        .map(Map.Entry::getKey)
                        .toList(),
                "metadata members ride in alphabetical order so identical requests render identical bytes");
    }

    private static AnswersRequest fullRequest() {
        return AnswersRequest.builder("what is the brave search api")
                .stream(true)
                .model("brave-pro")
                .maxCompletionTokens(1024)
                .seed(42)
                .citations(true)
                .entities(false)
                .research(true)
                .researchAllowThinking(false)
                .researchMaximumTokensPerQuery(4096)
                .researchMaximumQueries(10)
                .researchMaximumIterations(3)
                .researchMaximumSeconds(60)
                .researchMaximumResultsPerQuery(30)
                .country("US")
                .language("en")
                .safeSearch(SafeSearch.STRICT)
                .metadata(Map.of("user_id", "agent-1"))
                .build();
    }
}
