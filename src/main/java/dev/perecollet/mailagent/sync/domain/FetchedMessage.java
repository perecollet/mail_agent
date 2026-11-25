package dev.perecollet.mailagent.sync.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A message as read from the server, free of Jakarta Mail types. Header values are raw: {@code MessageNormalizer}
 * turns them into an {@link EmailRecord}.
 *
 * @param uid                IMAP UID within the folder's current UIDVALIDITY
 * @param receivedAt         INTERNALDATE, set by the server
 * @param sentAt             {@code Date} header; {@code null} if missing or unparseable
 * @param messageId          raw {@code Message-ID} header, or {@code null}
 * @param inReplyTo          raw {@code In-Reply-To} header, or {@code null}
 * @param references         raw {@code References} header, or {@code null}
 * @param fromAddress        sender address, or {@code null}
 * @param fromName           sender display name, or {@code null}
 * @param toAddresses        {@code To} addresses as found in the message
 * @param ccAddresses        {@code Cc} addresses as found in the message
 * @param subject            raw subject, or {@code null}
 * @param seen               the {@code \Seen} flag
 * @param providerImportant  Gmail's {@code \Important} label
 * @param hasListUnsubscribe whether the message has a {@code List-Unsubscribe} header
 * @param plainText          {@code text/plain} body, or {@code null}
 * @param html               {@code text/html} body, or {@code null}
 * @param attachments        attachments with distinct part paths
 */
public record FetchedMessage(
        long uid,
        Instant receivedAt,
        Instant sentAt,
        String messageId,
        String inReplyTo,
        String references,
        String fromAddress,
        String fromName,
        List<String> toAddresses,
        List<String> ccAddresses,
        String subject,
        boolean seen,
        boolean providerImportant,
        boolean hasListUnsubscribe,
        String plainText,
        String html,
        List<FetchedAttachment> attachments) {

    public FetchedMessage {
        if (uid < 1) {
            throw new IllegalArgumentException("uid must be positive: " + uid);
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        toAddresses = List.copyOf(Objects.requireNonNull(toAddresses, "toAddresses"));
        ccAddresses = List.copyOf(Objects.requireNonNull(ccAddresses, "ccAddresses"));
        attachments = FetchedAttachment.copyWithDistinctPartPaths(attachments);
    }

    /** Email content and addresses stay out of logs. */
    @Override
    public String toString() {
        return "FetchedMessage[uid=%d, receivedAt=%s, attachments=%d]".formatted(uid, receivedAt, attachments.size());
    }
}
