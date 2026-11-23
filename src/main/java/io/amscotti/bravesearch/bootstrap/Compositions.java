package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import java.util.Objects;
import java.util.function.Function;

/**
 * The process wiring of every remote command: one composition per endpoint, held together so
 * the root command line takes a single wiring record instead of one constructor parameter per
 * endpoint — adding an endpoint grows this record and nothing in {@link Main}'s signature.
 *
 * <p>Each {@code with…} method returns a copy carrying one replaced composition. That is how a
 * test injects a double for exactly the endpoint it exercises while every other command keeps
 * its process wiring, and why no partial constructor chain exists: the wiring is always whole.
 */
public record Compositions(
        WebSearchComposition webSearch,
        ContextComposition contextSearch,
        NewsSearchComposition newsSearch,
        VideoSearchComposition videosSearch,
        ImageSearchComposition imagesSearch,
        SuggestComposition suggestSearch,
        SpellcheckComposition spellcheckSearch,
        PlacesComposition placesSearch,
        PlacesEnrichmentComposition placesEnrichment,
        RichComposition richCallbacks,
        AnswersComposition answersSearch) {

    public Compositions {
        Objects.requireNonNull(webSearch, "webSearch");
        Objects.requireNonNull(contextSearch, "contextSearch");
        Objects.requireNonNull(newsSearch, "newsSearch");
        Objects.requireNonNull(videosSearch, "videosSearch");
        Objects.requireNonNull(imagesSearch, "imagesSearch");
        Objects.requireNonNull(suggestSearch, "suggestSearch");
        Objects.requireNonNull(spellcheckSearch, "spellcheckSearch");
        Objects.requireNonNull(placesSearch, "placesSearch");
        Objects.requireNonNull(placesEnrichment, "placesEnrichment");
        Objects.requireNonNull(richCallbacks, "richCallbacks");
        Objects.requireNonNull(answersSearch, "answersSearch");
    }

    /** The process wiring: real environment lookups, real transports, one machine mapper set. */
    public static Compositions process(CredentialProvider storedCredentials, CancellationRegistry registry) {
        return process(storedCredentials, EnvironmentCredentialSource.processEnvironmentLookup(), registry);
    }

    /** The shared process wiring over machine codecs of its own. */
    static Compositions process(CredentialProvider storedCredentials, Function<String, String> environment, CancellationRegistry registry) {
        JsonMappers mappers = new JsonMappers();
        return process(storedCredentials, environment, registry, mappers, new JsonlCodec(mappers));
    }

    /**
     * The shared process wiring behind the process factory, with the environment lookup and the
     * machine codecs injected: every composition of one process encodes through the same mapper
     * set, so a process builds exactly one however many endpoints it wires.
     */
    static Compositions process(
            CredentialProvider storedCredentials,
            Function<String, String> environment,
            CancellationRegistry registry,
            JsonMappers mappers,
            JsonlCodec jsonl) {
        return new Compositions(
                WebSearchComposition.process(storedCredentials, environment, mappers, jsonl),
                ContextComposition.process(storedCredentials, environment, mappers, jsonl),
                NewsSearchComposition.process(storedCredentials, environment, mappers, jsonl),
                VideoSearchComposition.process(storedCredentials, environment, mappers, jsonl),
                ImageSearchComposition.process(storedCredentials, environment, mappers, jsonl),
                SuggestComposition.process(storedCredentials, environment, mappers, jsonl),
                SpellcheckComposition.process(storedCredentials, environment, mappers, jsonl),
                PlacesComposition.process(storedCredentials, environment, mappers, jsonl),
                PlacesEnrichmentComposition.process(storedCredentials, environment, mappers, jsonl),
                RichComposition.process(storedCredentials, environment, mappers, jsonl),
                AnswersComposition.process(storedCredentials, environment, registry, mappers, jsonl));
    }

    /** This wiring with the web command's composition replaced. */
    public Compositions withWebSearch(WebSearchComposition replacement) {
        return new Compositions(
                replacement,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the context command's composition replaced. */
    public Compositions withContext(ContextComposition replacement) {
        return new Compositions(
                webSearch,
                replacement,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the news command's composition replaced. */
    public Compositions withNewsSearch(NewsSearchComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                replacement,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the videos command's composition replaced. */
    public Compositions withVideosSearch(VideoSearchComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                replacement,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the images command's composition replaced. */
    public Compositions withImagesSearch(ImageSearchComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                replacement,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the suggest command's composition replaced. */
    public Compositions withSuggestSearch(SuggestComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                replacement,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the spellcheck command's composition replaced. */
    public Compositions withSpellcheckSearch(SpellcheckComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                replacement,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the places search command's composition replaced. */
    public Compositions withPlacesSearch(PlacesComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                replacement,
                placesEnrichment,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the place enrichment commands' composition replaced. */
    public Compositions withPlacesEnrichment(PlacesEnrichmentComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                replacement,
                richCallbacks,
                answersSearch);
    }

    /** This wiring with the rich command's composition replaced. */
    public Compositions withRichCallbacks(RichComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                replacement,
                answersSearch);
    }

    /** This wiring with the answers command's composition replaced. */
    public Compositions withAnswersSearch(AnswersComposition replacement) {
        return new Compositions(
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                replacement);
    }
}
