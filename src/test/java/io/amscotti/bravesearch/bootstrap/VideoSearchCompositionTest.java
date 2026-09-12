package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Credential lifecycle of the process wiring: one invocation resolves its credential exactly
 * once. The command's preflight resolution is the only one — the resolved credential travels
 * into the exchange, so the per-invocation gateway consumes it instead of re-resolving from
 * the environment a second time.
 */
final class VideoSearchCompositionTest {

    @TempDir
    Path home;

    @Test
    void aLoopbackRunResolvesItsCredentialExactlyOnce() {
        AtomicInteger testKeyLookups = new AtomicInteger();
        String proxyToken =
                "proxy-placeholder-token-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> keyedEnvironment = Map.of("BRAVE_SEARCH_TEST_KEY", proxyToken);
        Function<String, String> environment = name -> {
            if ("BRAVE_SEARCH_TEST_KEY".equals(name)) {
                testKeyLookups.incrementAndGet();
                return keyedEnvironment.get(name);
            }
            return null;
        };
        ExchangeRegistry exchanges = new ExchangeRegistry();
        VideoSearchComposition process = VideoSearchComposition.process(storedCredentials(), environment);

        RunResult result = runMain(exchanges, environment, process, "videos", "--base-url", "http://127.0.0.1:1", "q");

        assertEquals(
                6,
                result.exitCode(),
                "the resolved credential reaches the transport, which fails to connect: " + result.describe());
        assertEquals(1, testKeyLookups.get(), "the test-key source is consulted exactly once per run");
    }
    private static CredentialProvider storedCredentials() {
        return new CredentialProvider() {
            @Override
            public Credential resolve() {
                return Credential.of("stored-production-token".getBytes(UTF_8));
            }

            @Override
            public ResolvedCredential resolveWithProvenance() {
                return new ResolvedCredential(resolve(), "environment BRAVE_API_KEY", false);
            }
        };
    }


    private RunResult runMain(
            ExchangeRegistry exchanges, Function<String, String> environment, VideoSearchComposition videosSearch, String... args) {
        ConfigComposition config = new ConfigComposition(
                storedCredentials(),
                unusedStore(),
                fromStdin -> {
                    throw new IllegalStateException("no secret input is read by these runs");
                },
                () -> home.resolve("config.json"));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, UTF_8));
        System.setErr(new PrintStream(stderr, true, UTF_8));
        try {
            int exitCode = new Main(
                            exchanges,
                            config,
                            WebSearchComposition.process(storedCredentials(), environment),
                            ContextComposition.process(storedCredentials(), environment),
                            NewsSearchComposition.process(storedCredentials(), environment),
                            videosSearch,
                            ImageSearchComposition.process(storedCredentials(), environment),
                            SuggestComposition.process(storedCredentials(), environment),
                            SpellcheckComposition.process(storedCredentials(), environment),
                            PlacesComposition.process(storedCredentials(), environment),
                            PlacesEnrichmentComposition.process(storedCredentials(), environment),
                            RichComposition.process(storedCredentials(), environment),
                            AnswersComposition.process(storedCredentials(), exchanges)
                            )
                    .execute(args);
            return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
    private static ConfigStore unusedStore() {
        return new ConfigStore() {
            @Override
            public io.amscotti.bravesearch.domain.config.ConfigState load() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public io.amscotti.bravesearch.domain.config.ConfigFileSight peekSummary() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public void store(Credential credential) {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public void clear() {
                throw new IllegalStateException("the store is not read by these runs");
            }

            @Override
            public io.amscotti.bravesearch.domain.config.PermissionRepair repairPermissions() {
                throw new IllegalStateException("the store is not read by these runs");
            }
        };
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }
}
