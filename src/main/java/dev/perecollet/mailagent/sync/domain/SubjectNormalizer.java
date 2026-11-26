package dev.perecollet.mailagent.sync.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips reply and forward prefixes ({@code Re:}, {@code Fwd:}, {@code RV:}, {@code AW:}…) and leading
 * {@code [TAG]}s from a subject, so replies across clients and languages share one {@code subject_normalized}
 * (design §5.5). Also tells whether a subject marks a forward, which {@code ReplyStripper} must never cut.
 */
public final class SubjectNormalizer {

    /**
     * Reply prefixes in EN, ES, CA, FR, DE, PT and IT, matched case-insensitively. French {@code Réf:} is a
     * reference label, not a reply, so it is deliberately absent.
     */
    private static final Set<String> REPLY = Set.of(
            "re", "aw", "antw", "antwort", "rif", "res", "resp", "respuesta", "resposta", "réponse", "risposta");

    /** Forward prefixes in EN, ES, CA, FR, DE, PT and IT, matched case-insensitively. */
    private static final Set<String> FORWARD = Set.of(
            "fw", "fwd", "rv", "reenv", "reenviado", "reenviat", "tr", "transfert", "wg", "weitergeleitet",
            "enc", "encaminhado", "inoltro", "inoltrato");

    /**
     * Italian Outlook's one-letter prefixes ({@code R:} reply, {@code I:} forward), accepted in upper case only
     * so ordinary text such as {@code i: ...} is not mistaken for a prefix.
     */
    private static final Set<String> REPLY_UPPER_CASE_ONLY = Set.of("R");
    private static final Set<String> FORWARD_UPPER_CASE_ONLY = Set.of("I");

    /**
     * One prefix, optionally numbered ({@code Re[2]:}, {@code Re(3):}, {@code Re^2:}), with an ASCII or full-width
     * colon. Unicode whitespace classes cover the non-breaking space French clients put before the colon.
     */
    private static final Pattern PREFIX = Pattern.compile(
            "^(\\p{L}{1,14})(?:\\s*\\[\\d+]|\\s*\\(\\d+\\)|\\^\\d+)?\\s*[:：]\\s*", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern LEADING_TAG = Pattern.compile("^\\[[^\\[\\]]*]\\s*", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private SubjectNormalizer() {
    }

    /** The subject without prefixes or leading tags; {@code null} if nothing is left. */
    public static String normalize(String subject) {
        if (subject == null) {
            return null;
        }
        String rest = strip(subject).rest();
        return rest.isEmpty() ? null : rest;
    }

    /** Whether the outermost prefix marks a forward: {@code Fwd: Re: x} is a forward, {@code Re: Fwd: x} a reply. */
    public static boolean isForward(String subject) {
        return subject != null && strip(subject).outermostIsForward();
    }

    private record Stripped(String rest, boolean outermostIsForward) {
    }

    private static Stripped strip(String subject) {
        String rest = WHITESPACE.matcher(subject).replaceAll(" ").strip();
        Boolean outermostIsForward = null;
        while (true) {
            Matcher tag = LEADING_TAG.matcher(rest);
            if (tag.find()) {
                rest = rest.substring(tag.end());
                continue;
            }
            Matcher prefix = PREFIX.matcher(rest);
            if (!prefix.find()) {
                break;
            }
            String word = prefix.group(1);
            String lowerCase = word.toLowerCase(Locale.ROOT);
            boolean forward = FORWARD.contains(lowerCase) || FORWARD_UPPER_CASE_ONLY.contains(word);
            boolean reply = REPLY.contains(lowerCase) || REPLY_UPPER_CASE_ONLY.contains(word);
            if (!forward && !reply) {
                break;
            }
            if (outermostIsForward == null) {
                outermostIsForward = forward;
            }
            rest = rest.substring(prefix.end());
        }
        return new Stripped(rest, Boolean.TRUE.equals(outermostIsForward));
    }
}
