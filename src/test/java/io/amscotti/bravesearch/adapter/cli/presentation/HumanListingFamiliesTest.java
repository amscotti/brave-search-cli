package io.amscotti.bravesearch.adapter.cli.presentation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.images.ImageSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.json.EnvelopeCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.RawCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.news.NewsSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescribePresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDescriptionsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlaceDetailsPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlacesProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.places.PlacesSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.rich.RichProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.spellcheck.SpellcheckPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.spellcheck.SpellcheckProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.suggest.SuggestPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.suggest.SuggestProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.videos.VideoSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebPagedSearchPresenterImpl;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebProjectionExtractor;
import io.amscotti.bravesearch.adapter.cli.presentation.web.WebSearchPresenterImpl;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import io.amscotti.bravesearch.domain.result.PagedSearch;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import io.amscotti.bravesearch.domain.result.RichResult;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The shared-layout structural test across every listing family: one fixture — the same
 * long title, the same over-wide url, the same body text — rendered through every
 * family's own presenter twice, plain and colorable, so a family that diverges from the
 * shared entry layout fails here before any byte-exact test hides the drift behind its
 * own expectations.
 *
 * <p>The pinned invariants are the ones the human layout contract fixes for every
 * family: the index gutter right-aligned to the widest index with member and
 * continuation lines aligned under the title column; blank lines only after the heading
 * block and between entries, never after the last entry; the count line closing the
 * document; a plain render with zero escape bytes; a colorable render whose styling
 * never moves a byte of layout (stripped of its SGR wraps it is the plain document);
 * every wrapped line of an entry title bold; and a url member never wrapped — a url
 * longer than the width overflows whole on one line.
 *
 * <p>A new listing family joins this test the day it gains a presenter: the family
 * set below is exhaustive by contract, so a family missing from it is a layout left
 * unpinned.
 */
final class HumanListingFamiliesTest {

    /** The narrowest documented render width, so the shared title and url must wrap and overflow. */
    private static final int WIDTH = 40;

    /** The member indent of a two-entry listing: one column wider than the index plus two spaces. */
    private static final String INDENT = " ".repeat(4);

    private static final String TITLE =
            "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi";

    /** A url wider than the render width: it must stay one intact line in every family. */
    private static final String URL = "https://example.com/" + "u".repeat(45);

    private static final String DESCRIPTION = "Shared body description sentence for the layout fixture.";

    private static final Pattern VERTICAL_LABEL_LINE = Pattern.compile("^[a-z]+ \\(2\\):$");

    /** One listing family's structural expectations over the shared fixture. */
    private record Family(String name, String heading, String title, boolean urlMember, String countLine) {}

    private final JsonMappers mappers = new JsonMappers();

    private final JsonlCodec jsonl = new JsonlCodec(mappers);

    private final EnvelopeCodec envelopes = new EnvelopeCodec(mappers);

    private final RawCodec raw = new RawCodec();

    @Test
    void everyListingFamilyRendersTheSharedEntryLayout() {
        assertSharedLayout(
                "web",
                "Web results for: layout query",
                TITLE,
                URL,
                "2 results.",
                output -> renderSingle(
                        new WebSearchPresenterImpl(envelopes, jsonl, raw, webExtractor(), warnings("web")),
                        webOutcome(),
                        WebSearchRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "news",
                "News results for: layout query",
                TITLE,
                URL,
                "2 results.",
                output -> renderSingle(
                        new NewsSearchPresenterImpl(envelopes, jsonl, raw, newsExtractor(), warnings("news")),
                        newsOutcome(),
                        NewsSearchRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "videos",
                "Videos results for: layout query",
                TITLE,
                URL,
                "2 results.",
                output -> renderSingle(
                        new VideoSearchPresenterImpl(envelopes, jsonl, raw, videoExtractor(), warnings("videos")),
                        videosOutcome(),
                        VideoSearchRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "images",
                "Images results for: layout query",
                TITLE,
                URL,
                "2 results.",
                output -> renderSingle(
                        new ImageSearchPresenterImpl(envelopes, jsonl, raw, imageExtractor(), warnings("images")),
                        imagesOutcome(),
                        ImageSearchRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "suggest",
                "Suggestions for: layout query",
                TITLE,
                null,
                "2 suggestions.",
                output -> renderSingle(
                        new SuggestPresenterImpl(envelopes, jsonl, raw, suggestExtractor(), warnings("suggest")),
                        suggestOutcome(),
                        SuggestRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "spellcheck",
                "Spellcheck results for: layout query",
                TITLE,
                null,
                "2 corrections.",
                output -> renderSingle(
                        new SpellcheckPresenterImpl(
                                envelopes, jsonl, raw, spellcheckExtractor(), warnings("spellcheck")),
                        spellcheckOutcome(),
                        SpellcheckRequest.builder("layout query").build(),
                        output));
        assertSharedLayout(
                "places search",
                "Places for: layout query",
                TITLE,
                URL,
                "2 places.",
                output -> renderSingle(
                        new PlacesSearchPresenterImpl(
                                envelopes, jsonl, raw, placesExtractor(), warnings("places.search")),
                        placesOutcome(),
                        PlaceSearchRequest.builder().query("layout query").build(),
                        output));
        assertSharedLayout(
                "places details",
                "Place details for 2 ids",
                TITLE,
                URL,
                "2 ids, 2 returned, 0 not returned.",
                output -> renderEnrichmentDetails(output));
        assertSharedLayout(
                "places describe",
                "Place descriptions for 2 ids",
                "layout-id-one",
                "layout-id-two",
                null,
                null,
                "2 ids, 2 returned, 0 not returned.",
                output -> renderEnrichmentDescribe(output));
        assertSharedLayout(
                "rich",
                "Rich results for: cb-layout",
                TITLE,
                URL,
                "1 vertical, 2 items.",
                output -> renderSingle(
                        new RichPresenterImpl(envelopes, jsonl, raw, richExtractor(), warnings("rich")),
                        richOutcome(),
                        new RichRequest("cb-layout"),
                        output));
        assertSharedLayout(
                "web walk",
                "Web results for: layout query",
                TITLE,
                TITLE,
                URL,
                URL + "2",
                "2 results across 1 of 1 requested pages, 0 duplicates removed.",
                output -> renderWalk(new WebPagedSearchPresenterImpl(envelopes, jsonl, webExtractor(), warnings("web")), webWalkBody(), WebSearchRequest.builder("layout query").build(), output, this::webResult));
        assertSharedLayout(
                "news walk",
                "News results for: layout query",
                TITLE,
                TITLE,
                URL,
                URL + "2",
                "2 results across 1 of 1 requested pages, 0 duplicates removed.",
                output -> renderWalk(new NewsPagedSearchPresenterImpl(envelopes, jsonl, newsExtractor(), warnings("news")), newsWalkBody(), NewsSearchRequest.builder("layout query").build(), output, body -> new NewsSearchResult(200, payload(body), null, null, null, null)));
        assertSharedLayout(
                "videos walk",
                "Videos results for: layout query",
                TITLE,
                TITLE,
                URL,
                URL + "2",
                "2 results across 1 of 1 requested pages, 0 duplicates removed.",
                output -> renderWalk(new VideoPagedSearchPresenterImpl(envelopes, jsonl, videoExtractor(), warnings("videos")), videosWalkBody(), VideoSearchRequest.builder("layout query").build(), output, body -> new VideoSearchResult(200, payload(body), null, null, null, null)));
    }

    // ------------------------------------------------------------------ assertions

    private void assertSharedLayout(
            String family,
            String heading,
            String title,
            String entryUrl,
            String countLine,
            Function<OutputRequest, String> renderer) {
        assertSharedLayout(family, heading, title, title, entryUrl, entryUrl, countLine, renderer);
    }

    private void assertSharedLayout(
            String family,
            String heading,
            String title,
            String secondTitle,
            String firstEntryUrl,
            String secondEntryUrl,
            String countLine,
            Function<OutputRequest, String> renderer) {
        String plain = renderer.apply(human(false));
        String colored = renderer.apply(human(true));

        assertEquals(0, countEscapes(plain), family + ": a plain render carries zero escape bytes");
        assertEquals(
                plain,
                stripSgr(colored),
                family + ": styling never moves a byte of layout — stripped of SGR wraps the colorable"
                        + " render is the plain document");

        List<String> lines = plain.lines().toList();
        assertTrue(lines.size() > 4, family + ": the shared fixture must render a substantial listing");
        assertEquals(heading, lines.getFirst(), family + ": the heading line opens the document");
        assertEquals("", lines.get(1), family + ": exactly one blank line follows the heading block");
        assertEquals(countLine, lines.getLast(), family + ": the count line closes the document");

        int firstEntry = entryLine(lines, " 1  ", family);
        int secondEntry = entryLine(lines, " 2  ", family);
        assertTrue(firstEntry > 1, family + ": the first entry follows the heading");
        assertTrue(secondEntry > firstEntry, family + ": the second entry follows the first");
        assertEquals("", lines.get(secondEntry - 1), family + ": exactly one blank line separates the entries");
        assertFalse(
                lines.get(secondEntry + 1).isBlank(),
                family + ": no blank line follows the last entry before the count line");
        long blankLines = lines.stream().filter(String::isBlank).count();
        assertEquals(2, blankLines, family + ": blank lines appear only after the heading and between the entries");

        for (int index = 2; index < lines.size() - 1; index++) {
            String line = lines.get(index);
            if (line.isBlank() || index == firstEntry || index == secondEntry) {
                continue;
            }
            assertTrue(
                    line.startsWith(INDENT) || VERTICAL_LABEL_LINE.matcher(line).matches(),
                    family + ": every member and continuation line aligns under the title column: <" + line + ">");
        }

        if (firstEntryUrl != null) {
            assertTrue(
                    lines.subList(firstEntry + 1, secondEntry - 1).contains(INDENT + firstEntryUrl),
                    family + ": the first entry's own url sits intact on one line inside its block,"
                            + " never wrapped");
        }
        if (secondEntryUrl != null) {
            assertTrue(
                    lines.subList(secondEntry + 1, lines.size() - 1).contains(INDENT + secondEntryUrl),
                    family + ": the second entry's own url sits intact on one line inside its block,"
                            + " never wrapped");
        }

        List<String> wrappedTitle = TextWrap.words(title, WIDTH - INDENT.length());
        assertFalse(wrappedTitle.isEmpty(), family + ": the shared title must wrap for the bold assertion");
        List<String> wrappedSecondTitle = TextWrap.words(secondTitle, WIDTH - INDENT.length());
        assertTrue(
                colored.lines().anyMatch(line -> line.equals(" 1  " + sgrBold(wrappedTitle.getFirst()))),
                family + ": the colorable render bolds the first entry's opening title line");
        assertTrue(
                colored.lines()
                        .anyMatch(line -> line.equals(" 2  " + sgrBold(wrappedSecondTitle.getFirst()))),
                family + ": the colorable render bolds the second entry's opening title line");
        for (int segment = 1; segment < wrappedTitle.size(); segment++) {
            String continuation = INDENT + sgrBold(wrappedTitle.get(segment));
            long occurrences = colored.lines().filter(line -> line.equals(continuation)).count();
            assertTrue(
                    occurrences >= 2,
                    family + ": every wrapped title line is bold in each entry, continuation " + segment);
        }
        assertTrue(
                colored.lines().anyMatch(line -> line.equals(sgrBold(heading))),
                family + ": the colorable render bolds the heading");
    }

    private static int entryLine(List<String> lines, String prefix, String family) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                return index;
            }
        }
        throw new AssertionError(family + ": no entry line starts with <" + prefix + ">");
    }

    private static String sgrBold(String text) {
        return "\u001b[1m" + text + "\u001b[0m";
    }

    private static String stripSgr(String text) {
        return text.replaceAll("\u001b\\[[0-9]*m", "");
    }

    private static long countEscapes(String text) {
        return text.chars().filter(character -> character == 0x1b).count();
    }

    private static OutputRequest human(boolean colorable) {
        return new OutputRequest(false, OutputMode.HUMAN, false, false, false, colorable, WIDTH);
    }

    // ------------------------------------------------------------------ rendering

    private <TReq, TRes> String renderSingle(
            SearchPresenter<TReq, TRes> presenter,
            Outcome.Success<TRes> outcome,
            TReq request,
            OutputRequest output) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        List<String> diagnostics = new java.util.ArrayList<>();
        int exit = presenter.present(outcome, request, output, new OutputStreamResultWriter(stdout), diagnostics::add);
        assertEquals(0, exit, () -> "the single render must succeed: " + diagnostics + " stdout=<" + stdout + ">");
        return stdout.toString(UTF_8);
    }

    private <TReq, TRes extends io.amscotti.bravesearch.domain.result.PagedExchange> String renderWalk(
            PagedSearchPresenter<TReq, TRes> presenter,
            String body,
            TReq request,
            OutputRequest output,
            Function<String, TRes> resultFactory) {
        TRes result = resultFactory.apply(body);
        PaginationService.PagedRun<TRes> run =
                new PaginationService.PagedRun<>(1, List.of(new PagedSearch.Page<>(0, 1, null, result)), null);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(run, request, output, new OutputStreamResultWriter(stdout), diagnostic -> {});
        assertEquals(0, exit, "the walk render must succeed");
        return stdout.toString(UTF_8);
    }

    private String renderEnrichmentDetails(OutputRequest output) {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DETAILS, List.of("layout-id-one", "layout-id-two"));
        PlaceEnrichmentResult result =
                new PlaceEnrichmentResult(200, new UpstreamPayload(enrichmentBody().getBytes(UTF_8)), null, null, null, null);
        PaginationService.PagedRun<PlaceEnrichmentResult> run =
                new PaginationService.PagedRun<>(1, List.of(new PagedSearch.Page<>(0, 1, null, result)), null);
        PlaceDetailsPresenterImpl presenter =
                new PlaceDetailsPresenterImpl(envelopes, jsonl, placeDetailsExtractor(), warnings("places.details"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(run, request, output, new OutputStreamResultWriter(stdout), diagnostic -> {});
        assertEquals(0, exit, "the details render must succeed");
        return stdout.toString(UTF_8);
    }

    private String renderEnrichmentDescribe(OutputRequest output) {
        PlaceEnrichmentRequest request = new PlaceEnrichmentRequest(
                PlaceEnrichmentRequest.Kind.DESCRIPTIONS, List.of("layout-id-one", "layout-id-two"));
        PlaceEnrichmentResult result =
                new PlaceEnrichmentResult(200, new UpstreamPayload(describeBody().getBytes(UTF_8)), null, null, null, null);
        PaginationService.PagedRun<PlaceEnrichmentResult> run =
                new PaginationService.PagedRun<>(1, List.of(new PagedSearch.Page<>(0, 1, null, result)), null);
        PlaceDescribePresenterImpl presenter =
                new PlaceDescribePresenterImpl(envelopes, jsonl, placeDescriptionsExtractor(), warnings("places.describe"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = presenter.present(run, request, output, new OutputStreamResultWriter(stdout), diagnostic -> {});
        assertEquals(0, exit, "the describe render must succeed");
        return stdout.toString(UTF_8);
    }

    private SearchPresenterBase.WarningsRouter warnings(String command) {
        return (mode, diagnostics, results, quiet) ->
                new ModeAwareWarnings(mode, command, diagnostics, results, jsonl, quiet);
    }

    private WebProjectionExtractor webExtractor() {
        return new WebProjectionExtractor(mappers);
    }

    private NewsProjectionExtractor newsExtractor() {
        return new NewsProjectionExtractor(mappers);
    }

    private VideoProjectionExtractor videoExtractor() {
        return new VideoProjectionExtractor(mappers);
    }

    private ImageProjectionExtractor imageExtractor() {
        return new ImageProjectionExtractor(mappers);
    }

    private SuggestProjectionExtractor suggestExtractor() {
        return new SuggestProjectionExtractor(mappers);
    }

    private SpellcheckProjectionExtractor spellcheckExtractor() {
        return new SpellcheckProjectionExtractor(mappers);
    }

    private PlacesProjectionExtractor placesExtractor() {
        return new PlacesProjectionExtractor(mappers);
    }

    private PlaceDetailsExtractor placeDetailsExtractor() {
        return new PlaceDetailsExtractor(mappers);
    }

    private PlaceDescriptionsExtractor placeDescriptionsExtractor() {
        return new PlaceDescriptionsExtractor(mappers);
    }

    private RichProjectionExtractor richExtractor() {
        return new RichProjectionExtractor(mappers);
    }

    // ------------------------------------------------------------------ fixture bodies

    private Outcome.Success<WebSearchResult> webOutcome() {
        return new Outcome.Success<>(webResult(webBody()));
    }

    private Outcome.Success<NewsSearchResult> newsOutcome() {
        return new Outcome.Success<>(new NewsSearchResult(200, payload(newsBody()), null, null, null, null));
    }

    private Outcome.Success<VideoSearchResult> videosOutcome() {
        return new Outcome.Success<>(new VideoSearchResult(200, payload(videosBody()), null, null, null, null));
    }

    private Outcome.Success<ImageSearchResult> imagesOutcome() {
        return new Outcome.Success<>(new ImageSearchResult(200, payload(imagesBody()), null, null, null, null));
    }

    private Outcome.Success<SuggestSearchResult> suggestOutcome() {
        return new Outcome.Success<>(new SuggestSearchResult(200, payload(suggestBody()), null, null, null, null));
    }

    private Outcome.Success<SpellcheckSearchResult> spellcheckOutcome() {
        return new Outcome.Success<>(new SpellcheckSearchResult(200, payload(spellcheckBody()), null, null, null, null));
    }

    private Outcome.Success<PlaceSearchResult> placesOutcome() {
        return new Outcome.Success<>(new PlaceSearchResult(200, payload(placesBody()), null, null, null, null));
    }

    private Outcome.Success<RichResult> richOutcome() {
        return new Outcome.Success<>(new RichResult(200, payload(richBody()), null, null, null, null));
    }

    private WebSearchResult webResult(String body) {
        return new WebSearchResult(200, payload(body), null, null, null, null);
    }

    private static UpstreamPayload payload(String body) {
        return new UpstreamPayload(body.getBytes(UTF_8));
    }

    private String webBody() {
        return "{\"query\":{\"original\":\"layout query\"},\"web\":{\"results\":[" + sharedEntry() + "," + sharedEntry() + "]}}";
    }

    private String newsBody() {
        return "{\"query\":{\"original\":\"layout query\"},\"news\":{\"results\":[" + sharedEntry() + "," + sharedEntry() + "]}}";
    }

    private String videosBody() {
        return "{\"type\":\"videos\",\"query\":{\"original\":\"layout query\"},\"results\":[" + sharedEntry() + ","
                + sharedEntry() + "]}";
    }

    private String imagesBody() {
        return "{\"type\":\"images\",\"query\":{\"original\":\"layout query\"},\"results\":[" + sharedEntry() + ","
                + sharedEntry() + "]}";
    }

    /** One shared entry: the members web, news, videos, and images all read the same way. */
    private static String sharedEntry() {
        return sharedEntry(URL);
    }

    /**
     * One shared entry under the given url — the walks pass distinct urls, because their
     * deduplication is by exact url string and the shared fixture must survive it.
     */
    private static String sharedEntry(String url) {
        return "{\"title\":\"" + TITLE + "\",\"url\":\"" + url + "\",\"description\":\"" + DESCRIPTION + "\"}";
    }

    /** The walk bodies: two entries identical except their urls, so deduplication keeps both. */
    private static String walkBody(String containerPrefix, String containerSuffix) {
        return containerPrefix + sharedEntry() + "," + sharedEntry(URL + "2") + containerSuffix;
    }

    private String webWalkBody() {
        return walkBody("{\"query\":{\"original\":\"layout query\"},\"web\":{\"results\":[", "]}}");
    }

    private String newsWalkBody() {
        return walkBody("{\"query\":{\"original\":\"layout query\"},\"news\":{\"results\":[", "]}}");
    }

    private String videosWalkBody() {
        return walkBody("{\"type\":\"videos\",\"query\":{\"original\":\"layout query\"},\"results\":[", "]}");
    }

    private String suggestBody() {
        return "{\"type\":\"suggest\",\"query\":{\"original\":\"layout query\"},\"results\":[{\"query\":\""
                + TITLE + "\",\"type\":\"query\"},{\"query\":\"" + TITLE + "\",\"type\":\"query\"}]}";
    }

    private String spellcheckBody() {
        return "{\"type\":\"spellcheck\",\"query\":{\"original\":\"layout query\"},\"results\":[{\"query\":\""
                + TITLE + "\"},{\"query\":\"" + TITLE + "\"}]}";
    }

    private String placesBody() {
        return "{\"type\":\"places\",\"query\":{\"original\":\"layout query\"},\"results\":[" + placeEntry() + ","
                + placeEntry() + "]}";
    }

    private static String placeEntry() {
        return "{\"id\":\"layout-place\",\"title\":\"" + TITLE + "\",\"address\":\"" + DESCRIPTION
                + "\",\"website\":\"" + URL + "\"}";
    }

    private String enrichmentBody() {
        return "{\"results\":[" + "{\"id\":\"layout-id-one\",\"title\":\"" + TITLE + "\",\"url\":\"" + URL
                + "\",\"description\":\"" + DESCRIPTION + "\"}," + "{\"id\":\"layout-id-two\",\"title\":\"" + TITLE
                + "\",\"url\":\"" + URL + "\",\"description\":\"" + DESCRIPTION + "\"}]}";
    }

    private String describeBody() {
        return "{\"results\":[" + "{\"id\":\"layout-id-one\",\"description\":\"" + DESCRIPTION + "\"},"
                + "{\"id\":\"layout-id-two\",\"description\":\"" + DESCRIPTION + "\"}]}";
    }

    private String richBody() {
        return "{\"videos\":[" + "{\"title\":\"" + TITLE + "\",\"url\":\"" + URL + "\",\"description\":\""
                + DESCRIPTION + "\",\"source\":\"Example Provider\"}," + "{\"title\":\"" + TITLE + "\",\"url\":\""
                + URL + "\",\"source\":\"Example Provider\"}]}";
    }
}
