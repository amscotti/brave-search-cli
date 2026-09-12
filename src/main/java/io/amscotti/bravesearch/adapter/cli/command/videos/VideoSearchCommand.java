package io.amscotti.bravesearch.adapter.cli.command.videos;

import io.amscotti.bravesearch.adapter.cli.command.PagedRemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoPagedSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.VideoSearchPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.VideoSearchDispatch;
import io.amscotti.bravesearch.application.port.out.VideoSearchExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.request.VideoSearchRequest;
import io.amscotti.bravesearch.domain.result.VideoSearchResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The videos search command: one query, one exchange or one sequential page walk, one
 * rendering. The run policy — the single-request spine and the whole {@code --all-pages}
 * walk — lives once in {@link PagedRemoteCommandSupport}; this command is the videos
 * options, the request assembly, and the exchange call.
 *
 * <p>The endpoint documents the shared search spellings {@code --country}, {@code
 * --search-lang}, {@code --ui-lang}, {@code --safe-search}, {@code --freshness}, {@code
 * --count}, {@code --page}, and {@code --spellcheck}, plus the videos-only {@code
 * --include-fetch-metadata} and {@code --operators}. The complement guard still runs, so
 * a spelling added to the shared mixin later is refused here until the videos endpoint
 * documents it. The endpoint documents no Goggles — no goggle mixin exists here, so every
 * goggle spelling is an unknown option — and no web-only spelling (decorations, filters,
 * units, the rich callback, extra snippets, the location group) is known to this command.
 * The videos walk has no upstream continuation field: {@code --all-pages} walks the
 * user-facing pages sequentially through page 10 unless bounded by {@code --max-pages},
 * and a short page is never read as exhaustion.
 */
@Command(name = VideoSearchCommand.NAME, description = "Search videos with one query and print the results.")
public final class VideoSearchCommand extends PagedRemoteCommandSupport<VideoSearchRequest, VideoSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "videos";

    private final VideoSearchExchange exchange;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "One search query; quote it and use -- when it starts like an option.")
    String query;

    @Option(names = "--include-fetch-metadata", negatable = true, description = "Include page fetch metadata.")
    Boolean includeFetchMetadata;

    @Option(names = "--operators", negatable = true, description = "Disable query operators; on by default upstream.")
    Boolean operators;

    /**
     * @param pagination the sequential page-walk orchestrator of {@code --all-pages}
     * @param cancellations the process registry that lets an interrupt cancel a pacing wait
     *     of the page walk
     */
    public VideoSearchCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            VideoSearchExchange exchange,
            VideoSearchPresenter presenter,
            VideoPagedSearchPresenter pagedPresenter,
            PaginationService<VideoSearchResult> pagination,
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
    }

    @Override
    protected String commandName() {
        return NAME;
    }

    @Override
    protected int minDocumentedPage() {
        return VideoSearchRequest.MIN_PAGE;
    }

    @Override
    protected int maxDocumentedPage() {
        return VideoSearchRequest.MAX_PAGE;
    }

    @Override
    protected VideoSearchRequest withPage(VideoSearchRequest request, int page) {
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
    protected Outcome<VideoSearchResult> exchange(VideoSearchRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new VideoSearchDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }

    @Override
    protected VideoSearchRequest requestOf() {
        VideoSearchRequest.Builder builder = VideoSearchRequest.builder(query);
        commonOptions.applyTo(builder);
        if (includeFetchMetadata != null) {
            builder.includeFetchMetadata(includeFetchMetadata);
        }
        if (operators != null) {
            builder.operators(operators);
        }
        return builder.build();
    }
}
