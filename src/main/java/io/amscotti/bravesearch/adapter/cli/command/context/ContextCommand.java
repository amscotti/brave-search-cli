package io.amscotti.bravesearch.adapter.cli.command.context;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.ContextThresholdCandidates;
import io.amscotti.bravesearch.adapter.cli.option.ContextThresholdConverter;
import io.amscotti.bravesearch.adapter.cli.option.FiniteDoubleConverter;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.LocalRecallCandidates;
import io.amscotti.bravesearch.adapter.cli.option.LocalRecallConverter;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.ContextPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ContextDispatch;
import io.amscotti.bravesearch.application.port.out.ContextExchange;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.ContextRequest;
import io.amscotti.bravesearch.domain.request.ContextThreshold;
import io.amscotti.bravesearch.domain.request.LocalRecall;
import io.amscotti.bravesearch.domain.result.ContextResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The LLM context command: one query, one single non-paginated exchange, one rendering.
 * The run policy — output compatibility, usage rejections, the single credential preflight
 * with its origin routing, and the presenter handoff — lives once in {@link
 * RemoteCommandSupport}; this command is the context options, the request assembly, and the
 * exchange call.
 *
 * <p>The shared search options arrive through the {@link CommonSearchOptions} mixin; this
 * endpoint documents country, search language, SafeSearch, freshness, and its own wider
 * count range, and nothing else of the shared grammar — the page, interface-language, and
 * spellcheck spellings are rejected locally through the mixin's complement guard, and
 * every web-only option (pagination, Goggles, decorations, the timezone location header)
 * is an unknown spelling here. The context-only options below carry the documented Brave
 * defaults as omissions: an unsupplied budget is never coerced into an explicit wire
 * value, because Brave calibrates the defaults upstream.
 */
@Command(name = ContextCommand.NAME, description = "Retrieve LLM-ready search context for one query.")
public final class ContextCommand extends RemoteCommandSupport<ContextRequest, ContextResult> {

    /** Registration name under the root command. */
    public static final String NAME = "context";

    private final ContextExchange exchange;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "QUERY",
            description = "One search query; quote it and use -- when it starts like an option.")
    String query;

    @Option(
            names = "--max-urls",
            paramLabel = "<1..50>",
            description = "Per-request URL budget, 1 to 50; the default follows Brave.")
    Integer maxUrls;

    @Option(
            names = "--max-tokens",
            paramLabel = "<1024..32768>",
            description = "Per-request token budget, 1024 to 32768; the default follows Brave.")
    Integer maxTokens;

    @Option(
            names = "--max-snippets",
            paramLabel = "<1..256>",
            description = "Per-request snippet budget, 1 to 256; the default follows Brave.")
    Integer maxSnippets;

    @Option(
            names = "--max-tokens-per-url",
            paramLabel = "<512..8192>",
            description = "Per-url token budget, 512 to 8192; the default follows Brave.")
    Integer maxTokensPerUrl;

    @Option(
            names = "--max-snippets-per-url",
            paramLabel = "<1..100>",
            description = "Per-url snippet budget, 1 to 100; the default follows Brave.")
    Integer maxSnippetsPerUrl;

    @Option(
            names = "--threshold",
            converter = ContextThresholdConverter.class,
            completionCandidates = ContextThresholdCandidates.class,
            paramLabel = "<mode>",
            description = "Context threshold mode: strict, balanced, lenient, or disabled.")
    ContextThreshold threshold;

    @Option(
            names = "--source-metadata",
            negatable = true,
            description = "Attach source metadata to the sources map; off by default upstream.")
    Boolean sourceMetadata;

    @Option(
            names = "--local",
            converter = LocalRecallConverter.class,
            completionCandidates = LocalRecallCandidates.class,
            paramLabel = "<auto|on|off>",
            description = "Local recall: auto defers to Brave, on and off pin it.")
    LocalRecall local;

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

    public ContextCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            ContextExchange exchange,
            ContextPresenter presenter,
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
    protected ContextRequest buildRequest() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept("--country", "--search-lang", "--safe-search", "--freshness", "--count");
        return requestOf();
    }

    @Override
    protected Outcome<ContextResult> exchange(ContextRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new ContextDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }

    private ContextRequest requestOf() {
        ContextRequest.Builder builder = ContextRequest.builder(query);
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (commonOptions.searchLang() != null) {
            builder.searchLang(commonOptions.searchLang());
        }
        if (commonOptions.safeSearch() != null) {
            builder.safeSearch(commonOptions.safeSearch());
        }
        if (commonOptions.freshness() != null) {
            builder.freshness(commonOptions.freshness());
        }
        if (commonOptions.count() != null) {
            builder.count(commonOptions.count());
        }
        if (maxUrls != null) {
            builder.maxUrls(maxUrls);
        }
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        if (maxSnippets != null) {
            builder.maxSnippets(maxSnippets);
        }
        if (maxTokensPerUrl != null) {
            builder.maxTokensPerUrl(maxTokensPerUrl);
        }
        if (maxSnippetsPerUrl != null) {
            builder.maxSnippetsPerUrl(maxSnippetsPerUrl);
        }
        if (threshold != null) {
            builder.threshold(threshold);
        }
        if (sourceMetadata != null) {
            builder.sourceMetadata(sourceMetadata);
        }
        if (local != null && local.enableLocal() != null) {
            builder.enableLocal(local.enableLocal());
        }
        if (anyLocationSupplied()) {
            builder.location(
                    new ContextRequest.Location(locLat, locLong, locCity, locState, locStateName, locCountry, locPostalCode));
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
                || locPostalCode != null;
    }
}
