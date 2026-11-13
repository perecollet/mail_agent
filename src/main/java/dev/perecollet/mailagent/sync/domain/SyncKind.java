package dev.perecollet.mailagent.sync.domain;

/** The kind of pass recorded in {@code sync_run} (design §5.1). */
public enum SyncKind {
    INCREMENTAL,
    FLAGS,
    RECONCILE
}
