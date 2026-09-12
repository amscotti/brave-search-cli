package io.amscotti.bravesearch.adapter.cli.option;

import java.util.Iterator;
import java.util.List;

/**
 * The completion candidates of {@code --threshold}: exactly the converter's own lowercase
 * wire tokens, so a shell completion offers only spellings the command accepts — never the
 * enum's uppercase constant names, which the generator would otherwise invent.
 */
public final class ContextThresholdCandidates implements Iterable<String> {

    private static final List<String> TOKENS = List.of("strict", "balanced", "lenient", "disabled");

    @Override
    public Iterator<String> iterator() {
        return TOKENS.iterator();
    }
}
