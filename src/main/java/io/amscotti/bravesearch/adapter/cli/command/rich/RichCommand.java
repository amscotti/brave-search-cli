package io.amscotti.bravesearch.adapter.cli.command.rich;

import io.amscotti.bravesearch.adapter.cli.command.RemoteCommandSupport;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.RichPresenter;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.RichDispatch;
import io.amscotti.bravesearch.application.port.out.RichExchange;
import io.amscotti.bravesearch.application.stream.CancellationContext;
import io.amscotti.bravesearch.application.stream.CancellationRegistry;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.request.RichRequest;
import io.amscotti.bravesearch.domain.result.RichResult;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * The rich command: one opaque callback key, one single GET exchange, one rendering. The
 * key references the rich results of an earlier web search that enabled rich callbacks —
 * it is a reference, not a query and not a credential — so it travels verbatim under the
 * endpoint's own {@code callback_key} wire name and {@code --} hands over keys that
 * start like an option. The endpoint documents no search options, so this command
 * carries no search-option mixin: every shared search spelling, the goggle family, and
 * the page-walk flags are unknown options here. The run policy — output compatibility,
 * usage rejections, the single credential preflight with its origin routing, and the
 * presenter handoff — lives once in {@link RemoteCommandSupport}.
 */
@Command(name = RichCommand.NAME, description = "Fetch the current rich results of one web search callback key.")
public final class RichCommand extends RemoteCommandSupport<RichRequest, RichResult> {

    /** Registration name under the root command. */
    public static final String NAME = "rich";

    private final RichExchange exchange;

    @Parameters(
            index = "0",
            arity = "1..1",
            paramLabel = "CALLBACK-KEY",
            description = "The callback key an earlier web search issued; quote it and use -- when it starts like an option.")
    String callbackKey;

    /**
     * @param exchange the parsed-invocation exchange that performs the single GET lookup
     * @param presenter the renderer of the command's exchange outcomes
     */
    public RichCommand(
            GlobalOptions globals,
            RemoteOptions remoteOptions,
            CredentialProvider storedCredentials,
            CredentialProvider loopbackTestToken,
            RichExchange exchange,
            RichPresenter presenter,
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
    protected RichRequest buildRequest() {
        return new RichRequest(callbackKey);
    }

    @Override
    protected Outcome<RichResult> exchange(RichRequest request, Credential credential, CancellationContext cancellation) {
        return this.exchange.dispatch(new RichDispatch(
                request,
                remoteOptions.origin(),
                credential,
                remoteOptions.totalTimeout(),
                remoteOptions.connectTimeout(),
                remoteOptions.apiVersionPin(),
                cancellation));
    }
}
