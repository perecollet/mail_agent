package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FetchedMessageTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-13T10:00:00Z");

    private static FetchedMessage message(long uid, Instant receivedAt, List<String> to) {
        return message(uid, receivedAt, to, List.of());
    }

    private static FetchedMessage message(long uid, Instant receivedAt, List<String> to,
            List<FetchedAttachment> attachments) {
        return new FetchedMessage(uid, receivedAt, null, "<id@example.com>", null, null,
                "ana@example.com", "Ana", to, List.of(), "Hello", false, false, false,
                "Hi there", null, attachments);
    }

    @Test
    void rejectsRepeatedAttachmentPartPaths() {
        FetchedAttachment part = FetchedAttachment.withoutText(
                "2", "a.bin", "application/octet-stream", 1L, false, ExtractionStatus.SKIPPED);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> message(1L, RECEIVED, List.of(), List.of(part, part)));
    }

    @Test
    void rejectsANonPositiveUid() {
        assertThatIllegalArgumentException().isThrownBy(() -> message(0L, RECEIVED, List.of()));
    }

    @Test
    void requiresTheReceivedDate() {
        assertThatNullPointerException().isThrownBy(() -> message(1L, null, List.of()));
    }

    @Test
    void isNotAffectedByLaterChangesToTheSourceLists() {
        List<String> to = new ArrayList<>(List.of("bob@example.com"));
        FetchedMessage message = message(1L, RECEIVED, to);

        to.add("intruder@example.com");

        assertThat(message.toAddresses()).containsExactly("bob@example.com");
    }

    @Test
    void doesNotPrintContentOrAddresses() {
        assertThat(message(1L, RECEIVED, List.of("bob@example.com")).toString())
                .doesNotContain("ana@example.com", "bob@example.com", "Hello", "Hi there");
    }
}
