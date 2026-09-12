package io.amscotti.bravesearch.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.adapter.cli.presentation.DiagnosticsSink;
import io.amscotti.bravesearch.adapter.cli.presentation.WebSearchPresenter;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.port.out.ConfigStore;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.application.port.out.ResolvedCredential;
import io.amscotti.bravesearch.application.port.out.WebSearchDispatch;
import io.amscotti.bravesearch.application.port.out.WebSearchExchange;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import io.amscotti.bravesearch.domain.metadata.UpstreamPayload;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import io.amscotti.bravesearch.domain.result.WebSearchResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The web command runs through the process root wiring exactly as the entry point composes
 * it: the root grammar keeps remote options behind the subcommand token, the shared globals
 * parse after it, the version line stays byte-identical in every spelling, and the loopback
 * test-key seam resolves from the environment before any loopback exchange is attempted.
 */
final class WebSearchMainTest {

    @TempDir
    Path home;

    @Test
    void webDispatchesThroughTheRootCommandLineWithInjectedPorts() {
        RecordingExchange exchange = new RecordingExchange();
        RecordingPresenter presenter = new RecordingPresenter();

        RunResult result = runMain(webSearch(exchange, presenter), "web", "hello world");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, exchange.dispatches.size());
        WebSearchDispatch dispatch = exchange.dispatches.getFirst();
        assertEquals(BraveApiOrigin.production(), dispatch.origin());
        assertEquals(Duration.ofSeconds(30), dispatch.totalTimeout());
        assertEquals(Duration.ofSeconds(10), dispatch.connectTimeout());
        assertEquals(null, dispatch.pinnedApiVersion());
        assertEquals(1, presenter.rendered, "the presenter owns the successful rendering");
    }

    @Test
    void remoteOptionsStayBehindTheSubcommandTokenInTheRootGrammar() {
        RecordingExchange exchange = new RecordingExchange();

        RunResult result = runMain(webSearch(exchange, new RecordingPresenter()), "--output", "json", "web", "q");

        assertEquals(2, result.exitCode(), () -> result.describe());
        assertEquals(0, exchange.dispatches.size());
        assertTrue(result.stderr().contains("Usage: brave-search"), () -> result.describe());
    }

    @Test
    void sharedGlobalsParseAfterTheSubcommandTokenInTheRootGrammar() {
        RecordingExchange exchange = new RecordingExchange();

        RunResult result = runMain(webSearch(exchange, new RecordingPresenter()), "web", "--verbose", "q");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertEquals(1, exchange.dispatches.size());
    }

    @Test
    void theWebVersionFlagPrintsTheRootVersionLineByteExactly() {
        RecordingExchange exchange = new RecordingExchange();

        RunResult flag = runMain(webSearch(exchange, new RecordingPresenter()), "web", "-V");
        RunResult root = runMain(webSearch(exchange, new RecordingPresenter()), "--version");

        assertEquals(0, flag.exitCode(), () -> flag.describe());
        assertEquals(root.stdout(), flag.stdout(), "the version line is identical in every spelling");
        assertTrue(
                flag.stdout().startsWith(Main.COMMAND_NAME + " ") && flag.stdout().endsWith("\n"),
                () -> flag.describe());
    }

    @Test
    void theProcessCompositionResolvesTheLoopbackTestKeyBeforeAnyLoopbackExchange() {
        Map<String, String> emptyEnvironment = new HashMap<>();
        WebSearchComposition withoutKey =
                WebSearchComposition.process(configCompositionCredentials(), emptyEnvironment::get);

        RunResult missing = runMain(withoutKey, "web", "--base-url", "http://127.0.0.1:1", "q");

        assertEquals(3, missing.exitCode(), "a loopback run without a test key keeps the configuration status");
        assertTrue(missing.stderr().contains("BRAVE_SEARCH_TEST_KEY"), () -> missing.describe());

        Map<String, String> keyedEnvironment = new HashMap<>();
        keyedEnvironment.put("BRAVE_SEARCH_TEST_KEY", "proxy-placeholder-token");
        WebSearchComposition withKey =
                WebSearchComposition.process(configCompositionCredentials(), keyedEnvironment::get);

        RunResult refused = runMain(withKey, "web", "--base-url", "http://127.0.0.1:1", "q");

        assertEquals(
                6,
                refused.exitCode(),
                "a resolved loopback credential reaches the transport, which fails to connect: "
                        + refused.describe());
    }

    private CredentialProvider configCompositionCredentials() {
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

    private WebSearchComposition webSearch(WebSearchExchange exchange, WebSearchPresenter presenter) {
        WebSearchComposition process = WebSearchComposition.process(configCompositionCredentials());
        return new WebSearchComposition(
                failingCredentials("BRAVE_SEARCH_TEST_KEY"), exchange, presenter, process.pagination(), process.pagedPresenter());
    }

    private static CredentialProvider failingCredentials(String name) {
        return new CredentialProvider() {
            @Override
            public Credential resolve() throws CredentialResolutionException {
                throw CredentialResolutionException.missing("environment " + name);
            }

            @Override
            public ResolvedCredential resolveWithProvenance() throws CredentialResolutionException {
                throw resolveFailure();
            }

            private CredentialResolutionException resolveFailure() {
                return CredentialResolutionException.missing("environment " + name);
            }
        };
    }

    private RunResult runMain(WebSearchComposition webSearch, String... args) {
        ConfigComposition config = new ConfigComposition(
                configCompositionCredentials(),
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
            int exitCode = new Main(new ExchangeRegistry(), config, webSearch).execute(args);
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

    private static final class RecordingExchange implements WebSearchExchange {
        final List<WebSearchDispatch> dispatches = new ArrayList<>();
        Outcome<WebSearchResult> outcome = new Outcome.Success<>(
                new WebSearchResult(200, new UpstreamPayload("{}".getBytes(UTF_8)), null, null, null, null));

        @Override
        public Outcome<WebSearchResult> dispatch(WebSearchDispatch invocation) {
            dispatches.add(invocation);
            return outcome;
        }
    }

    private static final class RecordingPresenter implements WebSearchPresenter {
        int rendered;

        @Override
        public int present(
                Outcome<WebSearchResult> outcome,
                io.amscotti.bravesearch.domain.request.WebSearchRequest request,
                OutputRequest output,
                io.amscotti.bravesearch.adapter.cli.presentation.ResultWriter results,
                DiagnosticsSink diagnostics) {
            rendered++;
            return 0;
        }
    }
}
