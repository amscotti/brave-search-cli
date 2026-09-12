package io.amscotti.bravesearch.adapter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Environment reads stay hermetic: every value flows through the injected lookup, and the
 * both-variables report carries presence only, never a value.
 */
final class EnvironmentCredentialSourceTest {

    @Test
    void canonicalAndAliasFlowThroughTheInjectedLookup() {
        EnvironmentCredentialSource source =
                environment(Map.of("BRAVE_API_KEY", "canonical", "BRAVE_SEARCH_API_KEY", "alias"));
        assertEquals(Optional.of("canonical"), source.canonical());
        assertEquals(Optional.of("alias"), source.alias());
    }

    @Test
    void absentVariablesYieldEmptyOptionals() {
        EnvironmentCredentialSource source = environment(Map.of());
        assertTrue(source.canonical().isEmpty());
        assertTrue(source.alias().isEmpty());
    }

    @Test
    void aliasShadowedRequiresBothVariables() {
        assertTrue(
                environment(Map.of("BRAVE_API_KEY", "canonical", "BRAVE_SEARCH_API_KEY", "alias"))
                        .aliasShadowed());
        assertFalse(environment(Map.of("BRAVE_SEARCH_API_KEY", "alias")).aliasShadowed());
        assertFalse(environment(Map.of("BRAVE_API_KEY", "canonical")).aliasShadowed());
        assertFalse(environment(Map.of()).aliasShadowed());
    }

    @Test
    void lookupsQueryExactlyTheCredentialVariableNames() {
        Set<String> queried = new HashSet<>();
        Map<String, String> variables = Map.of("BRAVE_API_KEY", "canonical", "BRAVE_SEARCH_API_KEY", "alias");
        EnvironmentCredentialSource source =
                new EnvironmentCredentialSource(name -> {
                    queried.add(name);
                    return variables.get(name);
                });
        source.canonical();
        source.alias();
        source.aliasShadowed();
        assertEquals(Set.of("BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY"), queried);
    }

    private static EnvironmentCredentialSource environment(Map<String, String> variables) {
        return new EnvironmentCredentialSource(variables::get);
    }
}
