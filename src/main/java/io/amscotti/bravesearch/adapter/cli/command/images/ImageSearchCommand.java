package io.amscotti.bravesearch.adapter.cli.command.images;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ImageSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ImageSearchDispatch;
import io.amscotti.bravesearch.application.port.out.ImageSearchExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.ImageSearchRequest;
import io.amscotti.bravesearch.domain.result.ImageSearchResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * The images search command: one query, one single GET exchange, one rendering — the
 * endpoint documents no pagination, so the whole page-walk family lives elsewhere. The
 * run policy — output compatibility, usage rejections, the single credential preflight
 * with its origin routing, and the presenter handoff — lives once in {@link
 * RemoteCommandSupport}; this command is the images options, the request assembly, and
 * the exchange call.
 *
 * <p>The endpoint documents only a subset of the shared search grammar — {@code
 * --country}, {@code --search-lang}, {@code --safe-search}, {@code --count}, and {@code
 * --spellcheck} — so the mixin's complement guard refuses {@code --ui-lang}, {@code
 * --freshness}, {@code --page}, and any spelling added to the mixin later until the
 * images endpoint documents it. The SafeSearch level narrows to the images pair: {@code
 * moderate} is not a documented images level and fails as a usage error. No images-only
 * options exist; the goggle family, the page-walk flags, and every web-only spelling are
 * unknown options of this command.
 */
@Command(name = ImageSearchCommand.NAME, description = "Search images with one query and print the results.")
public final class ImageSearchCommand extends RemoteCommandSupport<ImageSearchRequest, ImageSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "images";

    private final ImageSearchExchange exchange;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "One search query; quote it and use -- when it starts like an option.")
    String query;

    /**
     * @param commonOptions the shared search-option grammar this command narrows to the
     *     images subset through the complement guard
     */
    public ImageSearchCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            ImageSearchExchange exchange,
            ImageSearchPresenter presenter,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                storedCredentials,
                loopbackTestToken,
                presenter,
                cancellations,
                results,
                diagnosticsFactory);
        this.commonOptions = Objects.requireNonNull(commonOptions, "commonOptions");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    @Override
    protected String commandName() {
        return NAME;
    }

    @Override
    protected CommandOutputProfile outputProfile() {
        // one single non-paginated request: raw stays valid
        return CommandOutputProfile.REMOTE;
    }

    @Override
    protected ImageSearchRequest buildRequest() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept("--country", "--search-lang", "--safe-search", "--count", "--spellcheck");
        ImageSearchRequest.Builder builder = ImageSearchRequest.builder(query);
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (commonOptions.searchLang() != null) {
            builder.searchLang(commonOptions.searchLang());
        }
        if (commonOptions.safeSearch() != null) {
            builder.safeSearch(commonOptions.safeSearch());
        }
        if (commonOptions.count() != null) {
            builder.count(commonOptions.count());
        }
        if (commonOptions.spellcheck() != null) {
            builder.spellcheck(commonOptions.spellcheck());
        }
        return builder.build();
    }

    @Override
    protected Outcome<ImageSearchResult> exchange(ImageSearchRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new ImageSearchDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}
