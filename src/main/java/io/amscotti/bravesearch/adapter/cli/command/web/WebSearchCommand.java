package io.amscotti.bravesearch.adapter.cli.command.web;

import io.amscotti.bravesearch.adapter.cli.command.PagedRemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.FiniteDoubleConverter;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.GoggleOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.UnitsCandidates;
import io.amscotti.bravesearch.adapter.cli.option.UnitsConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.WebSearchDispatch;
import io.amscotti.bravesearch.application.port.out.WebSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.Units;
import io.amscotti.bravesearch.domain.request.WebSearchRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The web search command: one query, one exchange or one sequential page walk, one
 * rendering. The run policy — output compatibility, usage rejections, the single credential
 * preflight with its origin routing, the presenter handoff, and the whole {@code
 * --all-pages} walk — lives once in {@link PagedRemoteCommandSupport}; this command is the
 * web options, the request assembly, and the exchange call.
 *
 * <p>The endpoint documents every shared spelling of {@link CommonSearchOptions} — the
 * whole current inventory — plus the web-only grammar below; the complement guard still
 * runs, so a spelling added to the shared mixin later is refused here until the web
 * endpoint documents it. The injected exchange performs the request under
 * the preflight-resolved credential; the injected presenter renders a success and owns its
 * exit status; every exchange failure keeps its own kind's status with the outcome's
 * redacted diagnostic on stderr.
 */
@Command(name = WebSearchCommand.NAME, description = "Search the web with one query and print the results.")
public final class WebSearchCommand extends PagedRemoteCommandSupport<WebSearchRequest, WebSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "web";

    private final WebSearchExchange exchange;

    /** The web-only goggle input options; web is one of the endpoints that document Goggles. */
    @Mixin
    protected GoggleOptions goggleOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "One search query; quote it and use -- when it starts like an option.")
    String query;

    @Option(names = "--text-decorations", negatable = true, description = "Bold rendering decorations in result text.")
    Boolean textDecorations;

    @Option(
            names = "--result-filter",
            split = ",",
            paramLabel = "<filter>",
            description = "Result type filter; repeatable, also comma-separated.")
    List<String> resultFilters;

    @Option(
            names = "--units",
            converter = UnitsConverter.class,
            completionCandidates = UnitsCandidates.class,
            description = "metric or imperial.")
    Units units;

    @Option(names = "--extra-snippets", negatable = true, description = "Request additional result snippets.")
    Boolean extraSnippets;

    @Option(names = "--include-fetch-metadata", negatable = true, description = "Include page fetch metadata.")
    Boolean includeFetchMetadata;

    @Option(names = "--operators", negatable = true, description = "Disable query operators; on by default upstream.")
    Boolean operators;

    @Option(names = "--enable-rich-callback", negatable = true, description = "Request the rich callback result block.")
    Boolean enableRichCallback;

    @Option(
            names = "--loc-lat",
            paramLabel = "<decimal>",
            converter = FiniteDoubleConverter.class,
            description = "Location latitude, -90 to 90; pairs with --loc-long.")
    Double locLat;

    @Option(
            names = "--loc-long",
            paramLabel = "<decimal>",
            converter = FiniteDoubleConverter.class,
            description = "Location longitude, -180 to 180; pairs with --loc-lat.")
    Double locLong;

    @Option(names = "--loc-city", paramLabel = "<text>", description = "Location city name.")
    String locCity;

    @Option(names = "--loc-state", paramLabel = "<code>", description = "Location state, two uppercase letters.")
    String locState;

    @Option(names = "--loc-state-name", paramLabel = "<text>", description = "Location state full name.")
    String locStateName;

    @Option(names = "--loc-country", paramLabel = "<code>", description = "Location country, two uppercase letters.")
    String locCountry;

    @Option(names = "--loc-postal-code", paramLabel = "<text>", description = "Location postal code.")
    String locPostalCode;

    @Option(names = "--loc-timezone", paramLabel = "<IANA-zone>", description = "Location IANA timezone.")
    String locTimezone;

    /**
     * @param pagination the sequential page-walk orchestrator of {@code --all-pages}
     * @param cancellations the process registry that lets an interrupt cancel a pacing wait
     *     of the page walk
     */
    public WebSearchCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            GoggleOptions goggleOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            WebSearchExchange exchange,
            WebSearchPresenter presenter,
            WebPagedSearchPresenter pagedPresenter,
            PaginationService<WebSearchResult> pagination,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                commonOptions,
                storedCredentials,
                loopbackTestToken,
                presenter,
                pagedPresenter,
                pagination,
                cancellations,
                results,
                diagnosticsFactory);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.goggleOptions = Objects.requireNonNull(goggleOptions, "goggleOptions");
    }

    @Override
    protected void reportInvocationDiagnostics(OutputRequest output, DiagnosticsSink diagnostics) {
        goggleOptions.reportLoadedFiles(commandName(), output, diagnostics);
    }

    @Override
    protected String commandName() {
        return NAME;
    }

    @Override
    protected int minDocumentedPage() {
        return WebSearchRequest.MIN_PAGE;
    }

    @Override
    protected int maxDocumentedPage() {
        return WebSearchRequest.MAX_PAGE;
    }

    @Override
    protected WebSearchRequest withPage(WebSearchRequest request, int page) {
        return request.withPage(page);
    }

    @Override
    protected void validateEndpointOptions() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept(
                "--country",
                "--search-lang",
                "--ui-lang",
                "--safe-search",
                "--freshness",
                "--count",
                "--page",
                "--spellcheck");
    }

    @Override
    protected Outcome<WebSearchResult> exchange(WebSearchRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new WebSearchDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }

    @Override
    protected WebSearchRequest requestOf() {
        WebSearchRequest.Builder builder = WebSearchRequest.builder(query);
        commonOptions.applyTo(builder);
        if (textDecorations != null) {
            builder.textDecorations(textDecorations);
        }
        if (resultFilters != null) {
            builder.resultFilters(resultFilters);
        }
        if (units != null) {
            builder.units(units);
        }
        if (extraSnippets != null) {
            builder.extraSnippets(extraSnippets);
        }
        if (includeFetchMetadata != null) {
            builder.includeFetchMetadata(includeFetchMetadata);
        }
        if (operators != null) {
            builder.operators(operators);
        }
        if (enableRichCallback != null) {
            builder.enableRichCallback(enableRichCallback);
        }
        builder.goggles(goggleOptions.compileGoggles());
        if (anyLocationSupplied()) {
            builder.location(new WebSearchRequest.Location(
                    locLat, locLong, locCity, locState, locStateName, locCountry, locPostalCode, locTimezone));
        }
        return builder.build();
    }

    private boolean anyLocationSupplied() {
        return locLat != null
                || locLong != null
                || locCity != null
                || locState != null
                || locStateName != null
                || locCountry != null
                || locPostalCode != null
                || locTimezone != null;
    }
}
