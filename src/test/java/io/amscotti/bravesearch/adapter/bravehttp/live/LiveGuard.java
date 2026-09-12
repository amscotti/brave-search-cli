package io.amscotti.bravesearch.adapter.bravehttp.live;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpClientFactory;
import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.config.ConfigPaths;
import io.amscotti.bravesearch.adapter.config.CredentialResolver;
import io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource;
import io.amscotti.bravesearch.adapter.config.JsonConfigFile;
import io.amscotti.bravesearch.adapter.config.SecureConfigStore;
import io.amscotti.bravesearch.api.BraveSearchClient;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.exchange.ResponseLimits;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.application.port.out.CredentialResolutionException;
import io.amscotti.bravesearch.domain.config.Credential;
import io.amscotti.bravesearch.domain.error.Outcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;

/**
 * The one shared precondition of every live protocol test: credential resolution through the
 * canonical provider — the canonical environment variable, then its compatibility alias, then
 * the secure config file — exactly the precedence the process composition wires, resolved
 * through the existing {@link CredentialResolver}/{@link EnvironmentCredentialSource}
 * machinery and never a separate live-test key path.
 *
 * <p>Skip semantics: when no source provides a credential at all, {@link #requirePresent()}
 * aborts the test with the fixed {@link #SKIP_MESSAGE}, so an opted-in run without a key is a
 * clean skip, never a failure and never an exchange. A present-but-invalid credential fails
 * loudly instead: the resolver's already-redacted message surfaces as an error, because a
 * broken explicit setting must not silently degrade into "no key".
 *
 * <p>Serial execution: every live exchange is sent while holding {@link #EXCHANGE_LOCK}, so
 * even a launcher configured for concurrent execution performs the live requests one at a
 * time; the build additionally pins single-fork test execution.
 *
 * <p>Pacing: every live exchange goes through {@link #pacedExchange(ExchangePacing.Exchange)},
 * which holds the exchange lock, spaces dispatches by the shared {@link ExchangePacing} gate
 * — the observed one-request-per-second policy plus margin — and retries a rate-limited
 * exchange exactly once after the snapshot's bounded window reset, skipping with the fixed
 * message when the smallest applicable reset outlives the smoke budget or when the paced
 * retry answers rate-limited again. This is test instrumentation; the product never retries.
 *
 * <p>Token discipline: no guard surface ever renders the credential. The only text the guard
 * produces about an exchange is {@link #redacted(String)}, which asserts the line carries no
 * credential material and bounds its length, so an observation line can never leak the token
 * or grow into a result dump.
 *
 * <p>Wire-path expectation: {@link #productionPath(String)} derives the exact full request
 * path every live test asserts — the production base URL's path joined with the endpoint
 * segment — so the expectation always carries the origin's versioned prefix and can never
 * drift from the origin the exchange actually targets.
 */
public final class LiveGuard {

    /** The fixed skip reason of every live test without a resolvable credential. */
    public static final String SKIP_MESSAGE = "live tests require BRAVE_API_KEY";

    /** The fixed skip reason of the places probe when the key's plan lacks the places option. */
    public static final String PLACE_SEARCH_NOT_IN_PLAN_MESSAGE = "place search is not included in this plan";

    /** The fixed skip reason of the llm-context probe when the key's plan lacks the llm-context option. */
    public static final String LLM_CONTEXT_NOT_IN_PLAN_MESSAGE = "llm context is not included in this plan";

    /**
     * The fixed skip reason of both answers probes when the key's plan lacks the answers
     * option: the nested-versus-flat wire-form question cannot settle on an unentitled key.
     */
    public static final String ANSWERS_NOT_IN_PLAN_MESSAGE =
            "answers is not included in this plan — the nested web_search_options probe requires an entitled key";

    /** The upstream error code answering a versioned exchange whose pin names an unserved version. */
    public static final String API_VERSION_NOT_FOUND_CODE = "API_VERSION_NOT_FOUND";

    /**
     * The total budget of the shared live client ({@link #client()}): every live test's JUnit
     * deadline must fire strictly after it, so a hung exchange fails first as the client's
     * own classified transport failure — with its diagnostic — and the JUnit timeout stays
     * the backstop, never the reporter.
     */
    public static final Duration CLIENT_TOTAL_TIMEOUT = Duration.ofSeconds(120);

    /** The longest observation line {@link #redacted(String)} ever emits. */
    private static final int MAX_OBSERVATION_CHARS = 128;

    /** The class-level monitor every live exchange holds, keeping the opted-in run serial. */
    public static final Object EXCHANGE_LOCK = new Object();

    /** The one pacing gate every live dispatch rides, shared across the whole run. */
    private static final ExchangePacing PACING =
            new ExchangePacing(Clock.systemUTC(), duration -> Thread.sleep(duration));

    private static final LiveGuard PROCESS_GUARD =
            new LiveGuard(EnvironmentCredentialSource.processEnvironmentLookup(), LiveGuard::processFileCredential);

    private final CredentialProvider provider;

    private volatile Credential resolved;

    LiveGuard(Function<String, String> environmentLookup, Supplier<Optional<Credential>> fileCredential) {
        this.provider = new CredentialResolver(
                new EnvironmentCredentialSource(environmentLookup), fileCredential);
    }

    /** The guard of the live suite: the canonical provider over the real process environment. */
    static LiveGuard process() {
        return PROCESS_GUARD;
    }

    /** Skips the calling test when no credential source provides one; never renders values. */
    public static void requireCredential() {
        PROCESS_GUARD.requirePresent();
    }

    /** The memoized process credential; call only after {@link #requireCredential()} passed. */
    public static Credential credential() {
        return PROCESS_GUARD.resolveOrFailFixed();
    }

    /** Asserts the observation line carries no credential material and bounds its length. */
    public static String redacted(String observation) {
        return PROCESS_GUARD.boundedObservation(observation);
    }

    /** The exact full wire path the given endpoint segment rides on under the production origin. */
    public static String productionPath(String endpointSegment) {
        return URI.create(BraveApiOrigin.PRODUCTION_BASE_URL).getPath() + "/" + endpointSegment;
    }

    /**
     * One live exchange through the shared pacing gate: the exchange lock is held throughout,
     * the dispatch waits for its slot, and a rate-limited answer is retried exactly once
     * after the snapshot's bounded window reset — skipping with the fixed message when that
     * reset outlives the smoke budget, and skipping the same way when the paced retry answers
     * rate-limited again. The caller opens and closes its own client inside the exchange, so
     * the retry opens a fresh one.
     */
    public static <T> Outcome<T> pacedExchange(ExchangePacing.Exchange<T> exchange) throws Exception {
        synchronized (EXCHANGE_LOCK) {
            return PACING.dispatchPaced(exchange);
        }
    }

    /**
     * Whether a failed exchange is the documented plan-absence shape — HTTP 400 with
     * upstream code {@code OPTION_NOT_IN_PLAN} — and therefore an entitlement skip rather
     * than a failure: the invariants hold according to the plan entitlements the key
     * actually carries, so every entitlement-gated probe (places, llm context, answers)
     * reuses this one shape test. Every other failure shape stays the caller's to fail on.
     */
    public static boolean planOptionAbsent(Outcome.Failure<?> failure) {
        return failure.httpStatus() == 400
                && failure.upstream() != null
                && "OPTION_NOT_IN_PLAN".equals(failure.upstream().code());
    }

    /**
     * Whether a failed versioned exchange is the typed upstream rejection of an unserved
     * pin — a 4xx answering upstream code {@link #API_VERSION_NOT_FOUND_CODE}: proof the
     * pin reached the server and was evaluated, and therefore a recorded observation
     * rather than a failure. Every other failure shape stays the caller's to fail on.
     */
    public static boolean apiVersionNotFound(Outcome.Failure<?> failure) {
        return failure.httpStatus() >= 400
                && failure.httpStatus() <= 499
                && failure.upstream() != null
                && API_VERSION_NOT_FOUND_CODE.equals(failure.upstream().code());
    }

    /**
     * A live client over the public composition: the token supplier resolves per exchange
     * through the same guard, and the generous total budget bounds dispatch through the
     * complete body of one real network exchange.
     */
    public static BraveSearchClient client() {
        return BraveSearchClient.builder()
                .tokenSupplier(LiveGuard::credential)
                .connectTimeout(Duration.ofSeconds(10))
                .totalTimeout(CLIENT_TOTAL_TIMEOUT)
                .build();
    }

    /** A live transport mirroring the library composition's wiring; callers close it. */
    public static BraveHttpTransport transport() {
        return new BraveHttpTransport(
                new BraveHttpClientFactory(Duration.ofSeconds(10)).newClient(),
                Duration.ofSeconds(30),
                ResponseLimits.production(),
                Clock.systemUTC());
    }

    /** Skips the calling test when resolution reports every source missing. */
    void requirePresent() {
        Assumptions.assumeTrue(credentialOrNull() != null, SKIP_MESSAGE);
    }

    /**
     * The resolved credential, or null when every source is missing. A present-but-invalid
     * source fails loudly with the resolver's already-redacted message.
     */
    Credential credentialOrNull() {
        Credential already = resolved;
        if (already != null) {
            return already;
        }
        try {
            Credential credential = provider.resolve();
            resolved = credential;
            return credential;
        } catch (CredentialResolutionException unresolved) {
            if (unresolved.category() == CredentialResolutionException.Category.MISSING) {
                return null;
            }
            throw new IllegalStateException(unresolved.getMessage());
        }
    }

    /** Like {@link #credentialOrNull()} for callers past the guard; fixed failure text. */
    Credential resolveOrFailFixed() {
        Credential credential = credentialOrNull();
        if (credential == null) {
            throw new IllegalStateException(SKIP_MESSAGE);
        }
        return credential;
    }

    /** Asserts the line carries no resolved credential material, then bounds its length. */
    String boundedObservation(String observation) {
        Objects.requireNonNull(observation, "observation");
        Credential credential = resolved;
        if (credential != null) {
            String token = new String(credential.tokenBytes(), StandardCharsets.UTF_8);
            if (observation.contains(token)) {
                throw new AssertionError("a live observation line carried credential material");
            }
        }
        return observation.length() <= MAX_OBSERVATION_CHARS
                ? observation
                : observation.substring(0, MAX_OBSERVATION_CHARS) + "...";
    }

    /**
     * The secure-config leg of the canonical precedence, wired exactly like the process
     * composition: the platform credential path, the JSON codec, the process owner, and the
     * real secure-open, hard-link, and ACL probes.
     */
    private static Optional<Credential> processFileCredential() {
        Function<String, String> environmentLookup = EnvironmentCredentialSource.processEnvironmentLookup();
        ConfigPaths paths = new ConfigPaths(
                () -> System.getProperty("os.name"), () -> System.getProperty("user.home"), environmentLookup);
        SecureConfigStore store = new SecureConfigStore(
                paths::credentialFilePath,
                new JsonConfigFile(),
                LiveGuard::processOwner,
                SecureConfigStore.SecureStreamOpener.processFilesystem(),
                SecureConfigStore.HardLinkCounter.processFilesystem(),
                aclProbe());
        return store.load().credential();
    }

    private static UserPrincipal processOwner() {
        try {
            return FileSystems.getDefault()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    private static SecureConfigStore.AclProbe aclProbe() {
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return platform.startsWith("mac")
                ? SecureConfigStore.AclProbe.macOsListing()
                : SecureConfigStore.AclProbe.disabled();
    }
}
