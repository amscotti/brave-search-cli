package io.amscotti.bravesearch.adapter.cli.command.config;

import io.amscotti.bravesearch.domain.config.Credential;

/**
 * Reads one API-key secret for {@code config set-key} from the channel the invocation selected:
 * the no-echo console, or the exact one-line stdin contract when {@code --stdin} was given.
 *
 * <p>Functional seam so the command depends on no concrete input adapter: the composition root
 * binds it to the config adapter's secret input, and tests bind it to scripted sources. Every
 * failure surfaces as the typed secret-input configuration error without token material.
 */
@FunctionalInterface
public interface SecretSource {

    /**
     * Reads one secret through the selected channel.
     *
     * @param fromStdin whether {@code --stdin} explicitly requested the one-line stdin contract
     * @throws io.amscotti.bravesearch.domain.config.LocalConfigException with reason {@code
     *     SECRET_INPUT} when no secret can be read through the selected channel
     */
    Credential read(boolean fromStdin);
}
