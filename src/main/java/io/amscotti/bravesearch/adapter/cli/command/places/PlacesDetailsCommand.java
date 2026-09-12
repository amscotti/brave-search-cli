package io.amscotti.bravesearch.adapter.cli.command.places;

import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDetailsPresenter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.PlaceEnrichmentExchange;
import io.amscotti.bravesearch.application.service.PaginationService;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.request.PlaceEnrichmentRequest;
import io.amscotti.bravesearch.domain.result.PlaceEnrichmentResult;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * The places details command: one invocation of one through two hundred opaque POI ids
 * over {@code GET /local/pois}, fanned out into sequential chunk requests of at most
 * twenty ids each by the shared walk of {@link PlaceEnrichmentCommandSupport}. The ids
 * are the command's whole grammar — no search option applies to this endpoint family,
 * so every shared search spelling is an unknown option here — and they are opaque and
 * ephemeral, so nothing validates their internal format.
 *
 * <p>The run policy — output compatibility of the multi-request family ({@code raw} is
 * rejected before dispatch), the single credential preflight with its origin routing,
 * the chunk walk with its pacing and partial-failure rules, and the presenter handoff —
 * lives once in the support; this command is the endpoint kind, the ids parameter, and
 * the presenter binding.
 */
@Command(
        name = PlacesDetailsCommand.NAME,
        description = "Fetch photos, web mentions, and profiles of places by their opaque ids.")
public final class PlacesDetailsCommand extends PlaceEnrichmentCommandSupport {

    /** Registration name under the places group. */
    public static final String NAME = "details";

    @Parameters(
            index = "0..*",
            arity = "1..*",
            paramLabel = "ID",
            description = "One or more opaque place ids, up to " + PlaceEnrichmentRequest.MAX_IDS
                    + "; quote them and use -- when an id starts like an option.")
    List<String> ids;

    /**
     * @param presenter the detail renderer of the walk's terminal state and per-chunk
     *     records
     */
    public PlacesDetailsCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            PlaceEnrichmentExchange exchange,
            PlaceDetailsPresenter presenter,
            PaginationService<PlaceEnrichmentResult> pagination,
            CancellationRegistry cancellations,
            ResultWriter results,
            Function<java.io.Writer, io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink> diagnosticsFactory) {
        super(
                globals,
                remoteOptions,
                storedCredentials,
                loopbackTestToken,
                presenter,
                exchange,
                pagination,
                cancellations,
                results,
                diagnosticsFactory);
    }

    @Override
    protected String commandName() {
        return PlaceDetailsPresenter.COMMAND;
    }

    @Override
    protected PlaceEnrichmentRequest.Kind kind() {
        return PlaceEnrichmentRequest.Kind.DETAILS;
    }

    @Override
    protected List<String> ids() {
        Objects.requireNonNull(ids, "ids");
        return ids;
    }
}
