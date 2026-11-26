package dev.perecollet.mailagent.sync.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A normalized email, ready to store as one row of {@code email} plus its attachments. The natural key is
 * {@code (folderId, uidValidity, uid)} (design D4).
 *
 * @param messageId      without angle brackets, lower-cased; {@code null} if missing
 * @param referencesIds  normalized {@code References}, at most {@value #MAX_REFERENCES} ids
 * @param bodyText       full clean text
 * @param bodyStripped   text without quoted history and signature: what the model sees
 * @param attachmentText file names and extracted attachment text, aggregated for full-text search
 */
public record EmailRecord(
        String accountId,
        long folderId,
        long uidValidity,
        long uid,
        String messageId,
        String inReplyTo,
        List<String> referencesIds,
        Direction direction,
        String fromAddress,
        String fromName,
        List<String> toAddresses,
        List<String> ccAddresses,
        String subject,
        String subjectNormalized,
        Instant sentAt,
        Instant receivedAt,
        boolean seen,
        boolean providerImportant,
        boolean hasListUnsubscribe,
        String bodyText,
        String bodyStripped,
        boolean bodyTruncated,
        String attachmentText,
        List<FetchedAttachment> attachments) {

    public static final int MAX_REFERENCES = 50;

    public EmailRecord {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (folderId < 1 || uidValidity < 1 || uid < 1) {
            throw new IllegalArgumentException(
                    "folderId, uidValidity and uid must be positive: %d, %d, %d".formatted(folderId, uidValidity, uid));
        }
        referencesIds = List.copyOf(Objects.requireNonNull(referencesIds, "referencesIds"));
        toAddresses = List.copyOf(Objects.requireNonNull(toAddresses, "toAddresses"));
        ccAddresses = List.copyOf(Objects.requireNonNull(ccAddresses, "ccAddresses"));
        attachments = FetchedAttachment.copyWithDistinctPartPaths(attachments);
        if (referencesIds.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("At most " + MAX_REFERENCES + " references: " + referencesIds.size());
        }
    }

    /** Inline parts (signature logos, embedded images) do not count as attachments. */
    public boolean hasAttachments() {
        return attachments.stream().anyMatch(attachment -> !attachment.inline());
    }

    /** Email content and addresses stay out of logs. */
    @Override
    public String toString() {
        return "EmailRecord[accountId=%s, folderId=%d, uidValidity=%d, uid=%d, direction=%s, receivedAt=%s]"
                .formatted(accountId, folderId, uidValidity, uid, direction, receivedAt);
    }
}
