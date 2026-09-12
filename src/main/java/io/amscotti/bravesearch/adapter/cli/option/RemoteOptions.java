package io.amscotti.bravesearch.adapter.cli.option;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.InvalidOriginException;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The remote-only options every remote subcommand mixes in after its command token:
 * {@code -o/--output}, {@code --pretty}, {@code --timeout}, {@code --connect-timeout},
 * {@code --api-version}, and the hidden {@code --base-url}. The root grammar holds none of
 * them, so an occurrence before the subcommand token is an unknown option, and every value
 * is validated here at parse time — durations positive, the version pin a real calendar
 * date, the base URL a literal loopback origin — so no invalid value survives into dispatch.
 *
 * <p>The instance is constructed and injected by a composition root with the name-resolution
 * seam; the process default binds the platform resolver for the {@code localhost} spelling.
 */
public final class RemoteOptions {

    /** The documented default non-streaming total budget. */
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    /** The documented default connection budget. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final LocalhostResolver localhostResolver;

    @Spec
    CommandSpec spec;

    private boolean outputFlagPresent;

    private OutputMode outputMode = OutputMode.HUMAN;

    private boolean pretty;

    private Duration totalTimeout;

    private Duration connectTimeout;

    private LocalDate apiVersion;

    private BraveApiOrigin baseUrlOverride;

    public RemoteOptions(LocalhostResolver localhostResolver) {
        this.localhostResolver = Objects.requireNonNull(localhostResolver, "localhostResolver");
    }

    /** Whether {@code --output} appeared, distinct from the mode it selected. */
    public boolean outputFlagPresent() {
        return outputFlagPresent;
    }

    /** The selected output mode; the human channel unless {@code --output} said otherwise. */
    public OutputMode outputMode() {
        return outputMode;
    }

    /** Whether {@code --pretty} was requested. */
    public boolean pretty() {
        return pretty;
    }

    /** The non-streaming total budget; the documented default unless {@code --timeout} overrode it. */
    public Duration totalTimeout() {
        return totalTimeout == null ? DEFAULT_TOTAL_TIMEOUT : totalTimeout;
    }

    /** The connection budget; the documented default unless {@code --connect-timeout} overrode it. */
    public Duration connectTimeout() {
        return connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
    }

    /** The exact pin spelling for the {@code Api-Version} header, or null when unpinned. */
    public String apiVersionPin() {
        return apiVersion == null ? null : apiVersion.toString();
    }

    /** The origin this invocation travels to; production unless a loopback override parsed. */
    public BraveApiOrigin origin() {
        return baseUrlOverride == null ? BraveApiOrigin.production() : baseUrlOverride;
    }

    @Option(
            names = {"-o", "--output"},
            converter = OutputModeConverter.class,
            description = "Output channel: human, json, jsonl, or raw.")
    void setOutput(OutputMode outputMode) {
        this.outputFlagPresent = true;
        this.outputMode = outputMode;
    }

    @Option(names = "--pretty", description = "Pretty-print JSON; invalid with jsonl and raw.")
    void setPretty(boolean pretty) {
        this.pretty = pretty;
    }

    @Option(
            names = "--timeout",
            converter = SimpleDurationConverter.class,
            description = "Total non-stream request timeout, for example 30s; default 30s.")
    void setTotalTimeout(Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
    }

    @Option(
            names = "--connect-timeout",
            converter = SimpleDurationConverter.class,
            description = "Client connection timeout, for example 10s; default 10s.")
    void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @Option(
            names = "--api-version",
            converter = ApiVersionConverter.class,
            description = "Pin the Api-Version header to a real calendar date, YYYY-MM-DD.")
    void setApiVersion(LocalDate apiVersion) {
        this.apiVersion = apiVersion;
    }

    @Option(names = "--base-url", hidden = true, description = "Hidden test override; literal loopback origins only.")
    void setBaseUrl(String rawBaseUrl) {
        try {
            this.baseUrlOverride = BraveApiOrigin.fromOverride(rawBaseUrl, localhostResolver);
        } catch (InvalidOriginException refused) {
            throw new ParameterException(spec.commandLine(), refused.getMessage());
        }
    }
}
