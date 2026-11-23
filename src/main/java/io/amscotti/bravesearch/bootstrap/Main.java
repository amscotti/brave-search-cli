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
import io.amscotti.bravesearch.adapter.cli.presentation.json.JsonlCodec;
import io.amscotti.bravesearch.adapter.config.DebugTrace;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
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
import java.util.function.Function;
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
        // the footer names the output modes but not their flag: the flag belongs to each remote
        // command's grammar, and the root help must never look as if it accepted it here
        footer = "Run 'brave-search <command> --help' for a command's options. Scripts and agents:"
                + " give each remote command an explicit json or jsonl output mode, and decide on"
                + " the exit code before reading the document.",
        versionProvider = Main.VersionProvider.class,
        subcommands = {Main.VersionCommand.class})
public final class Main implements Runnable {

    /** Canonical command name; also the first token of the version line. */
    static final String COMMAND_NAME = "brave-search";

    private final ExchangeRegistry exchanges;

    private final ConfigComposition config;

    private final Compositions compositions;

    /**
     * The config group's machine codec, built on first use: only an invocation that assembles
     * the command line pays for it, and the process wiring builds it over the same mapper set
     * every remote composition encodes through.
     */
    private final Supplier<ConfigShowRecordCodec> configShowCodec;

    /** Whether unexpected internal errors also print a trace of frames and exception types. */
    private final boolean traceInternalErrors;

    @Mixin
    GlobalOptions globals;

    @Spec CommandSpec spec;

    /** The process composition: every adapter is the process one. */
    public Main() {
        this(new ExchangeRegistry());
    }

    /** The process composition over the given registry, which receives every live exchange. */
    public Main(ExchangeRegistry exchanges) {
        this(exchanges, ConfigComposition.process());
    }

    /** The process wiring of every remote command over the given configuration. */
    public Main(ExchangeRegistry exchanges, ConfigComposition config) {
        // one mapper set serves the whole process: every composition and the config group
        this(exchanges, config, new JsonMappers());
    }

    private Main(ExchangeRegistry exchanges, ConfigComposition config, JsonMappers mappers) {
        this(
                exchanges,
                config,
                Compositions.process(
                        config.credentials(),
                        EnvironmentCredentialSource.processEnvironmentLookup(),
                        exchanges,
                        mappers,
                        new JsonlCodec(mappers)),
                () -> new ConfigShowRecordCodec(mappers),
                DebugTrace.requested(EnvironmentCredentialSource.processEnvironmentLookup()));
    }

    /**
     * The fully injected composition. The wiring record is always whole — a test replaces the
     * one composition it exercises through {@link Compositions} — so no partial constructor
     * chain exists to keep in step with the endpoint inventory.
     */
    public Main(ExchangeRegistry exchanges, ConfigComposition config, Compositions compositions) {
        this(exchanges, config, compositions, EnvironmentCredentialSource.processEnvironmentLookup());
    }

    /**
     * The fully injected composition over the given environment lookup, through which the
     * internal-error trace opt-in is read.
     */
    public Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            Compositions compositions,
            Function<String, String> environmentLookup) {
        this(
                exchanges,
                config,
                compositions,
                () -> new ConfigShowRecordCodec(new JsonMappers()),
                DebugTrace.requested(environmentLookup));
    }

    private Main(
            ExchangeRegistry exchanges,
            ConfigComposition config,
            Compositions compositions,
            Supplier<ConfigShowRecordCodec> configShowCodec,
            boolean traceInternalErrors) {
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.config = Objects.requireNonNull(config, "config");
        this.compositions = Objects.requireNonNull(compositions, "compositions");
        this.configShowCodec = memoized(configShowCodec);
        this.traceInternalErrors = traceInternalErrors;
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
        // every node shares the root's two process-wide wires: the byte-lossless stdout, so
        // help text reaches every command however deeply nested, and the version provider,
        // because the version flag is inherited by the whole tree and a node without the
        // provider would accept the flag and print nothing
        propagateWiring(commandLine, stdout, new VersionProvider());
        // strict parsing lands after registration so every subcommand's parser is covered
        return StrictOptionParsing.apply(commandLine);
    }

    private CommandLine contextCommand() {
        ContextCommand command = new ContextCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.contextSearch().loopbackTestToken(),
                compositions.contextSearch().exchange(),
                compositions.contextSearch().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine contextCommandLine = new CommandLine(command);
        return contextCommandLine;
    }

    private CommandLine webCommand() {
        WebSearchCommand command = new WebSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                new GoggleOptions(new GoggleFileLoader()),
                config.credentials(),
                compositions.webSearch().loopbackTestToken(),
                compositions.webSearch().exchange(),
                compositions.webSearch().presenter(),
                compositions.webSearch().pagedPresenter(),
                compositions.webSearch().pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine webCommandLine = new CommandLine(command);
        return webCommandLine;
    }

    private CommandLine newsCommand() {
        NewsSearchCommand command = new NewsSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                new GoggleOptions(new GoggleFileLoader()),
                config.credentials(),
                compositions.newsSearch().loopbackTestToken(),
                compositions.newsSearch().exchange(),
                compositions.newsSearch().presenter(),
                compositions.newsSearch().pagedPresenter(),
                compositions.newsSearch().pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine newsCommandLine = new CommandLine(command);
        return newsCommandLine;
    }

    private CommandLine videosCommand() {
        VideoSearchCommand command = new VideoSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.videosSearch().loopbackTestToken(),
                compositions.videosSearch().exchange(),
                compositions.videosSearch().presenter(),
                compositions.videosSearch().pagedPresenter(),
                compositions.videosSearch().pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine videosCommandLine = new CommandLine(command);
        return videosCommandLine;
    }

    private CommandLine imagesCommand() {
        ImageSearchCommand command = new ImageSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.imagesSearch().loopbackTestToken(),
                compositions.imagesSearch().exchange(),
                compositions.imagesSearch().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine imagesCommandLine = new CommandLine(command);
        return imagesCommandLine;
    }

    private CommandLine suggestCommand() {
        SuggestCommand command = new SuggestCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.suggestSearch().loopbackTestToken(),
                compositions.suggestSearch().exchange(),
                compositions.suggestSearch().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine suggestCommandLine = new CommandLine(command);
        return suggestCommandLine;
    }

    private CommandLine spellcheckCommand() {
        SpellcheckCommand command = new SpellcheckCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.spellcheckSearch().loopbackTestToken(),
                compositions.spellcheckSearch().exchange(),
                compositions.spellcheckSearch().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine spellcheckCommandLine = new CommandLine(command);
        return spellcheckCommandLine;
    }

    private CommandLine placesCommandGroup() {
        CommandLine group = new CommandLine(new PlacesCommand());
        CommandLine searchCommandLine = new CommandLine(new PlacesSearchCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.placesSearch().loopbackTestToken(),
                compositions.placesSearch().exchange(),
                compositions.placesSearch().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new));
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
                compositions.placesEnrichment().loopbackTestToken(),
                compositions.placesEnrichment().exchange(),
                compositions.placesEnrichment().detailsPresenter(),
                compositions.placesEnrichment().pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine commandLine = new CommandLine(command);
        return commandLine;
    }

    private CommandLine describeCommand() {
        PlacesDescribeCommand command = new PlacesDescribeCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                config.credentials(),
                compositions.placesEnrichment().loopbackTestToken(),
                compositions.placesEnrichment().exchange(),
                compositions.placesEnrichment().describePresenter(),
                compositions.placesEnrichment().pagination(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine commandLine = new CommandLine(command);
        return commandLine;
    }

    private CommandLine richCommand() {
        RichCommand command = new RichCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                config.credentials(),
                compositions.richCallbacks().loopbackTestToken(),
                compositions.richCallbacks().exchange(),
                compositions.richCallbacks().presenter(),
                exchanges,
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine richCommandLine = new CommandLine(command);
        return richCommandLine;
    }

    private CommandLine answersCommand() {
        AnswersCommand command = new AnswersCommand(
                globals,
                new RemoteOptions(LocalhostResolver.platform()),
                new CommonSearchOptions(),
                config.credentials(),
                compositions.answersSearch().loopbackTestToken(),
                compositions.answersSearch().exchange(),
                compositions.answersSearch().streams(),
                exchanges,
                compositions.answersSearch().presenter(),
                compositions.answersSearch().streamingPresenter(),
                new OutputStreamResultWriter(StdoutWriters.byteStdout()),
                WriterDiagnosticsSink::new);
        CommandLine answersCommandLine = new CommandLine(command);
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
                        configShowCodec.get(),
                        config.configPath(),
                        results,
                        WriterDiagnosticsSink::new));
        group.addSubcommand(
                RepairPermissionsCommand.NAME,
                new RepairPermissionsCommand(config.store(), results, WriterDiagnosticsSink::new));
        return group;
    }

    private static void propagateWiring(CommandLine commandLine, PrintWriter stdout, IVersionProvider versions) {
        commandLine.setOut(stdout);
        commandLine.getCommandSpec().versionProvider(versions);
        commandLine.getSubcommands().values().forEach(subcommand -> propagateWiring(subcommand, stdout, versions));
    }

    /**
     * JVM entry point; the process bootstrap owns terminal exit handling. The SIGINT and
     * SIGTERM handlers are installed before the command line runs so a signal during a run
     * latches its cause on every live exchange while normal control flow still decides the
     * exit code, and the first cause latched wins, so an interrupt followed by a termination
     * keeps the interrupt's status. A signal arriving while no exchange is live has nothing
     * to latch, so its handler terminates the process itself with that signal's conventional
     * status — the same 130 or 143 a latched run exits by — and no user signal is ever
     * absorbed. A signal repeated while the run it latched is still winding down terminates
     * the process at once the same way: the user has asked twice. Platforms that reserve a
     * signal for their own shutdown machinery refuse the registration and still terminate
     * with that signal's conventional status on their own. {@link UserSignals} owns the
     * decision of each delivery.
     */
    public static void main(String[] args) {
        ExchangeRegistry exchanges = new ExchangeRegistry();
        UserSignals signals = new UserSignals(exchanges);
        try {
            Signal.handle(new Signal("INT"), signal -> {
                if (signals.interrupt()) {
                    System.exit(ExitCodeMapper.SIGINT);
                }
            });
        } catch (IllegalArgumentException reservedByPlatform) {
            // the platform's own interrupt handling decides the status without our latch
        }
        try {
            Signal.handle(new Signal("TERM"), signal -> {
                if (signals.terminate()) {
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
        Main built = null;
        try {
            built = composition.get();
            return built.runRoot(args);
        } catch (LocalConfigException | CredentialResolutionException misconfigured) {
            return localConfigurationStatus(misconfigured);
        } catch (Throwable internal) {
            if (built == null) {
                // a composition that never finished building has no opt-in of its own
                System.err.println(COMMAND_NAME + ": unexpected internal error (" + reported(internal)
                        .getClass()
                        .getSimpleName() + ")");
                return ExitCodeMapper.forKind(FailureKind.INTERNAL);
            }
            return built.internalFailureStatus(internal);
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
     * carrying nothing but the exception's own simple name — no message text that could quote
     * user input or credential material. A process that opted in through
     * {@link DebugTrace#VARIABLE} additionally receives a trace of frames and exception types.
     */
    private int internalFailureStatus(Throwable failure) {
        Throwable reported = reported(failure);
        System.err.println(COMMAND_NAME + ": unexpected internal error (" + reported.getClass().getSimpleName() + ")");
        if (traceInternalErrors) {
            printTrace(reported);
        }
        return ExitCodeMapper.forKind(FailureKind.INTERNAL);
    }

    /**
     * The opt-in trace of an internal failure: the frames of the failure and of every cause
     * in its chain, with the exception types named — and nothing else. No message appears
     * anywhere in it, because a message can quote user input or credential material; the
     * frames already say where the failure happened, which is what a bug report needs.
     */
    private static void printTrace(Throwable failure) {
        System.err.println(
                COMMAND_NAME + ": debug trace, exception messages withheld: " + failure.getClass().getSimpleName());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause != failure) {
                System.err.println(COMMAND_NAME + ": caused by: " + cause.getClass().getSimpleName());
            }
            for (StackTraceElement frame : cause.getStackTrace()) {
                System.err.println("    at " + frame);
            }
        }
    }

    /**
     * The status of one failure that escaped a running command: picocli converts an
     * execution handler's verdict into the process exit code and never rethrows past the
     * handler, so the same mapping the guarded entry promises decides here — a
     * configuration failure keeps the local-configuration status with its redacted message,
     * everything else ends as the internal status.
     */
    private int escapedFailureStatus(Throwable failure) {
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

    /** A supplier that resolves once and answers the resolved value on every later call. */
    private static <T> Supplier<T> memoized(Supplier<T> resolution) {
        return new Supplier<>() {

            private T resolved;

            private boolean resolvedYet;

            @Override
            public T get() {
                if (!resolvedYet) {
                    resolved = resolution.get();
                    resolvedYet = true;
                }
                return resolved;
            }
        };
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
