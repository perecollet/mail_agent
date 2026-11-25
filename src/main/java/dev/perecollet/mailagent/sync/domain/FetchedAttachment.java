package dev.perecollet.mailagent.sync.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An attachment as read from the server: metadata, its MIME part path and the text extracted from it, if any.
 * The binary is never kept; it is fetched again on demand.
 *
 * @param partPath      position in the MIME tree, e.g. {@code 2.1}
 * @param fileName      {@code null} if the part has no name
 * @param sizeBytes     {@code null} if the server did not report it
 * @param extractedText text extracted from the attachment; present only when {@code extractionStatus} is DONE
 */
public record FetchedAttachment(
        String partPath,
        String fileName,
        String contentType,
        Long sizeBytes,
        boolean inline,
        ExtractionStatus extractionStatus,
        String extractedText) {

    public FetchedAttachment {
        Objects.requireNonNull(partPath, "partPath");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(extractionStatus, "extractionStatus");
        if (partPath.isBlank()) {
            throw new IllegalArgumentException("partPath must not be blank");
        }
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
        if ((extractionStatus == ExtractionStatus.DONE) != (extractedText != null)) {
            throw new IllegalArgumentException("extractedText must be present exactly when extraction is DONE");
        }
    }

    /** Attachment whose text was not extracted (not a PDF, too large…). */
    public static FetchedAttachment withoutText(
            String partPath, String fileName, String contentType, Long sizeBytes, boolean inline,
            ExtractionStatus status) {
        if (status == ExtractionStatus.DONE) {
            throw new IllegalArgumentException("Use withText for a successful extraction");
        }
        return new FetchedAttachment(partPath, fileName, contentType, sizeBytes, inline, status, null);
    }

    public static FetchedAttachment withText(
            String partPath, String fileName, String contentType, Long sizeBytes, boolean inline, String text) {
        return new FetchedAttachment(partPath, fileName, contentType, sizeBytes, inline, ExtractionStatus.DONE, text);
    }

    /**
     * Copies {@code attachments}, rejecting repeated part paths: {@code email_attachment} is unique on
     * {@code (email_id, part_path)} and one bad message must not fail a whole batch.
     */
    static List<FetchedAttachment> copyWithDistinctPartPaths(List<FetchedAttachment> attachments) {
        List<FetchedAttachment> copy = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
        Set<String> partPaths = new HashSet<>();
        for (FetchedAttachment attachment : copy) {
            if (!partPaths.add(attachment.partPath())) {
                throw new IllegalArgumentException("Duplicate attachment part path: " + attachment.partPath());
            }
        }
        return copy;
    }

    /** File name and extracted text stay out of logs. */
    @Override
    public String toString() {
        return "FetchedAttachment[partPath=%s, contentType=%s, sizeBytes=%s, inline=%s, extractionStatus=%s]"
                .formatted(partPath, contentType, sizeBytes, inline, extractionStatus);
    }
}
