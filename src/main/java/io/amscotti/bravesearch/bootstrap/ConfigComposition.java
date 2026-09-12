package io.amscotti.bravesearch.bootstrap;

import io.amscotti.bravesearch.adapter.cli.command.config.SecretSource;
import io.amscotti.bravesearch.adapter.config.ConfigPaths;
import io.amscotti.bravesearch.adapter.config.CredentialResolver;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.JsonConfigFile;
import io.amscotti.bravesearch.adapter.config.SecretInput;
import io.amscotti.bravesearch.adapter.config.SecureConfigStore;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.domain.config.LocalConfigException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The config command group's injected ports: the credential provider, the secure store, the
 * secret input seam, and the resolved credential-file path.
 *
 * <p>The process wiring binds every environment, console, and filesystem touch to the config
 * adapter — the environment lookup, the platform path rules, the pinned secure store, and the
 * no-echo console — while every dependency stays lazy: building the root command resolves the
 * credential-file path zero times, so a misconfigured environment fails at the first command
 * that actually needs the path, with the documented clean exit instead of a composition crash.
 * The path resolves at most once per process through a memoized supplier shared by the store
 * and the command group. Tests inject doubles for hermetic runs of the real command line.
 */
public record ConfigComposition(
        CredentialProvider credentials, ConfigStore store, SecretSource secrets, Supplier<Path> configPath) {

    public ConfigComposition {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(configPath, "configPath");
    }

    /** The process wiring: real environment, console, stdin, and platform path rules. */
    public static ConfigComposition process() {
        Function<String, String> environmentLookup = EnvironmentCredentialSource.processEnvironmentLookup();
        return wiring(
                new ConfigPaths(
                        () -> System.getProperty("os.name"),
                        () -> System.getProperty("user.home"),
                        environmentLookup),
                environmentLookup,
                SecretInput.ConsoleAccess.processConsole(),
                System.in,
                processAclProbe());
    }

    /**
     * The shared wiring behind the process factory, with every environment and console touch
     * injected: the platform path rules run behind a memoized supplier, so constructing the
     * composition performs no path resolution at all.
     */
    static ConfigComposition wiring(
            ConfigPaths paths,
            Function<String, String> environmentLookup,
            SecretInput.ConsoleAccess console,
            InputStream stdin,
            SecureConfigStore.AclProbe aclProbe) {
        Supplier<Path> configPath = memoized(paths::credentialFilePath);
        SecureConfigStore store = new SecureConfigStore(
                configPath,
                new JsonConfigFile(),
                ConfigComposition::processOwner,
                SecureConfigStore.SecureStreamOpener.processFilesystem(),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                aclProbe);
        CredentialResolver resolver = new CredentialResolver(
                new EnvironmentCredentialSource(environmentLookup), () -> store.load().credential());
        SecretInput secretInput = new SecretInput(console, stdin);
        return new ConfigComposition(resolver, store, secretInput::read, configPath);
    }

    /**
     * The ACL probe the process deserves: the bounded macOS listing where the POSIX modes
     * cannot see ACLs, and a never-inspecting probe everywhere else.
     */
    private static SecureConfigStore.AclProbe processAclProbe() {
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return platform.startsWith("mac") ? SecureConfigStore.AclProbe.macOsListing() : SecureConfigStore.AclProbe.disabled();
    }

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

    private static UserPrincipal processOwner() {
        try {
            FileSystem filesystem = FileSystems.getDefault();
            return filesystem
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException missing) {
            throw LocalConfigException.unsafeFilesystem("the current user principal cannot be looked up");
        }
    }
}
