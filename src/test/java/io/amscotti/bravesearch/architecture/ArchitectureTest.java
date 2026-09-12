package io.amscotti.bravesearch.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorReference;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.amscotti.bravesearch.adapter.bravehttp.endpoint.BraveHttpWebSearchGateway;
import io.amscotti.bravesearch.adapter.cli.command.CliCrossAdapterFixture;
import io.amscotti.bravesearch.adapter.configlookalike.ViolatesConfigSiblingPrefix;
import io.amscotti.bravesearch.adapterimpostor.PrefixSiblingType;
import io.amscotti.bravesearch.adapterimpostor.WiresPrefixSiblingType;
import io.amscotti.bravesearch.api.ApiSurfaceFixture;
import io.amscotti.bravesearch.application.ApplicationBoundaryFixture;
import io.amscotti.bravesearch.architecture.cycle.CyclePartner;
import io.amscotti.bravesearch.architecture.cycle.IntraPackageCyclePartner;
import io.amscotti.bravesearch.architecture.cycle.IntraPackageCycleSource;
import io.amscotti.bravesearch.architecture.cycle.IntraPackageNestedCyclePartner;
import io.amscotti.bravesearch.architecture.cycle.IntraPackageNestedCycleSource;
import io.amscotti.bravesearch.bootstrap.Main;
import io.amscotti.bravesearch.domain.DomainIsolationFixture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Executable form of the architecture rules that govern this code base.
 *
 * <p>Every rule is proven twice: it bites a dedicated compile-safe violating fixture (evaluated in
 * isolation and within the union of production and fixture classes), and it leaves the production
 * classes clean. Fixtures live in the test source set inside the layer packages whose isolation
 * they violate; they are never executed.
 */
final class ArchitectureTest {

    private static final String ROOT_PACKAGE = "io.amscotti.bravesearch";
    private static final String DOMAIN = ROOT_PACKAGE + ".domain..";
    private static final String APPLICATION = ROOT_PACKAGE + ".application..";
    private static final String ADAPTER = ROOT_PACKAGE + ".adapter..";
    private static final String BRAVEHTTP = ROOT_PACKAGE + ".adapter.bravehttp..";
    private static final String BOOTSTRAP = ROOT_PACKAGE + ".bootstrap..";
    private static final String API_PACKAGE = ROOT_PACKAGE + ".api";
    private static final String API_HIERARCHY = ROOT_PACKAGE + ".api..";
    private static final String API_INTERNAL = ROOT_PACKAGE + ".api.internal..";
    private static final String CLI_PRESENTATION = ROOT_PACKAGE + ".adapter.cli.presentation..";
    private static final String ADAPTER_PREFIX = ROOT_PACKAGE + ".adapter";
    private static final String BOOTSTRAP_PREFIX = ROOT_PACKAGE + ".bootstrap";
    private static final String CONFIG_PREFIX = ROOT_PACKAGE + ".adapter.config";
    private static final String CONFIG = CONFIG_PREFIX + "..";
    private static final String MAIN_CLASS = ROOT_PACKAGE + ".bootstrap.Main";
    private static final String TERMINAL_DETECTOR = ROOT_PACKAGE + ".adapter.cli.presentation.TerminalDetector";
    private static final String ENVIRONMENT_CREDENTIAL_SOURCE =
            ROOT_PACKAGE + ".adapter.config.EnvironmentCredentialSource";
    private static final String JSON_MAPPERS = ROOT_PACKAGE + ".adapter.cli.presentation.json.JsonMappers";
    private static final String UPSTREAM_JSON_CODEC = ROOT_PACKAGE + ".adapter.bravehttp.json.UpstreamJsonCodec";
    private static final String JSON_CONFIG_FILE = ROOT_PACKAGE + ".adapter.config.JsonConfigFile";
    private static final String JACKSON_MAPPER = "tools.jackson.databind.json.JsonMapper";
    private static final String JACKSON_OBJECT_MAPPER = "tools.jackson.databind.ObjectMapper";

    /**
     * The factory methods through which a Jackson mapper is acquired — the method-reference
     * mirror of {@code mapperAcquisitionCall()}.
     */
    private static final Set<String> MAPPER_ACQUISITION_METHODS =
            Set.of("builder", "builderWithJackson2Defaults", "rebuild", "shared");

    /**
     * Exported types outside {@code api..}/{@code domain..} that package {@code api} may reference;
     * the constant form of the allowlist whose normative home is the exported-types section of
     * {@code docs/java-api.md} — extending it is a deliberate act that fails here until the
     * documentation and the constant agree.
     */
    private static final Set<String> API_EXPORT_ALLOWLIST =
            Set.of("tools.jackson.core.JacksonException", "tools.jackson.databind.JsonNode");

    /**
     * The JDK's mutable containers and atomics, together with its stateful service types: a
     * static field of any of these types is state the whole process shares, whatever finality
     * says — a static executor or timer is a service locator in disguise, and a static random
     * source or scanner is process-wide mutable state. The names are erased types, so a field
     * declared through an interface (the shape immutable {@code List.of}/{@code Map.of}
     * constants take) is judged by its own type instead of by this list, and a static final
     * array is constant configuration the codebase only ever reads.
     */
    private static final Set<String> MUTABLE_STATIC_FIELD_TYPES = Set.of(
            "java.lang.StringBuilder",
            "java.lang.StringBuffer",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.ArrayDeque",
            "java.util.PriorityQueue",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.IdentityHashMap",
            "java.util.WeakHashMap",
            "java.util.EnumMap",
            "java.util.Hashtable",
            "java.util.Properties",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Vector",
            "java.util.Stack",
            "java.util.BitSet",
            "java.util.Date",
            "java.util.Calendar",
            "java.util.Random",
            "java.util.Scanner",
            "java.util.Timer",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentLinkedQueue",
            "java.util.concurrent.ConcurrentLinkedDeque",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.concurrent.ConcurrentSkipListSet",
            "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.DelayQueue",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService",
            "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicIntegerArray",
            "java.util.concurrent.atomic.AtomicLong",
            "java.util.concurrent.atomic.AtomicLongArray",
            "java.util.concurrent.atomic.AtomicReference",
            "java.util.concurrent.atomic.AtomicReferenceArray",
            "java.util.concurrent.atomic.AtomicMarkableReference",
            "java.util.concurrent.atomic.AtomicStampedReference",
            "java.util.concurrent.atomic.DoubleAdder",
            "java.util.concurrent.atomic.DoubleAccumulator",
            "java.util.concurrent.atomic.LongAdder",
            "java.util.concurrent.atomic.LongAccumulator");

    private static final ArchRule RULE_1_DOMAIN_ISOLATION =
            noClasses().that().resideInAnyPackage(DOMAIN).should().dependOnClassesThat()
                    .resideInAnyPackage(APPLICATION, ADAPTER, BOOTSTRAP);

    private static final ArchRule RULE_2_APPLICATION_BOUNDARY =
            classes().that().resideInAnyPackage(APPLICATION).should().onlyDependOnClassesThat()
                    .resideInAnyPackage(APPLICATION, DOMAIN, "java..", "jdk..");

    private static final ArchRule RULE_3A_ADAPTER_DEPENDENCIES =
            noClasses().that().resideInAnyPackage(ADAPTER).should().dependOnClassesThat()
                    .resideInAnyPackage(BOOTSTRAP, API_HIERARCHY);

    private static final SliceRule RULE_3B_NO_SIBLING_ADAPTERS =
            SlicesRuleDefinition.slices().matching(ROOT_PACKAGE + ".adapter.(*)..").should().notDependOnEachOther();

    private static final ArchRule RULE_4_COMPOSITION_ROOTS =
            noClasses().that().resideOutsideOfPackages(BOOTSTRAP, API_INTERNAL).should()
                    .callCodeUnitWhere(constructorCallToConcreteAdapter());

    private static final ArchRule RULE_5A_BRAVEHTTP_REFERENCES =
            noClasses().that().resideOutsideOfPackages(BRAVEHTTP, BOOTSTRAP, API_INTERNAL).should()
                    .dependOnClassesThat().resideInAnyPackage(BRAVEHTTP);

    private static final ArchRule RULE_5B_JDK_HTTP_TYPES =
            noClasses().that().resideOutsideOfPackages(BRAVEHTTP).should().dependOnClassesThat()
                    .resideInAnyPackage("java.net.http..");

    private static final ArchRule RULE_6_PROCESS_EXIT =
            noClasses().that().doNotHaveFullyQualifiedName(MAIN_CLASS).should()
                    .callMethodWhere(
                            methodCallTo("java.lang.System", "exit")
                                    .or(methodCallTo("java.lang.Runtime", "exit"))
                                    .or(methodCallTo("java.lang.Runtime", "halt")));

    private static final ArchRule RULE_7_STANDARD_STREAMS =
            noClasses().that().resideOutsideOfPackages(CLI_PRESENTATION, BOOTSTRAP).should()
                    .accessFieldWhere(standardStreamAccess());

    /**
     * Standard input is the console seam's other half: the configuration adapter owns
     * interactive input (its secret reader receives the stream by injection), and bootstrap
     * owns the process wiring that hands it over. Presentation writes and never reads, so a
     * class elsewhere taking {@code System.in} directly bypasses the seam.
     */
    private static final ArchRule RULE_7B_STANDARD_INPUT =
            noClasses().that().resideOutsideOfPackages(BOOTSTRAP, CONFIG).should()
                    .accessFieldWhere(standardInputStreamAccess());

    /**
     * Rule 8 confines the process environment to the configuration adapter and the single
     * terminal detector; the sub-clauses below pin the two adjacent seams of the same
     * boundary. System properties are process configuration just like environment variables,
     * so they are read only where composition wires them or where the environment seam owns
     * them — the {@code java.lang} convenience lookups are property reads in disguise, and
     * property writes mutate the same process state, so both stay behind the same seam; and
     * the API-key variable names are referenced only inside {@code adapter.config}, because a
     * second observer of those names could silently diverge from the resolver's precedence.
     */
    private static final ArchRule RULE_8_ENVIRONMENT_ACCESS =
            noClasses().that(outsideConfigAndTerminalDetector()).should()
                    .callMethodWhere(
                            methodCallTo("java.lang.System", "getenv").or(methodCallTo("java.lang.System", "console")));

    private static final ArchRule RULE_8B_SYSTEM_PROPERTY_SEAM =
            noClasses().that(outsidePropertyOwners()).should()
                    .callMethodWhere(
                            methodCallTo("java.lang.System", "getProperty")
                                    .or(methodCallTo("java.lang.System", "getProperties"))
                                    .or(methodCallTo("java.lang.System", "setProperty"))
                                    .or(methodCallTo("java.lang.System", "setProperties"))
                                    .or(methodCallTo("java.lang.System", "clearProperty"))
                                    .or(methodCallTo("java.lang.Boolean", "getBoolean"))
                                    .or(methodCallTo("java.lang.Integer", "getInteger"))
                                    .or(methodCallTo("java.lang.Long", "getLong")));

    /**
     * The API-key variable names have exactly one home: {@code adapter.config}'s credential
     * source, whose lookups return values — never names — to everyone else. A compile-time
     * {@code String} constant is inlined at its use sites, so the executable form of that
     * confinement is the type boundary: no class outside {@code adapter.config} and the
     * bootstrap composition roots may depend on the credential-source class — depending on it
     * is how a second observer of the variable names is born, and the roots only ever inject
     * its process lookup.
     */
    private static final ArchRule RULE_8C_API_KEY_VARIABLE_CONFINEMENT =
            noClasses().that().resideOutsideOfPackages(CONFIG, BOOTSTRAP).should()
                    .dependOnClassesThat().haveFullyQualifiedName(ENVIRONMENT_CREDENTIAL_SOURCE);

    private static final ArchRule RULE_9_LIBRARY_ISOLATION =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION).should().dependOnClassesThat()
                    .resideInAnyPackage("picocli..", "tools.jackson..", "com.fasterxml.jackson..");

    private static final SliceRule RULE_10_CYCLE_FREE =
            SlicesRuleDefinition.slices().matching(ROOT_PACKAGE + ".(**)").should().beFreeOfCycles();

    private static final ArchRule RULE_11_API_SURFACE =
            classes().that().resideInAnyPackage(API_PACKAGE).should().onlyDependOnClassesThat(apiExportedTypes());

    private static final ArchRule RULE_12A_REFLECT_TYPES =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.lang.invoke..");

    private static final ArchRule RULE_12B_CLASS_FOR_NAME =
            noClasses().should().callMethodWhere(methodCallTo("java.lang.Class", "forName"));

    private static final ArchRule RULE_12C_SERVICE_LOADER =
            noClasses().should().callMethodWhere(
                    methodCallTo("java.util.ServiceLoader", "load")
                            .or(methodCallTo("java.util.ServiceLoader", "loadInstalled")));

    /**
     * Signal handling is process composition: the INT and TERM latches ratified by ADR 0005
     * exist only in the CLI composition root, so no library, command, or adapter code may grow
     * its own signal machinery.
     */
    private static final ArchRule RULE_13_SIGNAL_CONTAINMENT =
            noClasses().that().doNotHaveFullyQualifiedName(MAIN_CLASS).should()
                    .dependOnClassesThat().resideInAnyPackage("sun.misc..");

    /**
     * No production class holds mutable state in a static field: process-wide state lives in
     * instances owned by the composition roots, so a static field is either immutable
     * configuration or a service locator in disguise. Static finals of immutable value are the
     * codebase's norm; anything else fails here — a non-final static fails finality, and a
     * static final of one of the JDK's mutable containers, atomics, or stateful service types
     * fails the type check.
     */
    private static final ArchRule RULE_14_NO_STATIC_MUTABLE_STATE =
            ArchRuleDefinition.fields().that().areStatic().should().beFinal()
                    .andShould(notHoldAKnownMutableContainerType());

    /**
     * Jackson mappers are constructed and acquired only by the three role codecs — the
     * machine-document mapper ({@code JsonMappers}), the tolerant upstream codec
     * ({@code UpstreamJsonCodec}), and the credential-file codec ({@code JsonConfigFile}) —
     * because parser settings on a private mapper can silently change what a role's documents
     * mean. Every acquisition path counts as construction: the builder factories, the public
     * constructors of the mapper and its supertype, rebuilding an existing mapper, and the
     * JVM-global shared instance, which is a fourth codec's settings by another name. Every
     * other class parses through one of the role codecs instead.
     */
    private static final ArchRule RULE_15_MAPPER_CONTAINMENT =
            noClasses().that(doNotHaveAnyFullyQualifiedName(JSON_MAPPERS, UPSTREAM_JSON_CODEC, JSON_CONFIG_FILE))
                    .should()
                    .callCodeUnitWhere(mapperAcquisitionCall());

    /**
     * Production classes only: imported straight from the code source of {@code bootstrap.Main}, so
     * the set is exactly the main source output whether tests run against classes directories or
     * the built jar.
     */
    private static final JavaClasses PRODUCTION_CLASSES = productionClasses();

    /** Union of production classes, ordinary tests, and every architecture fixture. */
    private static final JavaClasses UNION_CLASSES = new ClassFileImporter().importPackages(ROOT_PACKAGE);

    @Test
    void rule1DomainDependsOnNeitherApplicationAdapterNorBootstrap() {
        assertRuleBitesFixture(RULE_1_DOMAIN_ISOLATION, "DomainIsolationFixture", DomainIsolationFixture.class);
        assertRuleBitesInUnion(RULE_1_DOMAIN_ISOLATION, "DomainIsolationFixture");
        assertProductionClean(RULE_1_DOMAIN_ISOLATION);
    }

    @Test
    void rule2ApplicationDependsOnlyOnDomainAndItself() {
        assertRuleBitesFixture(RULE_2_APPLICATION_BOUNDARY, "ApplicationBoundaryFixture", ApplicationBoundaryFixture.class);
        assertRuleBitesInUnion(RULE_2_APPLICATION_BOUNDARY, "ApplicationBoundaryFixture");
        assertProductionClean(RULE_2_APPLICATION_BOUNDARY);
    }

    @Test
    void rule3AdaptersDependOnlyOnDomainApplicationAndNeverOnSiblings() {
        assertRuleBitesFixture(RULE_3A_ADAPTER_DEPENDENCIES, "ApiLeak", CliCrossAdapterFixture.ApiLeak.class);
        assertRuleBitesInUnion(RULE_3A_ADAPTER_DEPENDENCIES, "ApiLeak");
        assertProductionClean(RULE_3A_ADAPTER_DEPENDENCIES);

        assertRuleBitesFixture(
                RULE_3B_NO_SIBLING_ADAPTERS, "CliCrossAdapterFixture", CliCrossAdapterFixture.class, BraveHttpWebSearchGateway.class);
        assertRuleBitesInUnion(RULE_3B_NO_SIBLING_ADAPTERS, "CliCrossAdapterFixture");
        assertProductionClean(RULE_3B_NO_SIBLING_ADAPTERS);
    }

    @Test
    void rule4OnlyBootstrapAndApiInternalInstantiateConcreteAdapters() {
        assertRuleBitesFixture(RULE_4_COMPOSITION_ROOTS, "ViolatesCompositionRoot", ArchFixtures.ViolatesCompositionRoot.class);
        assertRuleBitesInUnion(RULE_4_COMPOSITION_ROOTS, "ViolatesCompositionRoot");
        assertProductionClean(RULE_4_COMPOSITION_ROOTS);

        List<String> isolatedReferences = adapterConstructorReferencesOutsideCompositionRoots(
                new ClassFileImporter().importClasses(ArchFixtures.ViolatesCompositionRootByReference.class));
        assertTrue(
                isolatedReferences.stream().anyMatch(line -> line.contains("ViolatesCompositionRootByReference")),
                () -> "constructor-reference companion does not bite its fixture: " + isolatedReferences);

        List<String> unionReferences = adapterConstructorReferencesOutsideCompositionRoots(UNION_CLASSES);
        assertTrue(
                unionReferences.stream().anyMatch(line -> line.contains("ViolatesCompositionRootByReference")),
                () -> "constructor-reference companion has no violation in the union of production and fixture classes: "
                        + unionReferences);

        List<String> productionReferences = adapterConstructorReferencesOutsideCompositionRoots(PRODUCTION_CLASSES);
        assertTrue(
                productionReferences.isEmpty(),
                () -> "constructor-reference companion flags production code:\n" + productionReferences);

        EvaluationResult siblingWiring = RULE_4_COMPOSITION_ROOTS.evaluate(
                new ClassFileImporter().importClasses(PrefixSiblingType.class, WiresPrefixSiblingType.class));
        assertFalse(
                siblingWiring.hasViolation(),
                () -> "a package that merely extends the adapter prefix's spelling is not an adapter:\n"
                        + siblingWiring.getFailureReport());
    }

    @Test
    void rule5OnlyBravehttpTouchesTheBravehttpPackageAndJdkHttpTypes() {
        assertRuleBitesFixture(RULE_5A_BRAVEHTTP_REFERENCES, "CliCrossAdapterFixture", CliCrossAdapterFixture.class);
        assertRuleBitesInUnion(RULE_5A_BRAVEHTTP_REFERENCES, "CliCrossAdapterFixture");
        assertProductionClean(RULE_5A_BRAVEHTTP_REFERENCES);

        assertRuleBitesFixture(RULE_5B_JDK_HTTP_TYPES, "ViolatesHttpIsolation", ArchFixtures.ViolatesHttpIsolation.class);
        assertRuleBitesInUnion(RULE_5B_JDK_HTTP_TYPES, "ViolatesHttpIsolation");
        assertProductionClean(RULE_5B_JDK_HTTP_TYPES);
    }

    @Test
    void rule6OnlyBootstrapMainTerminatesTheProcess() {
        assertRuleBitesFixture(RULE_6_PROCESS_EXIT, "ViolatesProcessExitOwnership", ArchFixtures.ViolatesProcessExitOwnership.class);
        assertRuleBitesInUnion(RULE_6_PROCESS_EXIT, "ViolatesProcessExitOwnership");
        assertRuleBitesFixture(
                RULE_6_PROCESS_EXIT, "ViolatesRuntimeExitOwnership", ArchFixtures.ViolatesRuntimeExitOwnership.class);
        assertRuleBitesInUnion(RULE_6_PROCESS_EXIT, "ViolatesRuntimeExitOwnership");
        assertProductionClean(RULE_6_PROCESS_EXIT);
    }

    @Test
    void rule7OnlyCliPresentationAndBootstrapAccessStandardStreams() {
        assertRuleBitesFixture(
                RULE_7_STANDARD_STREAMS, "ViolatesStandardStreamsOwnership", ArchFixtures.ViolatesStandardStreamsOwnership.class);
        assertRuleBitesInUnion(RULE_7_STANDARD_STREAMS, "ViolatesStandardStreamsOwnership");
        assertProductionClean(RULE_7_STANDARD_STREAMS);
    }

    @Test
    void rule7bOnlyBootstrapAndConfigTakeStandardInput() {
        assertRuleBitesFixture(
                RULE_7B_STANDARD_INPUT, "ViolatesStandardInputOwnership", ArchFixtures.ViolatesStandardInputOwnership.class);
        assertRuleBitesInUnion(RULE_7B_STANDARD_INPUT, "ViolatesStandardInputOwnership");
        assertProductionClean(RULE_7B_STANDARD_INPUT);
    }

    @Test
    void rule8OnlyAdapterConfigAndTerminalDetectorReadTheEnvironment() {
        assertRuleBitesFixture(
                RULE_8_ENVIRONMENT_ACCESS,
                "ViolatesEnvironmentAccessOwnership",
                ArchFixtures.ViolatesEnvironmentAccessOwnership.class);
        assertRuleBitesInUnion(RULE_8_ENVIRONMENT_ACCESS, "ViolatesEnvironmentAccessOwnership");
        assertRuleBitesFixture(
                RULE_8_ENVIRONMENT_ACCESS, "ViolatesConfigSiblingPrefix", ViolatesConfigSiblingPrefix.class);
        assertRuleBitesInUnion(RULE_8_ENVIRONMENT_ACCESS, "ViolatesConfigSiblingPrefix");
        assertProductionClean(RULE_8_ENVIRONMENT_ACCESS);
    }

    @Test
    void rule8bSystemPropertiesAreReadOnlyThroughThePropertySeam() {
        assertRuleBitesFixture(
                RULE_8B_SYSTEM_PROPERTY_SEAM,
                "ViolatesSystemPropertySeam",
                ArchFixtures.ViolatesSystemPropertySeam.class);
        assertRuleBitesInUnion(RULE_8B_SYSTEM_PROPERTY_SEAM, "ViolatesSystemPropertySeam");
        assertRuleBitesFixture(
                RULE_8B_SYSTEM_PROPERTY_SEAM,
                "ViolatesSystemPropertyAliasRead",
                ArchFixtures.ViolatesSystemPropertyAliasRead.class);
        assertRuleBitesInUnion(RULE_8B_SYSTEM_PROPERTY_SEAM, "ViolatesSystemPropertyAliasRead");
        assertRuleBitesFixture(
                RULE_8B_SYSTEM_PROPERTY_SEAM,
                "ViolatesSystemPropertyWrite",
                ArchFixtures.ViolatesSystemPropertyWrite.class);
        assertRuleBitesInUnion(RULE_8B_SYSTEM_PROPERTY_SEAM, "ViolatesSystemPropertyWrite");
        assertRuleBitesFixture(
                RULE_8B_SYSTEM_PROPERTY_SEAM, "ViolatesConfigSiblingPrefix", ViolatesConfigSiblingPrefix.class);
        assertRuleBitesInUnion(RULE_8B_SYSTEM_PROPERTY_SEAM, "ViolatesConfigSiblingPrefix");
        assertProductionClean(RULE_8B_SYSTEM_PROPERTY_SEAM);
    }

    @Test
    void rule8cOnlyAdapterConfigReferencesTheApiKeyVariableNames() {
        assertRuleBitesFixture(
                RULE_8C_API_KEY_VARIABLE_CONFINEMENT,
                "ViolatesApiKeyVariableConfinement",
                ArchFixtures.ViolatesApiKeyVariableConfinement.class);
        assertRuleBitesInUnion(RULE_8C_API_KEY_VARIABLE_CONFINEMENT, "ViolatesApiKeyVariableConfinement");
        assertProductionClean(RULE_8C_API_KEY_VARIABLE_CONFINEMENT);
    }

    @Test
    void rule9DomainAndApplicationAvoidPicocliAndJackson() {
        assertRuleBitesFixture(RULE_9_LIBRARY_ISOLATION, "ApplicationBoundaryFixture", ApplicationBoundaryFixture.class);
        assertRuleBitesInUnion(RULE_9_LIBRARY_ISOLATION, "ApplicationBoundaryFixture");
        assertProductionClean(RULE_9_LIBRARY_ISOLATION);
    }

    @Test
    void rule10PackagesAreCycleFree() {
        assertRuleBitesFixture(RULE_10_CYCLE_FREE, "CyclePartner", ArchFixtures.CycleSource.class, CyclePartner.class);
        assertRuleBitesInUnion(RULE_10_CYCLE_FREE, "CyclePartner");
        assertProductionClean(RULE_10_CYCLE_FREE);

        List<String> isolatedIntraPackage = intraPackageTopLevelClassCycles(
                new ClassFileImporter().importClasses(IntraPackageCycleSource.class, IntraPackageCyclePartner.class));
        assertTrue(
                isolatedIntraPackage.stream().anyMatch(line -> line.contains("IntraPackageCycleSource")),
                () -> "class-level companion does not bite its fixture: " + isolatedIntraPackage);

        List<String> unionIntraPackage = intraPackageTopLevelClassCycles(UNION_CLASSES);
        assertTrue(
                unionIntraPackage.stream().anyMatch(line -> line.contains("IntraPackageCycleSource")),
                () -> "class-level companion has no violation in the union of production and fixture classes: "
                        + unionIntraPackage);

        List<String> isolatedNestedLeg = intraPackageTopLevelClassCycles(
                new ClassFileImporter().importClasses(
                        IntraPackageNestedCycleSource.class,
                        IntraPackageNestedCyclePartner.class,
                        IntraPackageNestedCyclePartner.Builder.class));
        assertTrue(
                isolatedNestedLeg.stream().anyMatch(line -> line.contains("IntraPackageNestedCycleSource")),
                () -> "class-level companion does not bite the nested-leg fixture: " + isolatedNestedLeg);

        assertTrue(
                unionIntraPackage.stream().anyMatch(line -> line.contains("IntraPackageNestedCycleSource")),
                () -> "class-level companion misses the nested-leg fixture in the union of production and fixture "
                        + "classes: " + unionIntraPackage);

        List<String> productionIntraPackage = intraPackageTopLevelClassCycles(PRODUCTION_CLASSES);
        assertTrue(
                productionIntraPackage.isEmpty(),
                () -> "class-level companion flags production code:\n" + productionIntraPackage);
    }

    @Test
    void rule11ApiReferencesOnlyApiDomainAndAllowlistedExports() {
        assertRuleBitesFixture(RULE_11_API_SURFACE, "ApiSurfaceFixture", ApiSurfaceFixture.class);
        assertRuleBitesInUnion(RULE_11_API_SURFACE, "ApiSurfaceFixture");
        assertProductionClean(RULE_11_API_SURFACE);
    }

    @Test
    void rule12NoProductionPackageUsesReflectionDirectly() {
        assertRuleBitesFixture(RULE_12A_REFLECT_TYPES, "ViolatesReflectionBan", ArchFixtures.ViolatesReflectionBan.class);
        assertRuleBitesInUnion(RULE_12A_REFLECT_TYPES, "ViolatesReflectionBan");
        assertProductionClean(RULE_12A_REFLECT_TYPES);

        assertRuleBitesFixture(RULE_12A_REFLECT_TYPES, "ViolatesMethodHandleUse", ArchFixtures.ViolatesMethodHandleUse.class);
        assertRuleBitesInUnion(RULE_12A_REFLECT_TYPES, "ViolatesMethodHandleUse");

        assertRuleBitesFixture(RULE_12B_CLASS_FOR_NAME, "ViolatesReflectionBan", ArchFixtures.ViolatesReflectionBan.class);
        assertRuleBitesInUnion(RULE_12B_CLASS_FOR_NAME, "ViolatesReflectionBan");
        assertProductionClean(RULE_12B_CLASS_FOR_NAME);

        assertRuleBitesFixture(RULE_12C_SERVICE_LOADER, "ViolatesServiceLoaderUse", ArchFixtures.ViolatesServiceLoaderUse.class);
        assertRuleBitesInUnion(RULE_12C_SERVICE_LOADER, "ViolatesServiceLoaderUse");
        assertRuleBitesFixture(
                RULE_12C_SERVICE_LOADER,
                "ViolatesServiceLoaderInstalledUse",
                ArchFixtures.ViolatesServiceLoaderInstalledUse.class);
        assertRuleBitesInUnion(RULE_12C_SERVICE_LOADER, "ViolatesServiceLoaderInstalledUse");
        assertProductionClean(RULE_12C_SERVICE_LOADER);
    }

    @Test
    void rule13OnlyBootstrapMainTouchesSignalMachinery() {
        assertRuleBitesFixture(RULE_13_SIGNAL_CONTAINMENT, "ViolatesSignalOwnership", ArchFixtures.ViolatesSignalOwnership.class);
        assertRuleBitesInUnion(RULE_13_SIGNAL_CONTAINMENT, "ViolatesSignalOwnership");
        assertProductionClean(RULE_13_SIGNAL_CONTAINMENT);
    }

    @Test
    void rule14NoProductionClassHoldsMutableStaticState() {
        assertRuleBitesFixture(
                RULE_14_NO_STATIC_MUTABLE_STATE,
                "ViolatesNoStaticMutableState",
                ArchFixtures.ViolatesNoStaticMutableState.class);
        assertRuleBitesInUnion(RULE_14_NO_STATIC_MUTABLE_STATE, "ViolatesNoStaticMutableState");
        assertRuleBitesFixture(
                RULE_14_NO_STATIC_MUTABLE_STATE,
                "ViolatesStaticMutableContainer",
                ArchFixtures.ViolatesStaticMutableContainer.class);
        assertRuleBitesInUnion(RULE_14_NO_STATIC_MUTABLE_STATE, "ViolatesStaticMutableContainer");
        assertRuleBitesFixture(
                RULE_14_NO_STATIC_MUTABLE_STATE,
                "ViolatesStaticMutableService",
                ArchFixtures.ViolatesStaticMutableService.class);
        assertRuleBitesInUnion(RULE_14_NO_STATIC_MUTABLE_STATE, "ViolatesStaticMutableService");
        assertProductionClean(RULE_14_NO_STATIC_MUTABLE_STATE);
    }

    @Test
    void rule15OnlyTheThreeRoleCodecsConstructJacksonMappers() {
        assertRuleBitesFixture(
                RULE_15_MAPPER_CONTAINMENT, "ViolatesMapperContainment", ArchFixtures.ViolatesMapperContainment.class);
        assertRuleBitesInUnion(RULE_15_MAPPER_CONTAINMENT, "ViolatesMapperContainment");
        assertRuleBitesFixture(
                RULE_15_MAPPER_CONTAINMENT,
                "ViolatesMapperConstructorContainment",
                ArchFixtures.ViolatesMapperConstructorContainment.class);
        assertRuleBitesInUnion(RULE_15_MAPPER_CONTAINMENT, "ViolatesMapperConstructorContainment");
        assertRuleBitesFixture(
                RULE_15_MAPPER_CONTAINMENT,
                "ViolatesObjectMapperConstructorContainment",
                ArchFixtures.ViolatesObjectMapperConstructorContainment.class);
        assertRuleBitesInUnion(RULE_15_MAPPER_CONTAINMENT, "ViolatesObjectMapperConstructorContainment");
        assertRuleBitesFixture(
                RULE_15_MAPPER_CONTAINMENT,
                "ViolatesSharedMapperContainment",
                ArchFixtures.ViolatesSharedMapperContainment.class);
        assertRuleBitesInUnion(RULE_15_MAPPER_CONTAINMENT, "ViolatesSharedMapperContainment");
        assertProductionClean(RULE_15_MAPPER_CONTAINMENT);

        List<String> isolatedMapperReferences = mapperAcquisitionReferencesOutsideRoleCodecs(
                new ClassFileImporter().importClasses(ArchFixtures.ViolatesMapperReferenceContainment.class));
        assertTrue(
                isolatedMapperReferences.stream().anyMatch(line -> line.contains("ViolatesMapperReferenceContainment")),
                () -> "constructor-reference companion does not bite its fixture: " + isolatedMapperReferences);

        List<String> isolatedMapperMethodReferences = mapperAcquisitionReferencesOutsideRoleCodecs(
                new ClassFileImporter().importClasses(ArchFixtures.ViolatesMapperMethodReferenceContainment.class));
        assertTrue(
                isolatedMapperMethodReferences.stream()
                        .anyMatch(line -> line.contains("ViolatesMapperMethodReferenceContainment")),
                () -> "method-reference companion does not bite its fixture: " + isolatedMapperMethodReferences);

        List<String> unionMapperReferences = mapperAcquisitionReferencesOutsideRoleCodecs(UNION_CLASSES);
        assertTrue(
                unionMapperReferences.stream().anyMatch(line -> line.contains("ViolatesMapperReferenceContainment")),
                () -> "constructor-reference companion has no violation in the union of production and fixture classes: "
                        + unionMapperReferences);
        assertTrue(
                unionMapperReferences.stream()
                        .anyMatch(line -> line.contains("ViolatesMapperMethodReferenceContainment")),
                () -> "method-reference companion has no violation in the union of production and fixture classes: "
                        + unionMapperReferences);

        List<String> productionMapperReferences = mapperAcquisitionReferencesOutsideRoleCodecs(PRODUCTION_CLASSES);
        assertTrue(
                productionMapperReferences.isEmpty(),
                () -> "acquisition-reference companion flags production code:\n" + productionMapperReferences);
    }

    private static DescribedPredicate<JavaMethodCall> methodCallTo(String ownerName, String methodName) {
        return DescribedPredicate.describe(
                "call " + ownerName + "." + methodName + "(..)",
                call -> call.getTargetOwner().getName().equals(ownerName) && call.getName().equals(methodName));
    }

    /**
     * Every call through which a class acquires a Jackson mapper of its own. Constructor calls
     * need the code-unit form: ArchUnit records them as constructor calls, which the
     * method-call predicate never sees.
     */
    private static DescribedPredicate<JavaCall<?>> mapperAcquisitionCall() {
        return codeUnitCallTo(JACKSON_MAPPER, "builder")
                .or(codeUnitCallTo(JACKSON_MAPPER, "builderWithJackson2Defaults"))
                .or(codeUnitCallTo(JACKSON_MAPPER, "rebuild"))
                .or(codeUnitCallTo(JACKSON_MAPPER, "shared"))
                .or(codeUnitCallTo(JACKSON_MAPPER, "<init>"))
                .or(codeUnitCallTo(JACKSON_OBJECT_MAPPER, "rebuild"))
                .or(codeUnitCallTo(JACKSON_OBJECT_MAPPER, "<init>"));
    }

    private static DescribedPredicate<JavaCall<?>> codeUnitCallTo(String ownerName, String methodName) {
        return DescribedPredicate.describe(
                "call " + ownerName + "." + methodName + "(..)",
                call -> call.getTargetOwner().getName().equals(ownerName) && call.getName().equals(methodName));
    }

    private static DescribedPredicate<JavaFieldAccess> standardStreamAccess() {
        return DescribedPredicate.describe(
                "access System.out or System.err",
                access -> access.getTargetOwner().getName().equals("java.lang.System")
                        && ("out".equals(access.getName()) || "err".equals(access.getName())));
    }

    private static DescribedPredicate<JavaFieldAccess> standardInputStreamAccess() {
        return DescribedPredicate.describe(
                "access System.in",
                access -> access.getTargetOwner().getName().equals("java.lang.System")
                        && "in".equals(access.getName()));
    }

    /**
     * Exact package residency: a class resides in {@code packagePrefix} only when its package
     * is the prefix itself or one of its children, so sibling packages that merely extend the
     * prefix's spelling never inherit the prefix's role.
     */
    private static boolean residesInPackage(JavaClass candidate, String packagePrefix) {
        String packageName = candidate.getPackageName();
        return packageName.equals(packagePrefix) || packageName.startsWith(packagePrefix + ".");
    }

    private static DescribedPredicate<JavaClass> outsideConfigAndTerminalDetector() {
        return DescribedPredicate.not(
                DescribedPredicate.describe("reside in " + CONFIG + " or be the terminal detector", javaClass ->
                        residesInPackage(javaClass, CONFIG_PREFIX)
                                || javaClass.getName().equals(TERMINAL_DETECTOR)));
    }

    /** The classes that may touch system properties: the two environment owners plus bootstrap. */
    private static DescribedPredicate<JavaClass> outsidePropertyOwners() {
        return DescribedPredicate.not(DescribedPredicate.describe(
                "reside in " + CONFIG + ", be the terminal detector, or reside in " + BOOTSTRAP, javaClass ->
                        residesInPackage(javaClass, CONFIG_PREFIX)
                                || javaClass.getName().equals(TERMINAL_DETECTOR)
                                || residesInPackage(javaClass, BOOTSTRAP_PREFIX)));
    }

    private static DescribedPredicate<JavaClass> doNotHaveAnyFullyQualifiedName(String... names) {
        Set<String> allowed = Set.of(names);
        return DescribedPredicate.not(DescribedPredicate.describe(
                "have one of the role fully qualified names", javaClass -> allowed.contains(javaClass.getName())));
    }

    private static DescribedPredicate<JavaCall<?>> constructorCallToConcreteAdapter() {
        return DescribedPredicate.describe(
                "instantiate a concrete adapter class",
                call -> "<init>".equals(call.getName()) && isConcreteAdapter(call.getTargetOwner()));
    }

    private static boolean isConcreteAdapter(JavaClass candidate) {
        return residesInPackage(candidate, ADAPTER_PREFIX)
                && !candidate.isInterface()
                && !candidate.getModifiers().contains(JavaModifier.ABSTRACT);
    }

    /**
     * Companion to {@link #RULE_4_COMPOSITION_ROOTS}: ArchUnit's call-based rule cannot see
     * constructor references such as {@code BraveHttpWebSearchGateway::new}, because those compile
     * to constant-pool method handles rather than invokespecial calls. This check walks the
     * recorded constructor references of every class outside the composition roots and reports each
     * one whose target is a concrete adapter.
     */
    private static List<String> adapterConstructorReferencesOutsideCompositionRoots(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (resideInAnyPackage(BOOTSTRAP, API_INTERNAL).test(javaClass)) {
                continue;
            }
            for (JavaConstructorReference reference : javaClass.getConstructorReferencesFromSelf()) {
                if (isConcreteAdapter(reference.getTarget().getOwner())) {
                    violations.add(javaClass.getName() + " references the constructor of concrete adapter "
                            + reference.getTarget().getOwner().getName());
                }
            }
        }
        return violations;
    }

    /**
     * Companion to {@link #RULE_15_MAPPER_CONTAINMENT}: ArchUnit's call-based rule cannot see
     * references such as {@code JsonMapper::new} or {@code JsonMapper::shared}, because those
     * compile to constant-pool method handles rather than invokespecial and invokestatic
     * calls. This check walks the recorded constructor and method references of every class
     * outside the three role codecs and reports each one whose target is the Jackson mapper,
     * its supertype, or one of the factory methods that acquire a mapper.
     */
    private static List<String> mapperAcquisitionReferencesOutsideRoleCodecs(JavaClasses classes) {
        Set<String> roleCodecs = Set.of(JSON_MAPPERS, UPSTREAM_JSON_CODEC, JSON_CONFIG_FILE);
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (roleCodecs.contains(javaClass.getName())) {
                continue;
            }
            for (JavaConstructorReference reference : javaClass.getConstructorReferencesFromSelf()) {
                String owner = reference.getTarget().getOwner().getName();
                if (JACKSON_MAPPER.equals(owner) || JACKSON_OBJECT_MAPPER.equals(owner)) {
                    violations.add(javaClass.getName() + " references the constructor of " + owner);
                }
            }
            for (JavaMethodReference reference : javaClass.getMethodReferencesFromSelf()) {
                String owner = reference.getTarget().getOwner().getName();
                String method = reference.getTarget().getName();
                if ((JACKSON_MAPPER.equals(owner) || JACKSON_OBJECT_MAPPER.equals(owner))
                        && MAPPER_ACQUISITION_METHODS.contains(method)) {
                    violations.add(javaClass.getName() + " references " + owner + "." + method);
                }
            }
        }
        return violations;
    }

    /**
     * Companion to {@link #RULE_10_CYCLE_FREE}: the slice rule proves the package graph
     * acyclic, but two top-level classes of one and the same package can still depend on
     * each other circularly without ever forming a package cycle. This walk reports exactly
     * those cycles: it builds the class dependency graph of every top-level class — a class
     * and its own nested types (its builders and variants, binary names holding {@code $})
     * are one type's implementation, so every nested type contributes its dependencies to
     * its top-level owner and every {@code $}-shaped target counts as its owner — finds the
     * strongly connected components, and reports each component whose members all share one
     * package. A cycle routed through a nested type is therefore as visible as a direct one.
     */
    private static List<String> intraPackageTopLevelClassCycles(JavaClasses classes) {
        Map<String, Set<String>> dependencies = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            String owner = topLevelOwnerOf(javaClass.getName());
            Set<String> targets = dependencies.computeIfAbsent(owner, ignored -> new TreeSet<>());
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                targets.add(topLevelOwnerOf(dependency.getTargetClass().getName()));
            }
            targets.remove(owner);
        }
        List<String> cycles = new ArrayList<>();
        for (List<String> component : stronglyConnectedComponents(dependencies)) {
            String packageName = packageNameOf(component.get(0));
            if (component.stream().allMatch(name -> packageNameOf(name).equals(packageName))) {
                cycles.add("cycle inside " + packageName + ": " + String.join(" -> ", component));
            }
        }
        return cycles;
    }

    private static List<List<String>> stronglyConnectedComponents(Map<String, Set<String>> dependencies) {
        List<List<String>> components = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        Map<String, Integer> lowest = new HashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] nextIndex = {0};
        for (String origin : dependencies.keySet()) {
            if (!order.containsKey(origin)) {
                collectComponent(origin, dependencies, order, lowest, pending, onStack, nextIndex, components);
            }
        }
        return components;
    }

    private static void collectComponent(
            String node,
            Map<String, Set<String>> dependencies,
            Map<String, Integer> order,
            Map<String, Integer> lowest,
            Deque<String> pending,
            Set<String> onStack,
            int[] nextIndex,
            List<List<String>> components) {
        order.put(node, nextIndex[0]);
        lowest.put(node, nextIndex[0]);
        nextIndex[0]++;
        pending.push(node);
        onStack.add(node);
        for (String successor : dependencies.get(node)) {
            if (!dependencies.containsKey(successor)) {
                continue;
            }
            if (!order.containsKey(successor)) {
                collectComponent(successor, dependencies, order, lowest, pending, onStack, nextIndex, components);
                lowest.put(node, Math.min(lowest.get(node), lowest.get(successor)));
            } else if (onStack.contains(successor)) {
                lowest.put(node, Math.min(lowest.get(node), order.get(successor)));
            }
        }
        if (lowest.get(node).equals(order.get(node))) {
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = pending.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            if (component.size() > 1) {
                components.add(component);
            }
        }
    }

    /**
     * The top-level owner of a binary class name: {@code X$Builder}, {@code X$1}, and any
     * deeper nesting belong to {@code X}, whose implementation they are.
     */
    private static String topLevelOwnerOf(String className) {
        int nestedMarker = className.indexOf('$');
        return nestedMarker < 0 ? className : className.substring(0, nestedMarker);
    }

    private static String packageNameOf(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    private static ArchCondition<JavaField> notHoldAKnownMutableContainerType() {
        return new ArchCondition<>("not hold a known-mutable container type") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                String typeName = field.getRawType().getName();
                if (MUTABLE_STATIC_FIELD_TYPES.contains(typeName)) {
                    events.add(SimpleConditionEvent.violated(
                            field,
                            field.getFullName() + " holds the mutable type " + typeName + " in a static field"));
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> apiExportedTypes() {
        return DescribedPredicate.describe(
                        "be an allowlisted exported type",
                        (JavaClass javaClass) -> API_EXPORT_ALLOWLIST.contains(javaClass.getName()))
                .or(resideInAnyPackage(API_HIERARCHY, DOMAIN, "java..", "jdk.."));
    }

    private static void assertRuleBitesFixture(ArchRule rule, String fixtureSimpleName, Class<?>... fixtureClasses) {
        EvaluationResult isolated = rule.evaluate(new ClassFileImporter().importClasses(fixtureClasses));
        assertTrue(
                isolated.hasViolation(),
                () -> "rule does not bite its fixture " + fixtureSimpleName + ": " + rule.getDescription());
        assertTrue(
                isolated.getFailureReport().toString().contains(fixtureSimpleName),
                () -> "failure report does not name " + fixtureSimpleName + ":\n" + isolated.getFailureReport());
    }

    private static void assertRuleBitesInUnion(ArchRule rule, String fixtureSimpleName) {
        EvaluationResult union = rule.evaluate(UNION_CLASSES);
        assertTrue(
                union.hasViolation(),
                () -> "rule has no violation in the union of production and fixture classes: " + rule.getDescription());
        assertTrue(
                union.getFailureReport().toString().contains(fixtureSimpleName),
                () -> "union failure report does not name " + fixtureSimpleName + ":\n" + union.getFailureReport());
    }

    private static void assertProductionClean(ArchRule rule) {
        EvaluationResult production = rule.evaluate(PRODUCTION_CLASSES);
        assertFalse(
                production.hasViolation(),
                () -> rule.getDescription() + " flags production code:\n" + production.getFailureReport());
    }

    private static JavaClasses productionClasses() {
        JavaClasses imported = new ClassFileImporter()
                .importLocations(List.of(Location.of(Main.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation())));
        if (imported.isEmpty()) {
            throw new IllegalStateException(
                    "the production class import resolved empty: the code source of bootstrap.Main must point at "
                            + "the main classes output, otherwise the production-clean assertions vacuously pass");
        }
        return imported;
    }
}
