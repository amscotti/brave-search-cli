package io.amscotti.bravesearch.adapter.cli.option;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.bravesearch.domain.error.UsageValidationError;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The complement guard of the shared search options: a command accepts shared spellings by
 * naming them, and every other supplied spelling is a usage error. The inventory the guard
 * walks is pinned to the mixin's annotated grammar by picocli's own model, so a spelling
 * added to the mixin lands in the inventory or the build fails, and the inventory-wide
 * supply matrix proves each spelling is rejected until a command explicitly accepts it —
 * the default is rejection, which is what keeps a future shared spelling from being
 * silently swallowed by commands that never named it.
 */
final class CommonSearchOptionsTest {

    /** The shared spellings the context endpoint documents; the accepted list a command names. */
    private static final String[] CONTEXT_ACCEPTED = {"--country", "--search-lang", "--safe-search", "--freshness", "--count"};

    /** One valid supplied form per inventory spelling, so the matrix below can supply each one. */
    private static final Map<String, String[]> SUPPLIED_FORM =
            Map.of(
                    "--country", new String[] {"--country", "DE"},
                    "--search-lang", new String[] {"--search-lang", "en"},
                    "--ui-lang", new String[] {"--ui-lang", "en"},
                    "--safe-search", new String[] {"--safe-search", "strict"},
                    "--freshness", new String[] {"--freshness", "pd"},
                    "--count", new String[] {"--count", "7"},
                    "--page", new String[] {"--page", "2"},
                    "--spellcheck", new String[] {"--spellcheck"});

    @Test
    void inventoryEqualsTheMixinsAnnotatedOptionGrammar() {
        CommandLine commandLine = new CommandLine(new SharedOptionsProbe(new CommonSearchOptions()));

        Set<String> grammar = new TreeSet<>();
        for (OptionSpec option : commandLine.getCommandSpec().options()) {
            grammar.addAll(Arrays.asList(option.names()));
        }

        assertEquals(new TreeSet<>(CommonSearchOptions.ALL_OPTION_NAMES), grammar, "the inventory must be exactly the annotated spellings picocli accepts");
    }

    @Test
    void freshOptionsRejectNothingEvenWithAnEmptyAcceptedList() {
        CommonSearchOptions fresh = new CommonSearchOptions();

        assertDoesNotThrow(() -> fresh.rejectUndocumentedExcept(), "an unsupplied invocation rejects nothing");
        assertDoesNotThrow(() -> fresh.rejectUndocumentedExcept(CONTEXT_ACCEPTED), "an unsupplied invocation rejects nothing");
    }

    @Test
    void everyInventorySpellingSuppliedIsRejectedWhenNotAccepted() {
        for (String name : CommonSearchOptions.ALL_OPTION_NAMES) {
            CommonSearchOptions supplied = parse(suppliedFormOf(name));

            UsageValidationError rejected =
                    assertThrows(UsageValidationError.class, () -> supplied.rejectUndocumentedExcept(), () -> name + " must be rejected by default");
            assertEquals(name + " is not accepted by this command", rejected.getMessage(), () -> name + " is named alone when supplied alone");
        }
    }

    @Test
    void everyInventorySpellingSuppliedPassesWhenExplicitlyAccepted() {
        for (String name : CommonSearchOptions.ALL_OPTION_NAMES) {
            CommonSearchOptions supplied = parse(suppliedFormOf(name));

            assertDoesNotThrow(() -> supplied.rejectUndocumentedExcept(name), () -> name + " passes when the command names it");
        }
    }

    @Test
    void everyUndocumentedSpellingSuppliedTogetherIsNamedInInventoryOrder() {
        CommonSearchOptions supplied = parse(
                "--country", "DE",
                "--search-lang", "en",
                "--ui-lang", "en",
                "--safe-search", "strict",
                "--freshness", "pd",
                "--count", "7",
                "--page", "2",
                "--spellcheck");

        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> supplied.rejectUndocumentedExcept(CONTEXT_ACCEPTED));

        assertEquals("--ui-lang, --page, --spellcheck are not accepted by this command", rejected.getMessage());
    }

    @Test
    void anAcceptedListSpellingKeepsWorkingWhenTheOthersAreRejected() {
        CommonSearchOptions supplied = parse("--count", "7", "--page", "2");

        UsageValidationError rejected =
                assertThrows(UsageValidationError.class, () -> supplied.rejectUndocumentedExcept(CONTEXT_ACCEPTED));

        assertEquals("--page is not accepted by this command", rejected.getMessage());
        assertTrue(rejected.getMessage().endsWith("this command"));
    }

    private static String[] suppliedFormOf(String name) {
        String[] form = SUPPLIED_FORM.get(name);
        assertTrue(form != null, () -> "every inventory spelling needs a supplied form here: add one for " + name);
        return form;
    }

    private static CommonSearchOptions parse(String... arguments) {
        CommonSearchOptions commonOptions = new CommonSearchOptions();
        new CommandLine(new SharedOptionsProbe(commonOptions)).parseArgs(arguments);
        return commonOptions;
    }

    /** Hosts the mixin exactly the way a command does, so picocli's own model can be enumerated. */
    @Command(name = "shared-options-probe")
    static final class SharedOptionsProbe {

        @Mixin
        CommonSearchOptions commonOptions;

        SharedOptionsProbe(CommonSearchOptions commonOptions) {
            this.commonOptions = commonOptions;
        }
    }
}
