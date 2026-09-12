package io.amscotti.bravesearch.adapter.cli.command.places;

import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.PlaceDescribePresenter;
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
 * The places describe command: one invocation of one through two hundred opaque POI ids
 * over {@code GET /local/descriptions}, fanned out into sequential chunk requests of at
 * most twenty ids each by the shared walk of {@link PlaceEnrichmentCommandSupport}. The
 * ids are the command's whole grammar — no search option applies to this endpoint
 * family, so every shared search spelling is an unknown option here — and they are
 * opaque and ephemeral, so nothing validates their internal format.
 */
@Command(
        name = PlacesDescribeCommand.NAME,
        description = "Fetch the AI-generated descriptions of places by their opaque ids.")
public final class PlacesDescribeCommand extends PlaceEnrichmentCommandSupport {

    /** Registration name under the places group. */
    public static final String NAME = "describe";

    @Parameters(
            index = "0..*",
            arity = "1..*",
            paramLabel = "ID",
            description = "One or more opaque place ids, up to " + PlaceEnrichmentRequest.MAX_IDS
                    + "; quote them and use -- when an id starts like an option.")
    List<String> ids;

    /**
     * @param presenter the description renderer of the walk's terminal state and
     *     per-chunk records
     */
    public PlacesDescribeCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            PlaceEnrichmentExchange exchange,
            PlaceDescribePresenter presenter,
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
        return PlaceDescribePresenter.COMMAND;
    }

    @Override
    protected PlaceEnrichmentRequest.Kind kind() {
        return PlaceEnrichmentRequest.Kind.DESCRIPTIONS;
    }

    @Override
    protected List<String> ids() {
        Objects.requireNonNull(ids, "ids");
        return ids;
    }
}
