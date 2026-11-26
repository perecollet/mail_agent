package dev.perecollet.mailagent.sync.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes the thread headers {@code Message-ID}, {@code In-Reply-To} and {@code References}: angle brackets
 * and whitespace removed, lower-cased (design §5.5). Malformed input never throws; it yields {@code null} or an
 * empty list.
 */
public final class MessageIds {

    /** RFC 5322 comments, e.g. {@code (Ana's message of Monday)}; nesting is not supported. */
    private static final Pattern COMMENT = Pattern.compile("\\([^()]*\\)");

    /**
     * A bracketed id (anything inside, as some legacy ids lack {@code @}), or a bare token containing {@code @}
     * for clients that omit the brackets. A stray {@code <} of an unterminated id is skipped.
     */
    private static final Pattern ID = Pattern.compile("<([^<>]*)>|([^\\s,<>]*@[^\\s,<>]*)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MessageIds() {
    }

    /**
     * The first id in a {@code Message-ID} or {@code In-Reply-To} header, or {@code null} if there is none.
     * When {@code In-Reply-To} lists several parents only the first is kept; the others still reach threading
     * through {@code References}.
     */
    public static String normalize(String header) {
        List<String> ids = extract(header);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * The ids of a {@code References} header in header order, keeping only the last occurrence of a repeated id
     * and only the last {@value EmailRecord#MAX_REFERENCES} ids (the most recent ones matter for threading).
     */
    public static List<String> parseReferences(String header) {
        SequencedSet<String> lastOccurrences = new LinkedHashSet<>();
        for (String id : extract(header).reversed()) {
            lastOccurrences.add(id);
        }
        List<String> ids = new ArrayList<>(lastOccurrences.reversed());
        int excess = ids.size() - EmailRecord.MAX_REFERENCES;
        return List.copyOf(excess > 0 ? ids.subList(excess, ids.size()) : ids);
    }

    private static List<String> extract(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Matcher matcher = ID.matcher(COMMENT.matcher(header).replaceAll(" "));
        while (matcher.find()) {
            String candidate = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            // Folded headers can break a long id across lines.
            String id = WHITESPACE.matcher(candidate).replaceAll("").toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
