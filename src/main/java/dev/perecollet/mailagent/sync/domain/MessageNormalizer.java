package dev.perecollet.mailagent.sync.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns a {@link FetchedMessage} into an {@link EmailRecord} ready to store (design §5.5): direction by sender
 * (D10), normalized thread headers and subject, clean and stripped bodies, aggregated attachment text, and values
 * that are safe for Postgres (no NUL characters, bounded lengths).
 */
public final class MessageNormalizer {

    /** File name separators, so {@code Factura_agosto_2026.pdf} matches a search for "agosto". */
    private static final Pattern FILE_NAME_SEPARATORS = Pattern.compile("[_\\-.]+");

    private final OwnAddresses ownAddresses;
    private final HtmlTextConverter html;
    private final int maxBodyChars;

    /**
     * @param maxBodyChars maximum length of each body and of the aggregated attachment text; longer values are
     *                     truncated. {@code bodyTruncated} reports truncated bodies only
     */
    public MessageNormalizer(OwnAddresses ownAddresses, HtmlTextConverter html, int maxBodyChars) {
        this.ownAddresses = Objects.requireNonNull(ownAddresses, "ownAddresses");
        this.html = Objects.requireNonNull(html, "html");
        if (maxBodyChars < 1) {
            throw new IllegalArgumentException("maxBodyChars must be positive: " + maxBodyChars);
        }
        this.maxBodyChars = maxBodyChars;
    }

    public EmailRecord normalize(FetchedMessage message, String accountId, long folderId, long uidValidity) {
        String subject = blankToNull(clean(message.subject()));
        boolean forward = SubjectNormalizer.isForward(subject);
        String fromAddress = address(message.fromAddress());
        Direction direction = ownAddresses.contains(fromAddress) ? Direction.OUTBOUND : Direction.INBOUND;

        Bodies bodies = bodies(message, forward);
        Truncated bodyText = truncate(bodies.full());
        Truncated bodyStripped = truncate(bodies.stripped());
        List<FetchedAttachment> attachments = message.attachments().stream().map(MessageNormalizer::clean).toList();
        Truncated attachmentText = truncate(attachmentText(attachments));

        return new EmailRecord(
                accountId,
                folderId,
                uidValidity,
                message.uid(),
                MessageIds.normalize(clean(message.messageId())),
                MessageIds.normalize(clean(message.inReplyTo())),
                MessageIds.parseReferences(clean(message.references())),
                direction,
                fromAddress,
                blankToNull(clean(message.fromName())),
                addresses(message.toAddresses()),
                addresses(message.ccAddresses()),
                subject,
                SubjectNormalizer.normalize(subject),
                message.sentAt(),
                message.receivedAt(),
                message.seen(),
                message.providerImportant(),
                message.hasListUnsubscribe(),
                bodyText.value(),
                bodyStripped.value(),
                bodyText.truncated() || bodyStripped.truncated(),
                attachmentText.value(),
                attachments);
    }

    private record Bodies(String full, String stripped) {
    }

    /** Plain text when present; otherwise HTML, whose quote containers are removed before conversion. */
    private Bodies bodies(FetchedMessage message, boolean forward) {
        String plain = clean(message.plainText());
        if (plain != null && !plain.isBlank()) {
            String full = lineEndings(plain).strip();
            return new Bodies(full, ReplyStripper.strip(full, forward));
        }
        String markup = clean(message.html());
        if (markup == null || markup.isBlank()) {
            return new Bodies(null, null);
        }
        String full = blankToNull(lineEndings(clean(html.toText(markup))));
        if (forward || full == null) {
            return new Bodies(full, full);
        }
        String withoutQuotes = lineEndings(clean(html.toTextWithoutQuotes(markup)));
        String stripped = ReplyStripper.strip(withoutQuotes, false);
        return new Bodies(full, stripped == null || stripped.isBlank() ? full : stripped);
    }

    /** File names with separators turned into spaces, plus extracted text, one per line. */
    private static String attachmentText(List<FetchedAttachment> attachments) {
        List<String> parts = new ArrayList<>();
        for (FetchedAttachment attachment : attachments) {
            if (attachment.fileName() != null && !attachment.fileName().isBlank()) {
                parts.add(FILE_NAME_SEPARATORS.matcher(attachment.fileName()).replaceAll(" ").strip());
            }
            if (attachment.extractedText() != null && !attachment.extractedText().isBlank()) {
                parts.add(attachment.extractedText().strip());
            }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private record Truncated(String value, boolean truncated) {
    }

    /** Cuts at {@code maxBodyChars} without splitting a surrogate pair. */
    private Truncated truncate(String text) {
        if (text == null || text.length() <= maxBodyChars) {
            return new Truncated(text, false);
        }
        int end = Character.isHighSurrogate(text.charAt(maxBodyChars - 1)) ? maxBodyChars - 1 : maxBodyChars;
        return new Truncated(text.substring(0, end), true);
    }

    /** Lower-cased, without blanks or duplicates, in order of appearance. */
    private static List<String> addresses(List<String> raw) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String candidate : raw) {
            String address = address(candidate);
            if (address != null) {
                unique.add(address);
            }
        }
        return List.copyOf(unique);
    }

    private static String address(String raw) {
        String cleaned = clean(raw);
        return cleaned == null || cleaned.isBlank() ? null : cleaned.strip().toLowerCase(Locale.ROOT);
    }

    private static FetchedAttachment clean(FetchedAttachment attachment) {
        return new FetchedAttachment(attachment.partPath(), clean(attachment.fileName()),
                clean(attachment.contentType()), attachment.sizeBytes(), attachment.inline(),
                attachment.extractionStatus(), clean(attachment.extractedText()));
    }

    /** Postgres text columns reject NUL characters. */
    private static String clean(String value) {
        return value == null ? null : value.replace("\u0000", "");
    }

    private static String lineEndings(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
