package io.amscotti.bravesearch.adapter.cli.command.news;

import io.amscotti.bravesearch.adapter.cli.command.PagedRemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.GoggleOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.NewsPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.NewsSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.NewsSearchDispatch;
import io.amscotti.bravesearch.application.port.out.NewsSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.request.NewsSearchRequest;
import io.amscotti.bravesearch.domain.result.NewsSearchResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The news search command: one query, one exchange or one sequential page walk, one
 * rendering. The run policy — the single-request spine and the whole {@code --all-pages}
 * walk — lives once in {@link PagedRemoteCommandSupport}; this command is the news options,
 * the request assembly, and the exchange call.
 *
 * <p>The endpoint documents the shared search options minus none of the current inventory —
 * every spelling of {@link CommonSearchOptions} rides this command — plus the news-only
 * {@code --extra-snippets}, {@code --include-fetch-metadata}, and {@code --operators}, and
 * Goggles. The complement guard still runs, so a spelling added to the shared mixin later is
 * refused here until the news endpoint documents it. Web-only spellings (decorations,
 * filters, units, the rich callback, the location group) are unknown options for this
 * command, and the endpoint documents no location headers and no timezone, so none exist to
 * reject. The news walk has no upstream continuation field: {@code --all-pages} walks the
 * user-facing pages sequentially through page 10 unless bounded by {@code --max-pages}, and
 * a short page is never read as exhaustion.
 */
@Command(name = NewsSearchCommand.NAME, description = "Search news with one query and print the results.")
public final class NewsSearchCommand extends PagedRemoteCommandSupport<NewsSearchRequest, NewsSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "news";

    private final NewsSearchExchange exchange;

    /** The news-only goggle input options; news is one of the endpoints that document Goggles. */
    @Mixin
    protected GoggleOptions goggleOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "One search query; quote it and use -- when it starts like an option.")
    String query;

    @Option(names = "--extra-snippets", negatable = true, description = "Request additional result snippets.")
    Boolean extraSnippets;

    @Option(names = "--include-fetch-metadata", negatable = true, description = "Include page fetch metadata.")
    Boolean includeFetchMetadata;

    @Option(names = "--operators", negatable = true, description = "Disable query operators; on by default upstream.")
    Boolean operators;

    /**
     * @param pagination the sequential page-walk orchestrator of {@code --all-pages}
     * @param cancellations the process registry that lets an interrupt cancel a pacing wait
     *     of the page walk
     */
    public NewsSearchCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            GoggleOptions goggleOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            NewsSearchExchange exchange,
            NewsSearchPresenter presenter,
            NewsPagedSearchPresenter pagedPresenter,
            PaginationService<NewsSearchResult> pagination,
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
        return NewsSearchRequest.MIN_PAGE;
    }

    @Override
    protected int maxDocumentedPage() {
        return NewsSearchRequest.MAX_PAGE;
    }

    @Override
    protected NewsSearchRequest withPage(NewsSearchRequest request, int page) {
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
    protected Outcome<NewsSearchResult> exchange(NewsSearchRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new NewsSearchDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }

    @Override
    protected NewsSearchRequest requestOf() {
        NewsSearchRequest.Builder builder = NewsSearchRequest.builder(query);
        commonOptions.applyTo(builder);
        if (extraSnippets != null) {
            builder.extraSnippets(extraSnippets);
        }
        if (includeFetchMetadata != null) {
            builder.includeFetchMetadata(includeFetchMetadata);
        }
        if (operators != null) {
            builder.operators(operators);
        }
        builder.goggles(goggleOptions.compileGoggles());
        return builder.build();
    }
}
