package dev.perecollet.mailagent.sync.domain;

/**
 * What the server reports for a folder when it is opened.
 *
 * @param uidValidity  the folder's UIDVALIDITY
 * @param uidNext      the UID the next message will get, or {@code null} if the server did not report it
 * @param messageCount number of messages in the folder
 */
public record MailboxStatus(long uidValidity, Long uidNext, int messageCount) {

    public MailboxStatus {
        if (uidValidity < 1) {
            throw new IllegalArgumentException("uidValidity must be positive: " + uidValidity);
        }
        if (uidNext != null && uidNext < 1) {
            throw new IllegalArgumentException("uidNext must be positive: " + uidNext);
        }
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative: " + messageCount);
        }
    }

    public boolean hasUidNext() {
        return uidNext != null;
    }

    public boolean isEmpty() {
        return messageCount == 0;
    }
}
