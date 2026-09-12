package io.amscotti.bravesearch.adapter.cli.option;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.LocalhostResolver;
import io.amscotti.bravesearch.domain.output.OutputMode;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Loopback-origin and default contracts of the remote-only options: every accepted
 * {@code --base-url} spelling is a literal loopback origin normalized without its trailing
 * slash, every other spelling is a usage error raised at parse time, and the budgets, the
 * output channel, and the version pin keep their documented defaults until overridden.
 */
final class RemoteOptionsTest {

    private static final LocalhostResolver STUB_LOCALHOST = host -> List.of(InetAddress.getByName("127.0.0.1"));

    @Test
    void defaultsHoldTheDocumentedRemoteBudgetsAndChannels() {
        Host host = new Host();

        run(host, "arg");

        assertFalse(host.remote.outputFlagPresent(), "no --output appeared");
        assertEquals(OutputMode.HUMAN, host.remote.outputMode());
        assertEquals(BraveApiOrigin.production(), host.remote.origin());
        assertTrue(host.remote.origin().credentialsAllowed());
        assertEquals(Duration.ofSeconds(30), host.remote.totalTimeout());
        assertEquals(Duration.ofSeconds(10), host.remote.connectTimeout());
        assertEquals(null, host.remote.apiVersionPin());
    }

    @Test
    void literalLoopbackOverridesNormalizeToAnOriginThatNeverTakesStoredCredentials() {
        assertEquals(
                "http://127.0.0.1:9000", parseBaseUrl("http://127.0.0.1:9000").baseUri().toString());
        assertEquals("http://127.0.0.1:9000", parseBaseUrl("http://127.0.0.1:9000/").baseUri().toString());
        assertEquals("http://127.0.0.1", parseBaseUrl("http://127.0.0.1").baseUri().toString());
        assertEquals("https://127.0.0.1:9", parseBaseUrl("https://127.0.0.1:9").baseUri().toString());
        assertEquals("http://[::1]:9", parseBaseUrl("http://[::1]:9").baseUri().toString());
        assertEquals("http://127.0.0.1:9", parseBaseUrl("http://localhost:9").baseUri().toString());
        assertFalse(parseBaseUrl("http://127.0.0.1:9").credentialsAllowed());
    }

    @Test
    void everyNonLoopbackBaseUrlSpellingIsAUsageErrorAtParseTime() {
        List<String> refused = List.of(
                "https://api.search.brave.com",
                "http://example.com",
                "http://127.0.0.2:9",
                "http://LOCALHOST:9",
                "http://127.0.0.1:9/path",
                "http://127.0.0.1:9?q=1",
                "http://127.0.0.1:9#fragment",
                "http://user:pw@127.0.0.1:9",
                "ftp://127.0.0.1:9",
                "http://127.0.0.1:0",
                "http://127.0.0.1:70000",
                "http://127.0.0.1:9:",
                "definitely not a url");

        for (String candidate : refused) {
            Host host = new Host();
            RunResult result = run(host, "--base-url", candidate, "arg");
            assertEquals(
                    2,
                    result.exitCode(),
                    () -> candidate + " must be a usage error: " + result.describe());
            assertFalse(host.remote.outputFlagPresent(), () -> candidate + " fails before any value settles");
        }
    }

    @Test
    void aLocalhostNameThatResolvesOutsideLoopbackIsRefused() {
        LocalhostResolver poisoned = host -> List.of(InetAddress.getByName("10.0.0.7"));
        Host host = new Host(poisoned);

        RunResult result = run(host, "--base-url", "http://localhost:9", "arg");

        assertEquals(2, result.exitCode(), () -> result.describe());
    }

    @Test
    void theOutputShortAliasAndExplicitPrettyFlagsParse() {
        Host host = new Host();

        RunResult result = run(host, "-o", "json", "--pretty", "arg");

        assertEquals(0, result.exitCode(), () -> result.describe());
        assertTrue(host.remote.outputFlagPresent());
        assertEquals(OutputMode.JSON, host.remote.outputMode());
        assertTrue(host.remote.pretty());
    }

    private BraveApiOrigin parseBaseUrl(String rawBaseUrl) {
        Host host = new Host();
        RunResult result = run(host, "--base-url", rawBaseUrl, "arg");
        assertEquals(0, result.exitCode(), () -> rawBaseUrl + " must parse: " + result.describe());
        return host.remote.origin();
    }

    private static RunResult run(Host host, String... args) {
        CommandLine commandLine = StrictOptionParsing.apply(new CommandLine(host));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(stdout, true));
        commandLine.setErr(new PrintWriter(stderr, true));
        int exitCode = commandLine.execute(args);
        return new RunResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8));
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exitCode=" + exitCode + ", stdout=<" + stdout + ">, stderr=<" + stderr + ">";
        }
    }

    @Command(name = "host")
    private static final class Host implements Callable<Integer> {
        @Mixin
        RemoteOptions remote;

        @picocli.CommandLine.Parameters(index = "0", arity = "1..1")
        String argument;

        Host() {
            this(STUB_LOCALHOST);
        }

        Host(LocalhostResolver localhostResolver) {
            this.remote = new RemoteOptions(localhostResolver);
        }

        @Override
        public Integer call() {
            return 0;
        }
    }
}
