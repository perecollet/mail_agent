package dev.perecollet.mailagent.sync.domain;

import java.util.Objects;

/**
 * Incremental sync position of one folder: the server's UIDVALIDITY and the highest UID already stored.
 *
 * <p>Immutable. {@code uidValidity} is {@code null} until the folder is synchronized for the first time, and a
 * {@code lastUid} of 0 means no message has been stored yet under the current UIDVALIDITY.
 */
public record FolderCursor(long folderId, String accountId, FolderRef folder, Long uidValidity, long lastUid) {

    public FolderCursor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(folder, "folder");
        if (uidValidity != null && uidValidity < 1) {
            throw new IllegalArgumentException("uidValidity must be positive: " + uidValidity);
        }
        if (lastUid < 0) {
            throw new IllegalArgumentException("lastUid must not be negative: " + lastUid);
        }
    }

    /** Cursor of a folder that has never been synchronized. */
    public static FolderCursor uninitialized(long folderId, String accountId, FolderRef folder) {
        return new FolderCursor(folderId, accountId, folder, null, 0L);
    }

    public boolean isInitialized() {
        return uidValidity != null;
    }

    /** No message stored yet: the next pass imports the initial window instead of the UIDs after the cursor. */
    public boolean needsInitialSync() {
        return lastUid == 0L;
    }

    /** Whether the stored UIDs are still meaningful for the UIDVALIDITY the server reports now. */
    public boolean isValidFor(long serverUidValidity) {
        return isInitialized() && uidValidity == serverUidValidity;
    }

    /** Starts over under a new UIDVALIDITY: every UID stored before is meaningless. */
    public FolderCursor resetTo(long newUidValidity) {
        return new FolderCursor(folderId, accountId, folder, newUidValidity, 0L);
    }

    /**
     * Moves the cursor forward to {@code uid}. Lower or equal UIDs are ignored, so the cursor never moves
     * backwards (servers answer {@code UID FETCH n:*} with the last message even when its UID is below n).
     */
    public FolderCursor advancedTo(long uid) {
        if (!isInitialized()) {
            throw new IllegalStateException("Cannot advance an uninitialized cursor; call resetTo first");
        }
        if (uid <= lastUid) {
            return this;
        }
        return new FolderCursor(folderId, accountId, folder, uidValidity, uid);
    }

    public String folderName() {
        return folder.name();
    }
}
