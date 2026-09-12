package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Credential lifecycle of the context process wiring: one invocation resolves its
 * credential exactly once. The command's preflight resolution is the only one — the
 * resolved credential travels into the exchange, so the per-invocation gateway consumes it
 * instead of re-resolving from the environment a second time.
 */
final class ContextCompositionTest {

    @TempDir
    Path home;

    @Test
    void aLoopbackRunResolvesItsCredentialExactlyOnce() {
        AtomicInteger testKeyLookups = new AtomicInteger();
        String proxyToken = "proxy-placeholder-token-" + java.util.UUID.randomUUID().toString().replace("-", "");
        Map<String, String> keyedEnvironment = Map.of("BRAVE_SEARCH_TEST_KEY", proxyToken);
        ContextComposition process = ContextComposition.process(
                storedCredentials(),
                name -> {
                    if ("BRAVE_SEARCH_TEST_KEY".equals(name)) {
                        testKeyLookups.incrementAndGet();
                        return keyedEnvironment.get(name);
                    }
                    return null;
                });

        RunResult result = runMain(process, "context", "--base-url", "http://127.0.0.1:1", "q");

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

    private RunResult runMain(ContextComposition context, String... args) {
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
            int exitCode = new Main(new ExchangeRegistry(), config, webComposition(), context)
                    .execute(args);
            return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static WebSearchComposition webComposition() {
        WebSearchComposition process = WebSearchComposition.process(storedCredentials());
        return new WebSearchComposition(
                process.loopbackTestToken(), process.exchange(), failingWebPresenter(), process.pagination(), process.pagedPresenter());
    }

    private static io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter failingWebPresenter() {
        return new io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter() {
            @Override
            public int present(
                    Outcome<io.amscotti.bravesearch.domain.result.WebSearchResult> outcome,
                    io.amscotti.bravesearch.domain.request.WebSearchRequest request,
                    OutputRequest output,
                    ResultWriter results,
                    DiagnosticsSink diagnostics) {
                throw new IllegalStateException("the web command is not exercised by these runs");
            }
        };
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
