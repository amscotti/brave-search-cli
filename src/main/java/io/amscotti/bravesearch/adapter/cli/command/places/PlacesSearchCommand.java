package io.amscotti.bravesearch.adapter.cli.command.places;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.FiniteDecimalConverter;
import io.amscotti.bravesearch.adapter.cli.option.FiniteDoubleConverter;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.UnitsCandidates;
import io.amscotti.bravesearch.adapter.cli.option.UnitsConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.PlacesSearchPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.PlaceSearchDispatch;
import io.amscotti.bravesearch.application.port.out.PlaceSearchExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.error.UsageValidationError;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.GeoLoc;
import io.amscotti.bravesearch.domain.request.PlaceAnchor;
import io.amscotti.bravesearch.domain.request.PlaceSearchRequest;
import io.amscotti.bravesearch.domain.request.Units;
import io.amscotti.bravesearch.domain.result.PlaceSearchResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The places search command: one query-less-optional search, one single GET exchange,
 * one rendering — the endpoint documents no pagination, so the whole page-walk family
 * lives elsewhere. The run policy — output compatibility, usage rejections, the single
 * credential preflight with its origin routing, and the presenter handoff — lives once
 * in {@link RemoteCommandSupport}; this command is the places options, the request
 * assembly, and the exchange call.
 *
 * <p>The query is always optional: omitting it with an anchor is Explore mode, and
 * omitting query and anchor both is a valid broad global search that is never rejected
 * locally. The anchor is exactly one spelling — the paired {@code --latitude}/{@code
 * --longitude}, the place-name {@code --location}, or neither — and the place name
 * refuses control characters and edge whitespace at this option boundary, so nothing
 * adversarial reaches a location string. The {@code --geoloc} hint validates both
 * components and serializes exactly {@code latitudexlongitude}; the {@code --radius}
 * accepts plain finite decimals at zero or above — exponent spellings and digit
 * counts beyond the shared decimal bounds are usage errors — as a ranking bias, not
 * a hard boundary; the {@code --count} budgets the total across every response
 * bucket. The endpoint
 * documents the shared {@code --country}, {@code --search-lang}, {@code --ui-lang},
 * {@code --safe-search}, and {@code --spellcheck}, so the mixin's complement guard
 * refuses {@code --freshness} and {@code --page}; the goggle family, the page-walk
 * flags, and the web location header group are unknown options of this command.
 */
@Command(
        name = PlacesSearchCommand.NAME,
        description = "Search places with an optional query, an anchor, or neither of the two.")
public final class PlacesSearchCommand extends RemoteCommandSupport<PlaceSearchRequest, PlaceSearchResult> {

    /** Registration name under the places group. */
    public static final String NAME = "search";

    private final PlaceSearchExchange exchange;

    @Mixin
    CommonSearchOptions commonOptions;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "QUERY",
            description = "One optional search query; quote it and use -- when it starts like an option.")
    String query;

    @Option(
            names = "--latitude",
            paramLabel = "<decimal>",
            converter = FiniteDoubleConverter.class,
            description = "Anchor latitude; requires --longitude.")
    Double latitude;

    @Option(
            names = "--longitude",
            paramLabel = "<decimal>",
            converter = FiniteDoubleConverter.class,
            description = "Anchor longitude; requires --latitude.")
    Double longitude;

    @Option(
            names = "--location",
            paramLabel = "<place-name>",
            description = "Anchor place name; US 'city state country', others 'city country', without commas.")
    String location;

    @Option(
            names = "--radius",
            paramLabel = "<meters>",
            converter = FiniteDecimalConverter.class,
            description = "Ranking-bias radius in meters, zero or greater; not a hard boundary.")
    BigDecimal radius;

    @Option(
            names = "--geoloc",
            paramLabel = "<lat>x<long>",
            description = "Device-geolocation hint spelled latitudexlongitude; both components range-checked.")
    String geoloc;

    @Option(
            names = "--units",
            converter = UnitsConverter.class,
            completionCandidates = UnitsCandidates.class,
            description = "metric or imperial.")
    Units units;

    /**
     * @param commonOptions the shared search-option grammar this command narrows to the
     *     places subset through the complement guard
     */
    public PlacesSearchCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CommonSearchOptions commonOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            PlaceSearchExchange exchange,
            PlacesSearchPresenter presenter,
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
        return PlacesSearchPresenter.COMMAND;
    }

    @Override
    protected CommandOutputProfile outputProfile() {
        // one single non-paginated request: raw stays valid
        return CommandOutputProfile.REMOTE;
    }

    @Override
    protected PlaceSearchRequest buildRequest() throws UsageValidationError {
        commonOptions.rejectUndocumentedExcept("--country", "--search-lang", "--ui-lang", "--safe-search", "--count", "--spellcheck");
        PlaceSearchRequest.Builder builder = PlaceSearchRequest.builder();
        if (query != null) {
            builder.query(query);
        }
        builder.anchor(PlaceAnchor.of(latitude, longitude, location));
        if (radius != null) {
            builder.radius(radius);
        }
        if (geoloc != null) {
            builder.geoloc(GeoLoc.parse(geoloc));
        }
        if (units != null) {
            builder.units(units);
        }
        if (commonOptions.country() != null) {
            builder.country(commonOptions.country());
        }
        if (commonOptions.searchLang() != null) {
            builder.searchLang(commonOptions.searchLang());
        }
        if (commonOptions.uiLang() != null) {
            builder.uiLang(commonOptions.uiLang());
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
    protected Outcome<PlaceSearchResult> exchange(PlaceSearchRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new PlaceSearchDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}
