package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.adapter.cli.exit.ExitCodeMapper;
import io.amscotti.bravesearch.adapter.cli.option.OutputModeConverter;
import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import io.amscotti.bravesearch.domain.config.PermissionRepair;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code config repair-permissions}: tightens the credential file and its directory back to
 * the owner-only permission contract, reporting exactly the parts that were reset and never
 * loosening an already stricter file mode. When nothing changed, the confirmation speaks only
 * for POSIX modes — macOS ACLs stay uninspected by the mode checks — and a best-effort probe
 * that sees ACL entries beyond the owner adds an advisory line instead of a repair claim.
 * Structural violations — symbolic links, foreign ownership, extra hard links — are refusals
 * of the store itself, not something a mode reset could repair.
 */
@Command(
        name = RepairPermissionsCommand.NAME,
        description = "Reset the credential file and its directory to owner-only permissions.")
public final class RepairPermissionsCommand extends ConfigSubcommand implements Callable<Integer> {

    /** Registration name under the config group. */
    public static final String NAME = "repair-permissions";

    private final ConfigStore configStore;

    @Option(
            names = "--output",
            converter = OutputModeConverter.class,
            hidden = true,
            paramLabel = "MODE",
            description = "Not valid here: config commands print fixed human text.")
    private OutputMode output;

    @Option(names = "--pretty", hidden = true, description = "Not valid here: config commands print fixed human text.")
    private boolean pretty;

    public RepairPermissionsCommand(
            ConfigStore configStore,
            ResultWriter results,
            Function<Writer, DiagnosticsSink> diagnosticsFactory) {
        super(results, diagnosticsFactory);
        this.configStore = Objects.requireNonNull(configStore, "configStore");
    }

    @Override
    public Integer call() {
        rejectRemoteOutputFlags(output, pretty);
        try {
            PermissionRepair repair = configStore.repairPermissions();
            int exit = resultLine(repairLine(repair));
            if (exit == ExitCodeMapper.SUCCESS && repair.nonOwnerAclSuspected()) {
                exit = resultLine("advisory: macOS ACL entries beyond the owner are present on the credential file;"
                        + " review them with ls -le and remove non-owner grants");
            }
            return exit;
        } catch (LocalConfigException failure) {
            return failLocally(failure);
        }
    }

    private static String repairLine(PermissionRepair repair) {
        if (!repair.directoryModeChanged() && !repair.fileModeChanged()) {
            // POSIX modes are all this command manages: never claim the full owner-only
            // contract while ACLs stay uninspected
            return "POSIX modes already owner-only (macOS ACLs not inspected)";
        }
        List<String> reset = new ArrayList<>();
        if (repair.directoryModeChanged()) {
            reset.add("directory mode reset to 0700");
        }
        if (repair.fileModeChanged()) {
            reset.add("file mode reset to 0600");
        }
        return "permissions repaired: " + String.join(", ", reset);
    }

    @Override
    protected String qualifiedName() {
        return "config " + NAME;
    }
}
