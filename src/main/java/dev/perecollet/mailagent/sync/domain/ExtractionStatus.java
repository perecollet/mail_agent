package dev.perecollet.mailagent.sync.domain;

/** Outcome of extracting text from an attachment; mirrors {@code email_attachment.extraction_status}. */
public enum ExtractionStatus {
    DONE,
    SKIPPED,
    FAILED
}
