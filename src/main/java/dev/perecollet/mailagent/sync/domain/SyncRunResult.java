package dev.perecollet.mailagent.sync.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of one pass over one folder, stored as a row of {@code sync_run}.
 *
 * @param errorMessage {@code null} when the pass succeeded
 */
public record SyncRunResult(
        long folderId,
        SyncKind kind,
        Instant startedAt,
        Instant finishedAt,
        int messagesFetched,
        int messagesUpdated,
        String errorMessage) {

    public SyncRunResult {
        if (folderId < 1) {
            throw new IllegalArgumentException("folderId must be positive: " + folderId);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt is before startedAt");
        }
        if (messagesFetched < 0 || messagesUpdated < 0) {
            throw new IllegalArgumentException("Message counts must not be negative");
        }
        if (errorMessage != null && errorMessage.isBlank()) {
            throw new IllegalArgumentException("A failed run needs an error message");
        }
    }

    public static SyncRunResult success(
            long folderId, SyncKind kind, Instant startedAt, Instant finishedAt, int fetched, int updated) {
        return new SyncRunResult(folderId, kind, startedAt, finishedAt, fetched, updated, null);
    }

    public static SyncRunResult failure(
            long folderId, SyncKind kind, Instant startedAt, Instant finishedAt, int fetched, String errorMessage) {
        Objects.requireNonNull(errorMessage, "errorMessage");
        return new SyncRunResult(folderId, kind, startedAt, finishedAt, fetched, 0, errorMessage);
    }

    public boolean succeeded() {
        return errorMessage == null;
    }
}
