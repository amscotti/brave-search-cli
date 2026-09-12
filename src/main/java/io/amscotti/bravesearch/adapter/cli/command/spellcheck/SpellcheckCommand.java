package io.amscotti.bravesearch.adapter.cli.command.spellcheck;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.SpellcheckPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.SpellcheckDispatch;
import io.amscotti.bravesearch.application.port.out.SpellcheckExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.SpellcheckRequest;
import io.amscotti.bravesearch.domain.result.SpellcheckSearchResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * The spellcheck command: one query, one single GET exchange, one rendering — the
 * endpoint documents no pagination, so the whole page-walk family lives elsewhere. The
 * run policy — output compatibility, usage rejections, the single credential preflight
 * with its origin routing, and the presenter handoff — lives once in {@link
 * RemoteCommandSupport}; this command is the spellcheck options, the request assembly,
 * and the exchange call.
 *
 * <p>The spellcheck endpoint speaks a different language wire name than the search
 * verticals and documents the smallest inventory of any endpoint: the query, {@code
 * --country}, and its language option traveling as {@code lang}, never {@code
 * search_lang}. The CLI keeps one spelling across every command, so the shared mixin's
 * {@code --search-lang} is this endpoint's accepted alias and binds onto the request's
 * own {@code lang} member — no {@code --lang} spelling exists anywhere in this CLI. The
 * complement guard refuses every other shared spelling — {@code --count} included — and
 * the goggle family, the page-walk flags, and the suggest-only {@code --rich} are
 * unknown options of this command.
 */
@Command(name = SpellcheckCommand.NAME, description = "Spell-check one query and print the corrections.")
public final class SpellcheckCommand extends RemoteCommandSupport<SpellcheckRequest, SpellcheckSearchResult> {

    /** Registration name under the root command. */
    public static final String NAME = "spellcheck";

    private final SpellcheckExchange exchange;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "The query to spell-check; quote it and use -- when it starts like an option.")
    String query;

    /**
     * @param commonOptions the shared search-option grammar this command narrows to the
     *     spellcheck subset through the complement guard
     */
    public SpellcheckCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            SpellcheckExchange exchange,
            SpellcheckPresenter presenter,
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
    protected SpellcheckRequest buildRequest() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept("--country", "--search-lang");
        SpellcheckRequest.Builder builder = SpellcheckRequest.builder(query);
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (commonOptions.searchLang() != null) {
            builder.lang(commonOptions.searchLang());
        }
        return builder.build();
    }

    @Override
    protected Outcome<SpellcheckSearchResult> exchange(SpellcheckRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new SpellcheckDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}
