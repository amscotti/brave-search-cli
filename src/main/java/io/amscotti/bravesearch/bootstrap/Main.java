package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.bravehttp.endpoint.StreamProbeGateway;
import io.amscotti.bravesearch.adapter.cli.command.StreamProbeCommand;
import io.amscotti.bravesearch.adapter.cli.command.answers.AnswersCommand;
import io.amscotti.bravesearch.adapter.cli.command.completion.CompletionCommand;
import io.amscotti.bravesearch.adapter.cli.command.config.ConfigCommand;
import io.amscotti.bravesearch.adapter.cli.command.config.RepairPermissionsCommand;
import io.amscotti.bravesearch.adapter.cli.command.config.SetKeyCommand;
import io.amscotti.bravesearch.adapter.cli.command.config.ShowCommand;
import io.amscotti.bravesearch.adapter.cli.command.config.UnsetKeyCommand;
import io.amscotti.bravesearch.adapter.cli.command.context.ContextCommand;
import io.amscotti.bravesearch.adapter.cli.command.images.ImageSearchCommand;
import io.amscotti.bravesearch.adapter.cli.command.news.NewsSearchCommand;
import io.amscotti.bravesearch.adapter.cli.command.places.PlacesCommand;
import io.amscotti.bravesearch.adapter.cli.command.places.PlacesDescribeCommand;
import io.amscotti.bravesearch.adapter.cli.command.places.PlacesDetailsCommand;
import io.amscotti.bravesearch.adapter.cli.command.places.PlacesSearchCommand;
import io.amscotti.bravesearch.adapter.cli.command.rich.RichCommand;
import io.amscotti.bravesearch.adapter.cli.command.spellcheck.SpellcheckCommand;
import io.amscotti.bravesearch.adapter.cli.command.suggest.SuggestCommand;
import io.amscotti.bravesearch.adapter.cli.command.videos.VideoSearchCommand;
import io.amscotti.bravesearch.adapter.cli.command.web.WebSearchCommand;
import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.CommonSearchOptions;
import io.amscotti.bravesearch.adapter.cli.option.GlobalOptions;
import io.amscotti.bravesearch.adapter.cli.option.GoggleOptions;
import io.amscotti.bravesearch.adapter.cli.option.RemoteOptions;
import io.amscotti.bravesearch.adapter.cli.option.StrictOptionParsing;
import io.amscotti.bravesearch.adapter.cli.presentation.OutputStreamResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.TerminalDetector;
import io.amscotti.bravesearch.adapter.cli.presentation.WriterDiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.json.ConfigShowRecordCodec;
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonMappers;
import io.amscotti.bravesearch.adapter.config.GoggleFileLoader;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.error.FailureKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import sun.misc.Signal;

/**
 * Composition root and picocli entry point of the brave-search CLI.
 *
 * <p>The {@link #execute(String[])} method returns a process exit code without terminating the
 * JVM, so behavior is testable in-process. Terminal exit handling is owned by the process
 * bootstrap, not by this command.
 */
@Command(
        name = Main.COMMAND_NAME,
        description = "Brave Search command line client for humans and autonomous agents.",
        versionProvider = Main.VersionProvider.class,
        subcommands = {Main.VersionCommand.class})
public final class Main implements Runnable {

    /** Canonical command name; also the first token of the version line. */
    static final String COMMAND_NAME = "brave-search";

    private final ExchangeRegistry exchanges;

    private final ConfigComposition config;

    private final WebSearchComposition webSearch;

    private final ContextComposition contextSearch;

    private final NewsSearchComposition newsSearch;

    private final VideoSearchComposition videosSearch;

    private final ImageSearchComposition imagesSearch;

    private final SuggestComposition suggestSearch;

    private final SpellcheckComposition spellcheckSearch;

    private final PlacesComposition placesSearch;

    private final PlacesEnrichmentComposition placesEnrichment;

    private final RichComposition richCallbacks;

    private final AnswersComposition answersSearch;

    @Mixin
    GlobalOptions globals;

    @Spec CommandSpec spec;

    public Main() {
        this(new ExchangeRegistry());
    }

    public Main(ExchangeRegistry exchanges) {
        this(exchanges, ConfigComposition.process());
    }

    public Main(ExchangeRegistry exchanges, ConfigComposition config) {
        this(exchanges, config, WebSearchComposition.process(config.credentials()));
    }

    public Main(ExchangeRegistry exchanges, ConfigComposition config, WebSearchComposition webSearch) {
        this(exchanges, config, webSearch, ContextComposition.process(config.credentials()));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch) {
        this(exchanges, config, webSearch, contextSearch, NewsSearchComposition.process(config.credentials()));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch,
            NewsSearchComposition newsSearch) {
        this(exchanges, config, webSearch, contextSearch, newsSearch, VideoSearchComposition.process(config.credentials()));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch,
            NewsSearchComposition newsSearch,
            VideoSearchComposition videosSearch) {
        this(
                exchanges,
                config,
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                ImageSearchComposition.process(config.credentials()));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch,
            NewsSearchComposition newsSearch,
            VideoSearchComposition videosSearch,
            ImageSearchComposition imagesSearch) {
        this(
                exchanges,
                config,
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
            SuggestComposition.process(config.credentials()),
            SpellcheckComposition.process(config.credentials()),
            PlacesComposition.process(config.credentials()),
            PlacesEnrichmentComposition.process(config.credentials()),
            RichComposition.process(config.credentials()));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch,
            NewsSearchComposition newsSearch,
            VideoSearchComposition videosSearch,
            ImageSearchComposition imagesSearch,
            SuggestComposition suggestSearch,
            SpellcheckComposition spellcheckSearch,
            PlacesComposition placesSearch,
            PlacesEnrichmentComposition placesEnrichment,
            RichComposition richCallbacks) {
        this(
                exchanges,
                config,
                webSearch,
                contextSearch,
                newsSearch,
                videosSearch,
                imagesSearch,
                suggestSearch,
                spellcheckSearch,
                placesSearch,
                placesEnrichment,
                richCallbacks,
                AnswersComposition.process(config.credentials(), exchanges));
    }

    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            WebSearchComposition webSearch,
            ContextComposition contextSearch,
            NewsSearchComposition newsSearch,
            VideoSearchComposition videosSearch,
            ImageSearchComposition imagesSearch,
            SuggestComposition suggestSearch,
            SpellcheckComposition spellcheckSearch,
            PlacesComposition placesSearch,
            PlacesEnrichmentComposition placesEnrichment,
            RichComposition richCallbacks,
            AnswersComposition answersSearch) {
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.config = Objects.requireNonNull(config, "config");
        this.webSearch = Objects.requireNonNull(webSearch, "webSearch");
        this.contextSearch = Objects.requireNonNull(contextSearch, "contextSearch");
        this.newsSearch = Objects.requireNonNull(newsSearch, "newsSearch");
        this.videosSearch = Objects.requireNonNull(videosSearch, "videosSearch");
        this.imagesSearch = Objects.requireNonNull(imagesSearch, "imagesSearch");
        this.suggestSearch = Objects.requireNonNull(suggestSearch, "suggestSearch");
        this.spellcheckSearch = Objects.requireNonNull(spellcheckSearch, "spellcheckSearch");
        this.placesSearch = Objects.requireNonNull(placesSearch, "placesSearch");
        this.placesEnrichment = Objects.requireNonNull(placesEnrichment, "placesEnrichment");
        this.richCallbacks = Objects.requireNonNull(richCallbacks, "richCallbacks");
        this.answersSearch = Objects.requireNonNull(answersSearch, "answersSearch");
        // the single color and width decisions of the process: the real console detection
        // combines with --no-color inside the globals, and the exported COLUMNS observation
        // rides the same detector, so presenters never re-derive either
        this.globals = new GlobalOptions(
                new TerminalDetector(TerminalDetector::processColorCapable, TerminalDetector::processWidth));
    }

    /** Prints usage help to the command's output stream when the root command runs bare. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Runs the command line and returns the process exit code without exiting the JVM. */
    public int execute(String... args) {
        CommandLine commandLine = assemble(
                StdoutWriters.passthroughBytes(System.out),
                new OutputStreamResultWriter(StdoutWriters.byteStdout()));
        // an execution failure inside a command never becomes picocli's stack trace and
        // software status: this handler owns the redacted line and its exit code. Picocli
        // converts the handler's verdict into the exit code and never rethrows past it, so
        // the mappings the guarded entry promises are decided here for a failure that
        // escapes a running command: a configuration failure keeps the
        // local-configuration status, everything else the internal one
        commandLine.setExecutionExceptionHandler((failure, cmd, parse) -> escapedFailureStatus(failure));
        return commandLine.execute(args);
    }

    /**
     * The fully registered command line over the given help stdout writer, with every
     * result-writing command bound to the process's own byte channel: the form the process
     * entry point runs.
     */
    CommandLine assemble(java.io.PrintWriter stdout) {
        return assemble(stdout, new OutputStreamResultWriter(StdoutWriters.byteStdout()));
    }

    /**
     * The fully registered command line over the given writers: every subcommand and
     * parser setting the process runs with, assembled once so in-process consumers observe
     * exactly the registered grammar. The stdout writer carries help and version text,
     * where nobody classifies write failures, while the result documents of the completion
     * and config commands travel through the result writer, whose write failures carry the
     * operating system's own identity so a downstream broken pipe stays classifiable.
     */
    CommandLine assemble(java.io.PrintWriter stdout, ResultWriter results) {
        CommandLine commandLine = new CommandLine(this);
        commandLine.setOut(stdout);
        commandLine.addSubcommand(
                StreamProbeCommand.NAME,
                new StreamProbeCommand(new StreamProbeGateway(), exchanges, WriterDiagnosticsSink::new, Clock.systemUTC()));
        commandLine.addSubcommand(ConfigCommand.NAME, configCommandGroup(results));
        commandLine.addSubcommand(WebSearchCommand.NAME, webCommand());
        commandLine.addSubcommand(ContextCommand.NAME, contextCommand());
        commandLine.addSubcommand(NewsSearchCommand.NAME, newsCommand());
        commandLine.addSubcommand(VideoSearchCommand.NAME, videosCommand());
        commandLine.addSubcommand(ImageSearchCommand.NAME, imagesCommand());
        commandLine.addSubcommand(SuggestCommand.NAME, suggestCommand());
        commandLine.addSubcommand(SpellcheckCommand.NAME, spellcheckCommand());
        commandLine.addSubcommand(PlacesCommand.NAME, placesCommandGroup());
        commandLine.addSubcommand(RichCommand.NAME, richCommand());
        commandLine.addSubcommand(AnswersCommand.NAME, answersCommand());
        commandLine.addSubcommand(CompletionCommand.NAME, new CommandLine(new CompletionCommand(results)));
        // subcommands keep their own writers, so the byte-lossless stdout must reach every
        // registered command, however deeply nested
        propagateStdout(commandLine, stdout);
        // strict parsing lands after registration so every subcommand's parser is covered
        return StrictOptionParsing.apply(commandLine);
    }

    private CommandLine contextCommand() {
        ContextCommand command = new ContextCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                contextSearch.loopbackTestToken(),
                contextSearch.exchange(),
                contextSearch.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine contextCommandLine = new CommandLine(command);
        contextCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return contextCommandLine;
    }

    private CommandLine webCommand() {
        WebSearchCommand command = new WebSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                new GoggleOptions(new GoggleFileLoader()),
                config.credentials(),
                webSearch.loopbackTestToken(),
                webSearch.exchange(),
                webSearch.presenter(),
                webSearch.pagedPresenter(),
                webSearch.pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine webCommandLine = new CommandLine(command);
        webCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return webCommandLine;
    }

    private CommandLine newsCommand() {
        NewsSearchCommand command = new NewsSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                new GoggleOptions(new GoggleFileLoader()),
                config.credentials(),
                newsSearch.loopbackTestToken(),
                newsSearch.exchange(),
                newsSearch.presenter(),
                newsSearch.pagedPresenter(),
                newsSearch.pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine newsCommandLine = new CommandLine(command);
        newsCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return newsCommandLine;
    }

    private CommandLine videosCommand() {
        VideoSearchCommand command = new VideoSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                videosSearch.loopbackTestToken(),
                videosSearch.exchange(),
                videosSearch.presenter(),
                videosSearch.pagedPresenter(),
                videosSearch.pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine videosCommandLine = new CommandLine(command);
        videosCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return videosCommandLine;
    }

    private CommandLine imagesCommand() {
        ImageSearchCommand command = new ImageSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                imagesSearch.loopbackTestToken(),
                imagesSearch.exchange(),
                imagesSearch.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine imagesCommandLine = new CommandLine(command);
        imagesCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return imagesCommandLine;
    }

    private CommandLine suggestCommand() {
        SuggestCommand command = new SuggestCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                suggestSearch.loopbackTestToken(),
                suggestSearch.exchange(),
                suggestSearch.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine suggestCommandLine = new CommandLine(command);
        suggestCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return suggestCommandLine;
    }

    private CommandLine spellcheckCommand() {
        SpellcheckCommand command = new SpellcheckCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                spellcheckSearch.loopbackTestToken(),
                spellcheckSearch.exchange(),
                spellcheckSearch.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine spellcheckCommandLine = new CommandLine(command);
        spellcheckCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return spellcheckCommandLine;
    }

    private CommandLine placesCommandGroup() {
        CommandLine group = new CommandLine(new PlacesCommand());
        CommandLine searchCommandLine = new CommandLine(new PlacesSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                placesSearch.loopbackTestToken(),
                placesSearch.exchange(),
                placesSearch.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new));
        searchCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        group.addSubcommand(PlacesSearchCommand.NAME, searchCommandLine);
        group.addSubcommand(PlacesDetailsCommand.NAME, detailsCommand());
        group.addSubcommand(PlacesDescribeCommand.NAME, describeCommand());
        return group;
    }

    private CommandLine detailsCommand() {
        PlacesDetailsCommand command = new PlacesDetailsCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                config.credentials(),
                placesEnrichment.loopbackTestToken(),
                placesEnrichment.exchange(),
                placesEnrichment.detailsPresenter(),
                placesEnrichment.pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine commandLine = new CommandLine(command);
        commandLine.getCommandSpec().versionProvider(new VersionProvider());
        return commandLine;
    }

    private CommandLine describeCommand() {
        PlacesDescribeCommand command = new PlacesDescribeCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                config.credentials(),
                placesEnrichment.loopbackTestToken(),
                placesEnrichment.exchange(),
                placesEnrichment.describePresenter(),
                placesEnrichment.pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine commandLine = new CommandLine(command);
        commandLine.getCommandSpec().versionProvider(new VersionProvider());
        return commandLine;
    }

    private CommandLine richCommand() {
        RichCommand command = new RichCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                config.credentials(),
                richCallbacks.loopbackTestToken(),
                richCallbacks.exchange(),
                richCallbacks.presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine richCommandLine = new CommandLine(command);
        richCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return richCommandLine;
    }

    private CommandLine answersCommand() {
        AnswersCommand command = new AnswersCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                answersSearch.loopbackTestToken(),
                answersSearch.exchange(),
                answersSearch.streams(),
                exchanges,
                answersSearch.presenter(),
                answersSearch.streamingPresenter(),
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine answersCommandLine = new CommandLine(command);
        answersCommandLine.getCommandSpec().versionProvider(new VersionProvider());
        return answersCommandLine;
    }

    private CommandLine configCommandGroup(ResultWriter results) {
        CommandLine group = new CommandLine(new ConfigCommand());
        group.addSubcommand(
                SetKeyCommand.NAME,
                new SetKeyCommand(
                        config.secrets(), config.store(), config.configPath(), results, WriterDiagnosticsSink::new));
        group.addSubcommand(
                UnsetKeyCommand.NAME,
                new UnsetKeyCommand(config.store(), config.configPath(), results, WriterDiagnosticsSink::new));
        group.addSubcommand(
                ShowCommand.NAME,
                new ShowCommand(
                        config.credentials(),
                        config.store(),
                        new ConfigShowRecordCodec(new JsonMappers()),
                        config.configPath(),
                        results,
                        WriterDiagnosticsSink::new));
        group.addSubcommand(
                RepairPermissionsCommand.NAME,
                new RepairPermissionsCommand(config.store(), results, WriterDiagnosticsSink::new));
        return group;
    }

    private static void propagateStdout(CommandLine commandLine, PrintWriter stdout) {
        commandLine.setOut(stdout);
        commandLine.getSubcommands().values().forEach(subcommand -> propagateStdout(subcommand, stdout));
    }

    /**
     * JVM entry point; the process bootstrap owns terminal exit handling. The SIGINT and
     * SIGTERM handlers are installed before the command line runs so a signal during a run
     * latches its cause on every live exchange while normal control flow still decides the
     * exit code, and the first cause latched wins, so an interrupt followed by a termination
     * keeps the interrupt's status. A signal that arrives while no exchange is live has no
     * latch to arm, so its handler terminates the process itself with that signal's
     * conventional status — the same 130 or 143 a latched run exits by — and no user signal
     * is ever absorbed. Platforms that reserve a signal for their own shutdown machinery
     * refuse the registration and still terminate with that signal's conventional status on
     * their own.
     */
    public static void main(String[] args) {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        try {
            Signal.handle(new Signal("INT"), signal -> {
                if (!exchanges.interruptLiveExchanges()) {
                    System.exit(ExitCodeMapper.SIGINT);
                }
            });
        } catch (IllegalArgumentException reservedByPlatform) {
            // the platform's own interrupt handling decides the status without our latch
        }
        try {
            Signal.handle(new Signal("TERM"), signal -> {
                if (!exchanges.terminateLiveExchanges()) {
                    System.exit(ExitCodeMapper.SIGTERM);
                }
            });
        } catch (IllegalArgumentException reservedByPlatform) {
            // the platform's own termination handling decides the status without our latch
        }
        System.exit(runGuarded(() -> new Main(exchanges), args));
    }

    /**
     * The guarded process entry: the compositions are built inside the safety net the
     * exit-code contract promises, so a composition or wiring defect during construction —
     * or any other failure outside the command execution itself — ends as the documented
     * internal status with one redacted line, never a raw stack trace with the virtual
     * machine's own default status. A configuration failure ends as the documented
     * local-configuration status with one redacted diagnostic line. Failures inside a
     * running command are converted by the execution handler installed in {@link
     * #execute(String...)}; both share the same mappings.
     */
    static int runGuarded(Supplier<Main> composition, String... args) {
        try {
            return composition.get().runRoot(args);
        } catch (LocalConfigException | CredentialResolutionException misconfigured) {
            return localConfigurationStatus(misconfigured);
        } catch (Throwable internal) {
            return internalFailureStatus(internal);
        }
    }

    /**
     * The guarded entry of this already-built composition: the command line runs under the
     * safety net the exit-code contract promises, with the mappings of {@link
     * #runGuarded(Supplier, String...)}.
     */
    public int runGuarded(String... args) {
        return runGuarded(() -> this, args);
    }

    /**
     * The internal-status form of one unexpected failure: a single redacted stderr line
     * carrying nothing but the exception's own simple name — no stack trace, no internal
     * class names, no message text that could quote user input or credential material.
     */
    private static int internalFailureStatus(Throwable failure) {
        Throwable reported = reported(failure);
        System.err.println(COMMAND_NAME + ": unexpected internal error (" + reported.getClass().getSimpleName() + ")");
        return ExitCodeMapper.forKind(FailureKind.INTERNAL);
    }

    /**
     * The status of one failure that escaped a running command: picocli converts an
     * execution handler's verdict into the process exit code and never rethrows past the
     * handler, so the same mapping the guarded entry promises decides here — a
     * configuration failure keeps the local-configuration status with its redacted message,
     * everything else ends as the internal status.
     */
    private static int escapedFailureStatus(Throwable failure) {
        Throwable reported = reported(failure);
        if (reported instanceof LocalConfigException misconfigured) {
            return localConfigurationStatus(misconfigured);
        }
        if (reported instanceof CredentialResolutionException unresolved) {
            return localConfigurationStatus(unresolved);
        }
        return internalFailureStatus(failure);
    }

    /**
     * The local-configuration status of one escaped configuration failure: its already
     * redacted message as the single stderr line and the documented configuration exit
     * code.
     */
    private static int localConfigurationStatus(Exception misconfigured) {
        System.err.println(COMMAND_NAME + ": " + misconfigured.getMessage());
        return ExitCodeMapper.forKind(FailureKind.LOCAL_CONFIG);
    }

    /** The failure to report: picocli wraps a command's own failure; the cause is the failure itself. */
    private static Throwable reported(Throwable failure) {
        return failure instanceof CommandLine.ExecutionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause()
                : failure;
    }

    /**
     * The command line as the bootstrap safety net sees it: credential resolution failures
     * are declared even though every command converts them, so a command that ever lets one
     * escape keeps the documented configuration status — decided by the execution handler
     * of {@link #execute(String...)} for a failure inside a running command, and by the
     * guarded entry for anything outside one.
     */
    private int runRoot(String[] args) throws CredentialResolutionException {
        return execute(args);
    }

    /** Explicit alias of {@code --version} whose stdout is byte-identical to the flag form. */
    @Command(name = "version", description = "Print version information to stdout and exit.")
    static final class VersionCommand implements Callable<Integer> {

        @Spec CommandSpec spec;

        @Override
        public Integer call() {
            CommandLine root = spec.root().commandLine();
            root.printVersionHelp(root.getOut());
            if (root.getOut().checkError()) {
                // a writer that already lost its reader reports itself through checkError
                // only; the failed write keeps the transport status with one fixed line
                root.getErr().println(COMMAND_NAME + ": writing the version line failed");
                return ExitCodeMapper.forKind(FailureKind.TRANSPORT);
            }
            return 0;
        }
    }

    /**
     * Supplies the version line from the {@code brave-search-version.properties} resource that
     * the build generates from the single project version, so no source file repeats it.
     */
    static final class VersionProvider implements IVersionProvider {

        private static final String RESOURCE = "/brave-search-version.properties";

        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream resource = Main.class.getResourceAsStream(RESOURCE)) {
                if (resource == null) {
                    throw new IOException("version resource " + RESOURCE + " is missing");
                }
                properties.load(resource);
            }
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IOException("version resource " + RESOURCE + " has no usable version");
            }
            return new String[] {COMMAND_NAME + " " + version};
        }
    }
}
