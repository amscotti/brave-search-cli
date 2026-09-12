package io.amscotti.bravesearch.adapter.cli.presentation;

import io.amscotti.bravesearch.domain.metadata.RateLimitWindow;
import io.amscotti.bravesearch.domain.output.HumanMember;
import io.amscotti.bravesearch.domain.output.OutputRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shared entry layout of every human listing, so one renderer's shape is every
 * family's shape: a two-space gutter after an index column right-aligned to the widest
 * index of the listing, the title bold and word-wrapped at the render width with
 * continuation lines aligned under it, url members dim and never wrapped, body text
 * word-wrapped at default intensity, and per-type metadata as the final dim lines of the
 * entry.
 *
 * <p>Every member text passes the terminal-safety rule before it is wrapped, and styling
 * is applied after wrapping, so layout code never measures an escape byte. The advisory
 * quota footer closes a listing: one dim trailing line naming the most restrictive
 * applicable rate-limit window exactly when it holds two or fewer remaining requests,
 * suppressed by {@code --quiet} like every advisory line.
 */
public final class HumanListing {

    /** The placeholder title of an entry whose upstream title was absent or unusable. */
    public static final String UNTITLED = "(no title)";

    private static final int MAXIMUM_REMAINING_FOR_FOOTER = 2;

    private HumanListing() {}

    /**
     * The width of the right-aligned index column of a listing of {@code entryCount}
     * entries: one column wider than the widest index, so the widest index still reads
     * separated from the margin.
     */
    public static int indexFieldWidth(int entryCount) {
        return Integer.toString(entryCount).length() + 1;
    }

    /** The gutter-and-index prefix of one entry's title line: right-aligned index plus two spaces. */
    public static String indexPrefix(int index, int entryCount) {
        int field = indexFieldWidth(entryCount);
        return " ".repeat(field - Integer.toString(index).length()) + index + "  ";
    }

    /** The column every continuation and member line of an entry aligns to: under the title. */
    public static int memberIndent(int entryCount) {
        return indexFieldWidth(entryCount) + 2;
    }

    /**
     * Appends the entry's title line: sanitized, wrapped at the width minus the gutter,
     * bold in a colorable render context on every wrapped line, with continuation lines
     * aligned under the first. A title that sanitizes away to nothing renders the
     * untitled placeholder instead.
     */
    public static void appendTitle(StringBuilder document, int index, int entryCount, String title, OutputRequest output) {
        Objects.requireNonNull(title, "title");
        String safe = TerminalSafeText.sanitize(title);
        List<String> lines = TextWrap.words(safe, wrapWidth(entryCount, output));
        if (lines.isEmpty()) {
            lines = List.of(UNTITLED);
        }
        String continuation = " ".repeat(memberIndent(entryCount));
        for (int line = 0; line < lines.size(); line++) {
            document.append(line == 0 ? indexPrefix(index, entryCount) : continuation)
                    .append(SgrStyle.bold(lines.get(line), output))
                    .append('\n');
        }
    }

    /**
     * Appends one url member line: sanitized, dim in a colorable render context, and
     * never wrapped — a url longer than the width overflows whole, because breaking a
     * url changes what it names.
     */
    public static void appendUrlLine(StringBuilder document, int entryCount, String url, OutputRequest output) {
        Objects.requireNonNull(url, "url");
        String safe = TerminalSafeText.sanitize(url);
        if (safe.isEmpty()) {
            return;
        }
        document.append(" ".repeat(memberIndent(entryCount)))
                .append(SgrStyle.dim(safe, output))
                .append('\n');
    }

    /**
     * Appends one body-text member: sanitized and word-wrapped at the width minus the
     * gutter, at default intensity, with continuation lines aligned under the first.
     */
    public static void appendTextLines(StringBuilder document, int entryCount, String text, OutputRequest output) {
        appendWrapped(document, entryCount, TerminalSafeText.sanitize(text), output, false);
    }

    /**
     * Appends one metadata member: sanitized and word-wrapped like body text, dim in a
     * colorable render context.
     */
    public static void appendMetaLines(StringBuilder document, int entryCount, String text, OutputRequest output) {
        appendWrapped(document, entryCount, TerminalSafeText.sanitize(text), output, true);
    }

    /**
     * Appends an entry's member lines: url and text members in their encounter order,
     * then the metadata members in theirs, so the per-type facts close the block.
     */
    public static void appendMembers(StringBuilder document, int entryCount, List<HumanMember> members, OutputRequest output) {
        List<HumanMember> metadata = new ArrayList<>();
        for (HumanMember member : members) {
            if (member.kind() == HumanMember.Kind.META) {
                metadata.add(member);
            } else {
                appendMember(document, entryCount, member, output);
            }
        }
        for (HumanMember member : metadata) {
            appendMember(document, entryCount, member, output);
        }
    }

    private static void appendMember(StringBuilder document, int entryCount, HumanMember member, OutputRequest output) {
        switch (member.kind()) {
            case URL -> appendUrlLine(document, entryCount, member.text(), output);
            case TEXT -> appendTextLines(document, entryCount, member.text(), output);
            case META -> appendMetaLines(document, entryCount, member.text(), output);
        }
    }

    private static void appendWrapped(
            StringBuilder document, int entryCount, String safe, OutputRequest output, boolean dim) {
        for (String line : TextWrap.words(safe, wrapWidth(entryCount, output))) {
            document.append(" ".repeat(memberIndent(entryCount)))
                    .append(dim ? SgrStyle.dim(line, output) : line)
                    .append('\n');
        }
    }

    private static int wrapWidth(int entryCount, OutputRequest output) {
        return Math.max(1, output.width() - memberIndent(entryCount));
    }

    /**
     * The advisory quota footer of a listing: one dim line naming the most restrictive
     * applicable window — the lowest remaining among the windows with a real limit, the
     * first observed on ties — exactly when that window holds two or fewer remaining
     * requests and quiet did not suppress it; an empty string otherwise.
     *
     * @param windows the outcome snapshot's parsed windows; {@code null} when none were
     *     observed
     * @param output the invocation's parsed output state carrying the color and quiet
     *     decisions
     */
    public static String quotaFooter(List<RateLimitWindow> windows, OutputRequest output) {
        Objects.requireNonNull(output, "output");
        if (windows == null || windows.isEmpty() || output.quiet()) {
            return "";
        }
        RateLimitWindow mostRestrictive = null;
        for (RateLimitWindow window : windows) {
            if (window.limit() <= 0) {
                // a limit of zero means unlimited capacity, not exhaustion
                continue;
            }
            if (mostRestrictive == null || window.remaining() < mostRestrictive.remaining()) {
                mostRestrictive = window;
            }
        }
        if (mostRestrictive == null || mostRestrictive.remaining() > MAXIMUM_REMAINING_FOR_FOOTER) {
            return "";
        }
        return SgrStyle.dim(
                "quota: " + mostRestrictive.remaining()
                        + " of "
                        + mostRestrictive.limit()
                        + " remaining (window resets in "
                        + mostRestrictive.reset().toSeconds()
                        + "s)",
                output);
    }

    /** Appends the advisory quota footer line to a listing when its windows earned one. */
    public static void appendQuotaFooter(StringBuilder document, List<RateLimitWindow> windows, OutputRequest output) {
        String footer = quotaFooter(windows, output);
        if (!footer.isEmpty()) {
            document.append(footer).append('\n');
        }
    }
}
