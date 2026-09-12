package io.amscotti.bravesearch.adapter.cli.command.completion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the completion generator's script to one byte order. The generator emits each
 * command's options in the model's own order, and that order originates in JVM reflection
 * over the annotated option methods, which the platform leaves unspecified: the same
 * grammar can print differently in different Java runtimes. This pass rewrites the three
 * generated shapes that carry that order — the quoted option-name lists, the completion
 * candidate declarations, and the previous-word case blocks — into sorted form, so the
 * printed script is a function of the grammar alone. Every other line passes through
 * untouched, and any line the patterns do not recognize passes through untouched, so the
 * pass can reorder but never rewrite the script's own syntax.
 */
final class CanonicalCompletionOrder {

    private static final Pattern OPTION_LIST_LINE = Pattern.compile("(\\s*local (?:arg_opts|flag_opts)=\")(.*)(\")");

    private static final Pattern CANDIDATES_LINE = Pattern.compile("\\s*local \\S+_option_args=\\(.*");

    private static final String CASE_HEADER = "  case ${prev_word} in";

    private static final String CASE_END = "  esac";

    private static final Pattern CASE_LABEL = Pattern.compile("    '.*");

    private static final String CASE_BLOCK_END = "      ;;";

    private CanonicalCompletionOrder() {}

    /** The script with every order-sensitive generated shape sorted. */
    static String sort(String script) {
        String[] lines = script.split("\n", -1);
        StringBuilder out = new StringBuilder(script.length());
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (CANDIDATES_LINE.matcher(line).matches()) {
                index = emitSortedLines(lines, index, CANDIDATES_LINE, out);
            } else if (CASE_HEADER.equals(line)) {
                out.append(line).append('\n');
                index = emitSortedCaseBlocks(lines, index + 1, out);
            } else {
                out.append(canonicalOptionList(line)).append('\n');
                index++;
            }
        }
        return out.toString();
    }

    /** One line with its quoted option-name list sorted, when the line carries one. */
    private static String canonicalOptionList(String line) {
        Matcher quoted = OPTION_LIST_LINE.matcher(line);
        if (!quoted.matches()) {
            return line;
        }
        String[] names = quoted.group(2).split(" ");
        Arrays.sort(names);
        return quoted.group(1) + String.join(" ", names) + quoted.group(3);
    }

    /** Emits the maximal run of matching lines in sorted order; returns the next unread index. */
    private static int emitSortedLines(String[] lines, int from, Pattern shape, StringBuilder out) {
        List<String> run = new ArrayList<>();
        int index = from;
        while (index < lines.length && shape.matcher(lines[index]).matches()) {
            run.add(lines[index]);
            index++;
        }
        run.sort(String::compareTo);
        for (String line : run) {
            out.append(line).append('\n');
        }
        return index;
    }

    /**
     * Emits the case construct's label blocks in sorted label order; returns the index after
     * the construct's {@code esac}. A construct whose body does not parse into complete
     * blocks is emitted verbatim instead, so an unexpected generator shape never loses its
     * own text.
     */
    private static int emitSortedCaseBlocks(String[] lines, int firstBodyLine, StringBuilder out) {
        List<List<String>> blocks = new ArrayList<>();
        int index = firstBodyLine;
        while (index < lines.length && !CASE_END.equals(lines[index])) {
            if (!CASE_LABEL.matcher(lines[index]).matches()) {
                return emitVerbatim(lines, firstBodyLine, index, out);
            }
            List<String> block = new ArrayList<>();
            block.add(lines[index]);
            index++;
            while (index < lines.length && !CASE_BLOCK_END.equals(lines[index])) {
                block.add(lines[index]);
                index++;
            }
            if (index >= lines.length) {
                return emitVerbatim(lines, firstBodyLine, index, out);
            }
            block.add(lines[index]);
            index++;
            blocks.add(block);
        }
        if (index >= lines.length) {
            return emitVerbatim(lines, firstBodyLine, index, out);
        }
        blocks.sort((first, second) -> first.getFirst().compareTo(second.getFirst()));
        for (List<String> block : blocks) {
            for (String line : block) {
                out.append(line).append('\n');
            }
        }
        out.append(CASE_END).append('\n');
        return index + 1;
    }

    /** Emits {@code to - from} lines exactly as they came; returns {@code to}. */
    private static int emitVerbatim(String[] lines, int from, int to, StringBuilder out) {
        for (int index = from; index < to; index++) {
            out.append(lines[index]).append('\n');
        }
        return to;
    }
}
