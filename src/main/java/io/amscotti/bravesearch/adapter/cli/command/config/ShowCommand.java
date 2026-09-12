package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.OutputModeConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.adapter.cli.presentation.json.ConfigShowRecordCodec;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.ConfigFileSight;
import io.amscotti.bravesearch.domain.config.ConfigShowView;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.output.CommandOutputProfile;
import io.amscotti.bravesearch.domain.output.OutputMode;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code config show}: prints the effective credential's source, the config-file path and
 * state, and which lower-precedence sources are shadowed — never the credential or any
 * reusable fingerprint of it.
 *
 * <p>Everything rendered is derived into a {@link ConfigShowView} first, so the fixed human
 * text and the machine record share one redaction-safe projection. The human channel stays
 * the default; {@code --output json} — the one machine mode this command accepts; jsonl and
 * raw are usage errors — writes exactly one LF-terminated, schema-versioned record through
 * {@link ConfigShowRecordCodec}, and {@code --pretty} stays a usage error like every other
 * config spelling.
 */
@Command(name = ShowCommand.NAME, description = "Print the effective credential source and config-file state.")
public final class ShowCommand extends ConfigSubcommand implements Callable<Integer> {

    /** Registration name under the config group. */
    public static final String NAME = "show";

    private final CredentialProvider credentials;

    private final ConfigStore configStore;

    private final ConfigShowRecordCodec machineRecords;

    private final Supplier<Path> configPath;

    @Option(
            names = "--output",
            converter = OutputModeConverter.class,
            paramLabel = "MODE",
            description = "Output channel: human, or one json machine record.")
    private OutputMode output;

    public ShowCommand(
            CredentialProvider credentials,
            ConfigStore configStore,
            ConfigShowRecordCodec machineRecords,
            Supplier<Path> configPath,
            ResultWriter results,
            Function<Writer, DiagnosticsSink> diagnosticsFactory) {
        super(results, diagnosticsFactory);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.machineRecords = Objects.requireNonNull(machineRecords, "machineRecords");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
    }

    @Override
    public Integer call() {
        OutputMode mode = output == null ? OutputMode.HUMAN : output;
        rejectIncompatibleOutput(
                CommandOutputProfile.CONFIG_SHOW,
                new OutputRequest(output != null, mode, false, false, false));
        try {
            ConfigShowView view = view();
            if (mode == OutputMode.JSON) {
                return machineDocument(machineRecords.encode(view));
            }
            return render(view);
        } catch (CredentialResolutionException failure) {
            if (failure.category() == CredentialResolutionException.Category.INVALID) {
                diagnostics().emit("config show: " + failure.getMessage());
                return 3;
            }
            ConfigShowView missing = ConfigShowView.missing(configPath.get(), sightTheFile());
            if (mode == OutputMode.JSON) {
                return machineDocument(machineRecords.encode(missing));
            }
            return render(missing);
        } catch (LocalConfigException failure) {
            return failLocally(failure);
        }
    }

    private ConfigShowView view() throws CredentialResolutionException {
        ResolvedCredential resolution = credentials.resolveWithProvenance();
        ConfigFileSight fileSight = sightTheFile();
        boolean fromEnvironment = !ConfigShowView.FILE_SOURCE.equals(resolution.sourceName());
        return new ConfigShowView(
                resolution.sourceName(),
                configPath.get(),
                fileSight,
                resolution.aliasShadowed(),
                fromEnvironment && fileSight == ConfigFileSight.KEY_STORED);
    }

    /**
     * How the config file presents, degraded honestly through the provenance-only seam: a
     * present-but-invalid key still counts as stored, a file that cannot be read safely is
     * reported as exactly that, and no credential is ever materialized to describe it.
     */
    private ConfigFileSight sightTheFile() {
        try {
            return configStore.peekSummary();
        } catch (LocalConfigException unreadable) {
            return ConfigFileSight.UNREADABLE;
        }
    }

    private int render(ConfigShowView view) {
        int exit = resultLine("credential source: " + view.sourceName());
        if (exit == ExitCodeMapper.SUCCESS) {
            exit = resultLine("config file: " + view.configPath() + " (" + humanPhrase(view.fileSight()) + ")");
        }
        if (exit == ExitCodeMapper.SUCCESS && view.aliasShadowed()) {
            exit = resultLine("shadowed: BRAVE_SEARCH_API_KEY is set but overridden");
        }
        if (exit == ExitCodeMapper.SUCCESS && view.fileShadowed()) {
            exit = resultLine("shadowed: the config file api key is overridden");
        }
        return exit;
    }

    private static String humanPhrase(ConfigFileSight sight) {
        return switch (sight) {
            case ABSENT -> "absent";
            case KEY_STORED -> "api key stored";
            case NO_KEY_STORED -> "no api key stored";
            case UNREADABLE -> "present, not safely readable";
        };
    }

    @Override
    protected String qualifiedName() {
        return "config " + NAME;
    }
}
