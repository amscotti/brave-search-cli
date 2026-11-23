package io.amscotti.bravesearch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The public surface of the library, executed: the facade is composed only through its
 * builder, so adding an endpoint never breaks a public signature; and the internal types the
 * public {@code api} package reaches — directly or transitively — are exactly the pinned
 * allowlist. Widening the library's contract is a deliberate act that fails here until the
 * allowlist and {@code docs/java-api.md} agree.
 */
final class PublicApiSurfaceTest {

    private static final String ROOT = "io.amscotti.bravesearch";

    private static final String API = ROOT + ".api";

    /**
     * Every top-level domain type the public API reaches, package-relative. A new entry is a
     * contract change: name it here and in the compatibility section of
     * {@code docs/java-api.md} in the same change.
     */
    private static final Set<String> REACHED_DOMAIN_TYPES = Set.of(
            "domain.answer.AnswerStreamEvent",
            "domain.config.Credential",
            "domain.config.InvalidCredentialException",
            "domain.error.ClassifiedFailure",
            "domain.error.FailureKind",
            "domain.error.Outcome",
            "domain.error.UpstreamError",
            "domain.error.UsageValidationError",
            "domain.goggles.Goggle",
            "domain.metadata.RateLimitSnapshot",
            "domain.metadata.RateLimitWindow",
            "domain.metadata.UpstreamPayload",
            "domain.metadata.Usage",
            "domain.request.AnswersRequest",
            "domain.request.CommonSearchBinding",
            "domain.request.ContextRequest",
            "domain.request.ContextThreshold",
            "domain.request.CountryCode",
            "domain.request.DecimalRules",
            "domain.request.Freshness",
            "domain.request.GeoLoc",
            "domain.request.ImageSafeSearch",
            "domain.request.ImageSearchRequest",
            "domain.request.LocationRules",
            "domain.request.NewsSearchRequest",
            "domain.request.PlaceAnchor",
            "domain.request.PlaceEnrichmentRequest",
            "domain.request.PlaceSearchRequest",
            "domain.request.QueryText",
            "domain.request.RichRequest",
            "domain.request.SafeSearch",
            "domain.request.SpellcheckRequest",
            "domain.request.SuggestRequest",
            "domain.request.Units",
            "domain.request.VideoSearchRequest",
            "domain.request.WebSearchRequest",
            "domain.result.AnswersResult",
            "domain.result.ContextResult",
            "domain.result.ImageSearchResult",
            "domain.result.NewsSearchResult",
            "domain.result.PagedExchange",
            "domain.result.PlaceEnrichmentResult",
            "domain.result.PlaceSearchResult",
            "domain.result.RichResult",
            "domain.result.SpellcheckSearchResult",
            "domain.result.SuggestSearchResult",
            "domain.result.VideoSearchResult",
            "domain.result.WebSearchResult");

    @Test
    void theFacadeIsComposedOnlyThroughItsBuilder() {
        List<String> publicConstructors = Arrays.stream(BraveSearchClient.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .map(constructor -> constructor.getParameterCount() + " parameter(s)")
                .toList();

        assertTrue(
                publicConstructors.isEmpty(),
                () -> "the facade has no public constructor: a public composition signature would break on every"
                        + " added endpoint, but these are public: " + publicConstructors);
    }

    @Test
    void theDomainTypesThePublicApiReachesAreExactlyTheAllowlistedOnes() {
        Set<String> reached = reachedInternalTypes();

        assertEquals(
                REACHED_DOMAIN_TYPES,
                reached,
                () -> "the public API's domain reach changed; pin it deliberately together with docs/java-api.md: "
                        + difference(REACHED_DOMAIN_TYPES, reached));
    }

    @Test
    void thePublicApiNeverReachesTheCliVocabularyOrAnApplicationType() {
        Set<String> reached = reachedInternalTypes();

        assertFalse(
                reached.stream().anyMatch(name -> name.startsWith("domain.output.")),
                () -> "CLI output vocabulary reached from the library: " + reached);
        assertFalse(
                reached.stream().anyMatch(name -> name.startsWith("config.Config")),
                () -> "CLI configuration views reached from the library: " + reached);
        assertTrue(
                reached.stream().noneMatch(name -> name.startsWith("application.")),
                () -> "no application type belongs to the public API's reach: " + reached);
    }

    /**
     * The top-level internal project types reachable from the production classes of the public
     * api package, transitively; a nested type counts as its owner.
     */
    private static Set<String> reachedInternalTypes() {
        Deque<JavaClass> pending = new ArrayDeque<>();
        for (JavaClass candidate : productionClasses()) {
            if (candidate.getPackageName().equals(API)) {
                pending.add(candidate);
            }
        }
        Set<String> seen = new TreeSet<>();
        Set<String> reached = new TreeSet<>();
        while (!pending.isEmpty()) {
            JavaClass current = pending.removeFirst();
            if (!seen.add(current.getName())) {
                continue;
            }
            for (JavaClass target : current.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                String topLevel = topLevelOwnerOf(target.getName());
                boolean internal =
                        topLevel.startsWith(ROOT + ".domain.") || topLevel.startsWith(ROOT + ".application.");
                if (internal) {
                    reached.add(topLevel.substring(ROOT.length() + 1));
                    pending.add(target);
                }
            }
        }
        return reached;
    }

    /** The top-level owner of a binary class name: {@code X$Builder} is part of {@code X}. */
    private static String topLevelOwnerOf(String className) {
        int nestedMarker = className.indexOf('$');
        return nestedMarker < 0 ? className : className.substring(0, nestedMarker);
    }

    /** What the two sets disagree about: the types only the allowlist names and only the reach contains. */
    private static String difference(Set<String> allowlisted, Set<String> reached) {
        Set<String> onlyAllowlisted = new TreeSet<>(allowlisted);
        onlyAllowlisted.removeAll(reached);
        Set<String> onlyReached = new TreeSet<>(reached);
        onlyReached.removeAll(allowlisted);
        return "pinned but unreachable " + onlyAllowlisted + "; reached but unpinned " + onlyReached;
    }

    /** Production classes only, imported from the code source of the facade itself. */
    private static JavaClasses productionClasses() {
        JavaClasses imported = new ClassFileImporter()
                .importLocations(List.of(Location.of(BraveSearchClient.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation())));
        if (imported.isEmpty()) {
            throw new IllegalStateException("the production class import resolved empty");
        }
        return imported;
    }
}
