package io.amscotti.bravesearch.architecture;

import io.amscotti.bravesearch.adapter.bravehttp.BraveHttpTransport;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpWebSearchGateway;
import io.amscotti.bravesearch.application.exchange.BraveApiOrigin;
import io.amscotti.bravesearch.application.port.out.CredentialProvider;
import io.amscotti.bravesearch.architecture.cycle.CyclePartner;
import io.amscotti.bravesearch.domain.config.Credential;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import sun.misc.Signal;

/**
 * Compile-safe architecture violators, one per architecture rule where a violation
 * does not require a dedicated package. The classes are never instantiated or executed; ArchUnit
 * reads them as distinct class files from the test class output.
 */
public final class ArchFixtures {

    private ArchFixtures() {}

    /**
     * Rule 4 fixture: instantiates a concrete adapter outside the composition roots. The
     * null arguments keep the call compile-safe; the fixture never executes.
     */
    public static final class ViolatesCompositionRoot {

        private final BraveHttpWebSearchGateway gateway =
                new BraveHttpWebSearchGateway(null, null, null, null, null, null);

        public BraveHttpWebSearchGateway gateway() {
            return gateway;
        }
    }

    /**
     * A constructor reference needs a matching functional shape; this one mirrors the
     * gateway's six-argument constructor.
     */
    @FunctionalInterface
    private interface GatewayFactory {

        BraveHttpWebSearchGateway create(
                BraveHttpTransport transport,
                BraveApiOrigin origin,
                CredentialProvider storedCredentials,
                Supplier<Credential> loopbackTestToken,
                java.time.Duration totalTimeout,
                String pinnedApiVersion);
    }

    /**
     * Rule 4 fixture: hands a concrete adapter constructor to a {@link GatewayFactory}
     * without ever invoking it, so only a constructor-reference check can catch the wiring.
     */
    public static final class ViolatesCompositionRootByReference {

        private final GatewayFactory gatewaySupplier = BraveHttpWebSearchGateway::new;

        public GatewayFactory gatewaySupplier() {
            return gatewaySupplier;
        }
    }

    /** Rule 5 fixture: references {@code java.net.http} outside {@code adapter.bravehttp}. */
    public static final class ViolatesHttpIsolation {

        public HttpClient client() {
            return HttpClient.newHttpClient();
        }
    }

    /** Rule 6 fixture: calls {@code System.exit} outside {@code bootstrap.Main}. */
    public static final class ViolatesProcessExitOwnership {

        public void exit() {
            System.exit(0);
        }
    }

    /** Rule 6 fixture: terminates the JVM through the runtime object outside {@code bootstrap.Main}. */
    public static final class ViolatesRuntimeExitOwnership {

        public void exitNow() {
            Runtime.getRuntime().exit(0);
        }

        public void haltNow() {
            Runtime.getRuntime().halt(1);
        }
    }

    /** Rule 7 fixture: accesses a standard stream outside CLI presentation and bootstrap. */
    public static final class ViolatesStandardStreamsOwnership {

        public void announce() {
            System.err.println("fixture");
        }
    }

    /** Rule 7 fixture: takes standard input outside bootstrap and the configuration adapter. */
    public static final class ViolatesStandardInputOwnership {

        public int read() throws java.io.IOException {
            return System.in.read();
        }
    }

    /** Rule 8 fixture: reads the environment outside {@code adapter.config} and the detector. */
    public static final class ViolatesEnvironmentAccessOwnership {

        public String home() {
            return System.getenv("HOME");
        }
    }

    /** Rule 13 fixture: touches the sun.misc signal machinery outside the composition root. */
    public static final class ViolatesSignalOwnership {

        public void latch() {
            Signal.handle(new Signal("USR2"), signal -> {
            });
        }
    }

    /** Rule 12 fixture: uses reflection APIs directly. */
    public static final class ViolatesReflectionBan {

        private final Constructor<String> constructor = null;

        public Object construct() throws ReflectiveOperationException {
            return constructor.newInstance();
        }

        public Class<?> load() throws ClassNotFoundException {
            return Class.forName("java.lang.String");
        }
    }

    /** Rule 12 fixture: touches the {@code java.lang.invoke} machinery outside any adapter need. */
    public static final class ViolatesMethodHandleUse {

        public Object lookup() {
            return MethodHandles.lookup();
        }
    }

    /** Rule 12 fixture: discovers implementations through the service loader instead of wiring. */
    public static final class ViolatesServiceLoaderUse {

        public Object service() {
            return ServiceLoader.load(Runnable.class);
        }
    }

    /**
     * Rule 12 fixture: discovers installed implementations through the service loader's
     * second entry point, which is the same dynamic-discovery seam as {@code load}.
     */
    public static final class ViolatesServiceLoaderInstalledUse {

        public Object service() {
            return ServiceLoader.loadInstalled(Runnable.class);
        }
    }

    /** Rule 10 fixture: one side of a package cycle with {@link CyclePartner}. */
    public static final class CycleSource {

        public String send(CyclePartner partner) {
            return partner.back(this);
        }
    }

    /**
     * Rule fixture: holds mutable state in a static field. The state never needs to be read;
     * the field itself is the violation.
     */
    public static final class ViolatesNoStaticMutableState {

        public static String mutable = "fixture";
    }

    /**
     * Rule fixture: holds a known-mutable container in a static final field. Finality alone
     * does not make a mutable container immutable configuration; the field is the violation.
     */
    public static final class ViolatesStaticMutableContainer {

        public static final java.util.ArrayList<String> items = new java.util.ArrayList<>();

        public static final java.util.concurrent.atomic.AtomicLong counter =
                new java.util.concurrent.atomic.AtomicLong();
    }

    /**
     * Rule fixture: holds stateful JDK services in static final fields. Finality does not
     * turn an executor, a random source, or a scanner into immutable configuration; the
     * fields are the violation, and the fixture never executes.
     */
    public static final class ViolatesStaticMutableService {

        public static final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();

        public static final java.util.Random random = new java.util.Random();

        public static final java.util.Scanner scanner = new java.util.Scanner("fixture");
    }

    /** Rule fixture: builds its own Jackson mapper outside the three mapper role classes. */
    public static final class ViolatesMapperContainment {

        private final tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();

        public Object read(String json) {
            return mapper.readTree(json);
        }
    }

    /** Rule fixture: constructs a Jackson mapper through its public constructor outside the role classes. */
    public static final class ViolatesMapperConstructorContainment {

        private final tools.jackson.databind.json.JsonMapper mapper =
                new tools.jackson.databind.json.JsonMapper();

        public Object read(String json) {
            return mapper.readTree(json);
        }
    }

    /** Rule fixture: constructs the mapper supertype directly outside the role classes. */
    public static final class ViolatesObjectMapperConstructorContainment {

        private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

        public Object read(String json) {
            return mapper.readTree(json);
        }
    }

    /** Rule fixture: parses through the JVM-global shared mapper instead of a role codec. */
    public static final class ViolatesSharedMapperContainment {

        public Object read(String json) {
            return tools.jackson.databind.json.JsonMapper.shared().readTree(json);
        }
    }

    /**
     * Rule fixture: hands the mapper constructor to a {@link java.util.function.Supplier}
     * without ever invoking it, so only a constructor-reference check can catch the wiring.
     */
    public static final class ViolatesMapperReferenceContainment {

        private final java.util.function.Supplier<tools.jackson.databind.json.JsonMapper> mapperSupplier =
                tools.jackson.databind.json.JsonMapper::new;

        public java.util.function.Supplier<tools.jackson.databind.json.JsonMapper> mapperSupplier() {
            return mapperSupplier;
        }
    }

    /**
     * Rule fixture: hands the shared-mapper and builder factories to
     * {@link java.util.function.Supplier}s without ever invoking them, so only a
     * method-reference check can catch the wiring.
     */
    public static final class ViolatesMapperMethodReferenceContainment {

        private final java.util.function.Supplier<tools.jackson.databind.json.JsonMapper> sharedMapper =
                tools.jackson.databind.json.JsonMapper::shared;

        private final java.util.function.Supplier<tools.jackson.databind.json.JsonMapper.Builder> mapperBuilder =
                tools.jackson.databind.json.JsonMapper::builder;

        public java.util.function.Supplier<tools.jackson.databind.json.JsonMapper> sharedMapper() {
            return sharedMapper;
        }

        public java.util.function.Supplier<tools.jackson.databind.json.JsonMapper.Builder> mapperBuilder() {
            return mapperBuilder;
        }
    }

    /** Rule fixture: reads a system property outside the process-property seam. */
    public static final class ViolatesSystemPropertySeam {

        public String name() {
            return System.getProperty("os.name");
        }
    }

    /**
     * Rule fixture: reads system properties through the {@code java.lang} convenience lookups,
     * which are property reads in disguise, outside the process-property seam.
     */
    public static final class ViolatesSystemPropertyAliasRead {

        public boolean flag() {
            return Boolean.getBoolean("fixture");
        }

        public Integer limit() {
            return Integer.getInteger("fixture");
        }

        public Long stamp() {
            return Long.getLong("fixture");
        }
    }

    /** Rule fixture: mutates the process properties outside the process-property seam. */
    public static final class ViolatesSystemPropertyWrite {

        public void apply() {
            System.setProperty("fixture", "value");
            System.clearProperty("other");
        }
    }

    /**
     * Rule fixture: depends on the credential-source class outside {@code adapter.config}, the
     * way a second observer of the API-key variable names is born. The call is never executed;
     * a method call defeats the constant inlining that would hide a plain constant reference.
     */
    public static final class ViolatesApiKeyVariableConfinement {

        public java.util.function.Function<String, String> lookup() {
            return io.amscotti.bravesearch.adapter.config.EnvironmentCredentialSource.processEnvironmentLookup();
        }
    }
}
