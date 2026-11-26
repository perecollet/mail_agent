package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailRecordTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-13T10:00:00Z");
    private static final FetchedAttachment LOGO = FetchedAttachment.withoutText(
            "1.2", "logo.png", "image/png", 10L, true, ExtractionStatus.SKIPPED);
    private static final FetchedAttachment INVOICE = FetchedAttachment.withText(
            "2", "invoice.pdf", "application/pdf", 10L, false, "Total");

    private static EmailRecord record(long folderId, long uidValidity, long uid) {
        return new EmailRecord("gmail", folderId, uidValidity, uid, null, null, List.of(), Direction.INBOUND,
                null, null, List.of(), List.of(), null, null, null, RECEIVED, false, false, false,
                null, null, false, null, List.of());
    }

    private static EmailRecord record(long uid, Direction direction, List<String> references,
            List<FetchedAttachment> attachments) {
        return new EmailRecord("gmail", 1L, 100L, uid, "id@example.com", null, references, direction,
                "ana@example.com", "Ana", List.of("bob@example.com"), List.of(), "Re: Hello", "Hello",
                null, RECEIVED, false, false, false, "Hi", "Hi", false, null, attachments);
    }

    @Test
    void inlinePartsAreNotAttachments() {
        assertThat(record(1L, Direction.INBOUND, List.of(), List.of(LOGO)).hasAttachments()).isFalse();
        assertThat(record(1L, Direction.INBOUND, List.of(), List.of(LOGO, INVOICE)).hasAttachments()).isTrue();
    }

    @Test
    void rejectsANonPositiveNaturalKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> record(0L, 100L, 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> record(1L, 0L, 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> record(1L, 100L, 0L));
    }

    @Test
    void rejectsRepeatedAttachmentPartPaths() {
        FetchedAttachment samePart = FetchedAttachment.withoutText(
                "2", "other.bin", "application/octet-stream", 1L, false, ExtractionStatus.SKIPPED);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(1L, Direction.INBOUND, List.of(), List.of(INVOICE, samePart)));
    }

    @Test
    void namesTheMissingList() {
        assertThatNullPointerException()
                .isThrownBy(() -> record(1L, Direction.INBOUND, null, List.of()))
                .withMessage("referencesIds");
    }

    @Test
    void requiresADirection() {
        assertThatNullPointerException().isThrownBy(() -> record(1L, null, List.of(), List.of()));
    }

    @Test
    void acceptsUpToFiftyReferences() {
        List<String> fifty = Collections.nCopies(EmailRecord.MAX_REFERENCES, "ref@example.com");

        assertThat(record(1L, Direction.INBOUND, fifty, List.of()).referencesIds()).hasSize(50);
    }

    @Test
    void rejectsMoreThanFiftyReferences() {
        List<String> tooMany = Collections.nCopies(EmailRecord.MAX_REFERENCES + 1, "ref@example.com");

        assertThatIllegalArgumentException().isThrownBy(() -> record(1L, Direction.INBOUND, tooMany, List.of()));
    }

    @Test
    void doesNotPrintContentOrAddresses() {
        assertThat(record(1L, Direction.OUTBOUND, List.of(), List.of()).toString())
                .contains("uid=1", "OUTBOUND")
                .doesNotContain("ana@example.com", "Hello");
    }
}
