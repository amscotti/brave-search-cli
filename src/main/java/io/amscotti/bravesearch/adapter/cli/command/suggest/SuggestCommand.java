package io.amscotti.bravesearch.adapter.cli.command.suggest;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SuggestPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.SuggestDispatch;
import io.amscotti.bravesearch.application.port.out.SuggestExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.SuggestRequest;
import io.amscotti.bravesearch.domain.result.SuggestSearchResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The suggest command: one partial query, one single GET exchange, one rendering — the
 * endpoint documents no pagination, so the whole page-walk family lives elsewhere. The
 * run policy — output compatibility, usage rejections, the single credential preflight
 * with its origin routing, and the presenter handoff — lives once in {@link
 * RemoteCommandSupport}; this command is the suggest options, the request assembly, and
 * the exchange call.
 *
 * <p>The suggest endpoint speaks a different language wire name than the search
 * verticals: its language option travels as {@code lang}, never {@code search_lang}.
 * The CLI keeps one spelling across every command, so the shared mixin's {@code
 * --search-lang} is this endpoint's accepted alias and binds onto the request's own
 * {@code lang} member — no {@code --lang} spelling exists anywhere in this CLI. The
 * endpoint documents {@code --country}, the alias, the suggest-only {@code --count}
 * (1..20, Brave's default of 5 staying an upstream decision through omission), and the
 * negatable {@code --rich} pair, so the mixin's complement guard refuses {@code
 * --ui-lang}, {@code --safe-search}, {@code --freshness}, {@code --page}, {@code
 * --spellcheck}, and any spelling added to the mixin later until the suggest endpoint
 * documents it. The goggle family and the page-walk flags are unknown options of this
 * command.
 */
@Command(name = SuggestCommand.NAME, description = "Suggest query completions for one partial query.")
public final class SuggestCommand extends RemoteCommandSupport<SuggestRequest, SuggestSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "suggest";

    private final SuggestExchange exchange;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "PARTIAL-QUERY",
            description = "The partial query to complete; quote it and use -- when it starts like an option.")
    String query;

    @Option(
            names = "--rich",
            negatable = true,
            description = "Ask for rich suggestions; requires a paid autosuggest subscription upstream.")
    Boolean rich;

    /**
     * @param commonOptions the shared search-option grammar this command narrows to the
     *     suggest subset through the complement guard
     */
    public SuggestCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            SuggestExchange exchange,
            SuggestPresenter presenter,
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
    protected SuggestRequest buildRequest() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept("--country", "--search-lang", "--count");
        SuggestRequest.Builder builder = SuggestRequest.builder(query);
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (commonOptions.searchLang() != null) {
            builder.lang(commonOptions.searchLang());
        }
        if (commonOptions.count() != null) {
            builder.count(commonOptions.count());
        }
        if (rich != null) {
            builder.rich(rich);
        }
        return builder.build();
    }

    @Override
    protected Outcome<SuggestSearchResult> exchange(SuggestRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new SuggestDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}
