package dev.perecollet.mailagent.sync.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes quoted history and the signature from a plain-text body, producing {@code body_stripped}: what the
 * model sees (design §5.5). Recognizes English, Spanish and Catalan formats of Gmail, Apple Mail, Outlook and
 * mobile clients.
 *
 * <p>It is conservative, because over-cutting hides content from the model while under-cutting only costs tokens:
 * <ul>
 *   <li>forwards are never cut, whether flagged by the subject or by a forward marker in the body;</li>
 *   <li>quoted history is only cut when none of the user's own text follows it (inline and bottom-posted replies
 *       are kept);</li>
 *   <li>if cutting would leave nothing, the original text is returned unchanged.</li>
 * </ul>
 * A cut text has {@code \n} line endings and no trailing whitespace.
 */
public final class ReplyStripper {

    private static final int OPTIONS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Structural lines (attributions, headers, footers) are short; longer lines are content and never matched. */
    private static final int MAX_STRUCTURAL_LINE = 300;

    /**
     * Attribution line before the quoted message: {@code On Mon, 3 Nov 2026 at 10:00, Ana <a@x> wrote:},
     * {@code El 3 nov 2026, a las 10:00, Ana escribió:}, {@code El dl., 3 de nov. 2026, Ana va escriure:}.
     * Bounded quantifiers keep matching linear.
     */
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "^\\s*(?:on|el)\\s[^\\n]{0,300}?\\d[^\\n]{0,300}?\\s"
                    + "(?:wrote|escribió|ha\\s+escrito|va\\s+escriure|ha\\s+escrit)\\s*:\\s*$",
            OPTIONS);

    /** Separator lines that introduce the original message in Outlook and other clients. */
    private static final Pattern ORIGINAL_MESSAGE = Pattern.compile(
            "^\\s*-{2,}\\s*(?:original\\s+message|mensaje\\s+original|missatge\\s+original)\\s*-{2,}\\s*$", OPTIONS);

    /** Markers of a forwarded message: its content is the message, so nothing is cut. */
    private static final Pattern FORWARD_MARKER = Pattern.compile(
            "^\\s*(?:-{2,}\\s*(?:forwarded\\s+message|mensaje\\s+reenviado|missatge\\s+reenviat)\\s*-{2,}"
                    + "|begin\\s+forwarded\\s+message\\s*:"
                    + "|inicio\\s+del\\s+mensaje\\s+reenviado\\s*:"
                    + "|inici\\s+del\\s+missatge\\s+reenviat\\s*:)\\s*$",
            OPTIONS);

    /** First line of an Outlook header block: {@code From:}, {@code De:}, {@code De :}. */
    private static final Pattern HEADER_FROM = Pattern.compile("^\\s*\\*?(?:from|de)\\s*:\\*?\\s*\\S.*$", OPTIONS);

    /** The "sent" field, which ordinary text such as an itinerary does not have. */
    private static final Pattern HEADER_SENT = Pattern.compile(
            "^\\s*\\*?(?:sent|enviado(?:\\s+el)?|enviat(?:\\s+el)?)\\s*:\\*?.*$", OPTIONS);

    /** Any field of an Outlook header block in EN, ES and CA. */
    private static final Pattern HEADER_FIELD = Pattern.compile(
            "^\\s*\\*?(?:sent|date|to|cc|subject|enviado(?:\\s+el)?|fecha|para|asunto|enviat(?:\\s+el)?|data"
                    + "|per\\s+a|a|assumpte)\\s*:\\*?.*$",
            OPTIONS);

    /** Mobile and app footers ({@code Sent from my iPhone}), which end the useful text. */
    private static final Pattern MOBILE_FOOTER = Pattern.compile(
            "^\\s*(?:(?:sent\\s+from\\s+my|enviado\\s+desde\\s+mi|enviat\\s+des\\s+del\\s+meu)\\s+"
                    + "(?:iphone|ipad|android|galaxy|samsung|huawei|xiaomi|pixel|blackberry|smartphone|phone|mobile"
                    + "|tablet|móvil|celular|mòbil|telèfon|teléfono)\\b.{0,30}"
                    + "|sent\\s+from\\s+mail\\s+for\\s+windows.{0,10}"
                    + "|enviado\\s+desde\\s+correo\\s+para\\s+windows.{0,10}"
                    + "|sent\\s+with\\s+proton\\s+mail.{0,30}"
                    + "|get\\s+outlook\\s+for\\s+(?:ios|android).{0,10}"
                    + "|obtener\\s+outlook\\s+para\\s+(?:ios|android).{0,10}"
                    + "|obt[ée]n\\s+outlook\\s+per\\s+a\\s+(?:ios|android).{0,10})$",
            OPTIONS);

    /** Usenet signature delimiter {@code "-- "}; many clients drop the trailing space. */
    private static final Pattern SIGNATURE_DELIMITER = Pattern.compile("^-- ?$");

    /** Rule Outlook draws above the header block of the original message. */
    private static final Pattern SEPARATOR = Pattern.compile("^\\s*(?:_{10,}|-{10,})\\s*$");

    private static final Pattern QUOTED = Pattern.compile("^\\s*>.*$");

    /** Fields that must follow {@code From:} on consecutive lines for the block to be an Outlook header. */
    private static final int MIN_HEADER_FIELDS = 2;

    private ReplyStripper() {
    }

    /**
     * The text without quoted history and signature.
     *
     * @param text    plain-text body, possibly {@code null}
     * @param forward whether the subject marks a forward (see {@code SubjectNormalizer.isForward}); forwards are
     *                returned unchanged
     */
    public static String strip(String text, boolean forward) {
        if (text == null || forward) {
            return text;
        }
        List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        if (lines.stream().anyMatch(line -> isShort(line) && FORWARD_MARKER.matcher(line).matches())) {
            return text;
        }
        int cut = firstCut(lines);
        while (cut > 0 && (lines.get(cut - 1).isBlank() || SEPARATOR.matcher(lines.get(cut - 1)).matches())) {
            cut--;
        }
        String stripped = String.join("\n", lines.subList(0, cut)).stripTrailing();
        return stripped.isBlank() ? text : stripped;
    }

    /** Index of the first line of history or signature that runs to the end of the text, or the line count. */
    private static int firstCut(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isShort(line)) {
                continue;
            }
            if (SIGNATURE_DELIMITER.matcher(line).matches()
                    || ORIGINAL_MESSAGE.matcher(line).matches()
                    || MOBILE_FOOTER.matcher(line).matches()
                    || isOutlookHeader(lines, i)) {
                return i;
            }
            int attributionEnd = attributionEnd(lines, i);
            if (attributionEnd >= 0 && onlyQuotesOrBlanksFrom(lines, attributionEnd)) {
                return i;
            }
            if (QUOTED.matcher(line).matches() && onlyQuotesOrBlanksFrom(lines, i)) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * Index of the line after an attribution starting at {@code i}, or -1. Gmail may wrap the attribution over two
     * lines, so a short line joined with the next one is tried as well.
     */
    private static int attributionEnd(List<String> lines, int i) {
        String line = lines.get(i);
        if (ATTRIBUTION.matcher(line).matches()) {
            return i + 1;
        }
        if (i + 1 < lines.size() && isShort(line + lines.get(i + 1))
                && ATTRIBUTION.matcher(line + " " + lines.get(i + 1)).matches()) {
            return i + 2;
        }
        return -1;
    }

    /**
     * {@code From:} followed by at least two header fields on consecutive lines, and either an address in the
     * {@code From:} line or a "sent" field, so labels such as {@code From: Barcelona / To: Madrid} are not cut.
     */
    private static boolean isOutlookHeader(List<String> lines, int i) {
        String from = lines.get(i);
        if (!HEADER_FROM.matcher(from).matches()) {
            return false;
        }
        int fields = 0;
        boolean sent = false;
        for (int j = i + 1; j < lines.size() && isShort(lines.get(j)) && HEADER_FIELD.matcher(lines.get(j)).matches(); j++) {
            fields++;
            sent |= HEADER_SENT.matcher(lines.get(j)).matches();
        }
        return fields >= MIN_HEADER_FIELDS && (sent || from.contains("@"));
    }

    private static boolean onlyQuotesOrBlanksFrom(List<String> lines, int from) {
        for (int j = from; j < lines.size(); j++) {
            String line = lines.get(j);
            if (!line.isBlank() && !QUOTED.matcher(line).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isShort(String line) {
        return line.length() <= MAX_STRUCTURAL_LINE;
    }
}
