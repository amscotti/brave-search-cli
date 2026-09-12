package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.adapter.cli.option.OutputModeConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.domain.config.ConfigState;
import io.amscotti.bravesearch.domain.config.InvalidCredentialException;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code config unset-key}: removes the stored API key. A key that is absent is a successful
 * no-op, and a stored-but-invalid key is removed exactly like a valid one — an unreadable value
 * is the problem unsetting remedies, not a reason to refuse.
 */
@Command(name = UnsetKeyCommand.NAME, description = "Remove the stored API key from the config file.")
public final class UnsetKeyCommand extends ConfigSubcommand implements Callable<Integer> {

    /** Registration name under the config group. */
    public static final String NAME = "unset-key";

    private final ConfigStore configStore;

    private final Supplier<Path> configPath;

    @Option(
            names = "--output",
            converter = OutputModeConverter.class,
            hidden = true,
            paramLabel = "MODE",
            description = "Not valid here: config commands print fixed human text.")
    private OutputMode output;

    @Option(names = "--pretty", hidden = true, description = "Not valid here: config commands print fixed human text.")
    private boolean pretty;

    public UnsetKeyCommand(
            ConfigStore configStore,
            Supplier<Path> configPath,
            ResultWriter results,
            Function<Writer, DiagnosticsSink> diagnosticsFactory) {
        super(results, diagnosticsFactory);
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
    }

    @Override
    public Integer call() {
        rejectRemoteOutputFlags(output, pretty);
        try {
            if (storedKeyPresent()) {
                configStore.clear();
                return resultLine("api key removed from " + configPath.get());
            }
            return resultLine("no api key stored at " + configPath.get() + "; nothing to remove");
        } catch (LocalConfigException failure) {
            return failLocally(failure);
        }
    }

    private boolean storedKeyPresent() {
        try {
            ConfigState state = configStore.load();
            return state.credential().isPresent();
        } catch (InvalidCredentialException storedButInvalid) {
            return true;
        }
    }

    @Override
    protected String qualifiedName() {
        return "config " + NAME;
    }
}
