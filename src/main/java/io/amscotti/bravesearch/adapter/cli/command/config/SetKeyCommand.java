package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.adapter.cli.option.OutputModeConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.domain.config.Credential;
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
 * {@code config set-key}: stores one API key read from the no-echo console, or — only when
 * {@code --stdin} is explicitly given — from exactly one LF/CRLF-terminated UTF-8 line.
 *
 * <p>The key is stored only after it was read and accepted, so an interrupted, truncated, or
 * rejected input leaves the prior configuration untouched. The success line names the stored
 * location and never key material.
 */
@Command(
        name = SetKeyCommand.NAME,
        description = "Store the API key read from a no-echo console, or from one --stdin line.")
public final class SetKeyCommand extends ConfigSubcommand implements Callable<Integer> {

    /** Registration name under the config group. */
    public static final String NAME = "set-key";

    private final SecretSource secrets;

    private final ConfigStore configStore;

    private final Supplier<Path> configPath;

    @Option(
            names = "--stdin",
            description = "Read exactly one LF/CRLF-terminated UTF-8 line from stdin instead of the no-echo console.")
    private boolean stdin;

    @Option(
            names = "--output",
            converter = OutputModeConverter.class,
            hidden = true,
            paramLabel = "MODE",
            description = "Not valid here: config commands print fixed human text.")
    private OutputMode output;

    @Option(names = "--pretty", hidden = true, description = "Not valid here: config commands print fixed human text.")
    private boolean pretty;

    public SetKeyCommand(
            SecretSource secrets,
            ConfigStore configStore,
            Supplier<Path> configPath,
            ResultWriter results,
            Function<Writer, DiagnosticsSink> diagnosticsFactory) {
        super(results, diagnosticsFactory);
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
    }

    @Override
    public Integer call() {
        rejectRemoteOutputFlags(output, pretty);
        try {
            Credential secret = secrets.read(stdin);
            configStore.store(secret);
            return resultLine("api key stored in " + configPath.get());
        } catch (LocalConfigException failure) {
            return failLocally(failure);
        }
    }

    @Override
    protected String qualifiedName() {
        return "config " + NAME;
    }
}
