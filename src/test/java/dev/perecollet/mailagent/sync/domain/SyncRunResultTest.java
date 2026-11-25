package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncRunResultTest {

    private static final Instant START = Instant.parse("2026-11-13T10:00:00Z");
    private static final Instant END = START.plusSeconds(5);

    @Test
    void successHasNoErrorMessage() {
        SyncRunResult result = SyncRunResult.success(1L, SyncKind.INCREMENTAL, START, END, 50, 0);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.messagesFetched()).isEqualTo(50);
    }

    @Test
    void failureKeepsTheMessagesFetchedBeforeTheError() {
        SyncRunResult result = SyncRunResult.failure(1L, SyncKind.INCREMENTAL, START, END, 50, "connection reset");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("connection reset");
        assertThat(result.messagesFetched()).isEqualTo(50);
    }

    @Test
    void rejectsFinishingBeforeStarting() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SyncRunResult.success(1L, SyncKind.FLAGS, END, START, 0, 0));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SyncRunResult.success(1L, SyncKind.FLAGS, START, END, -1, 0));
    }

    @Test
    void rejectsABlankErrorMessage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SyncRunResult.failure(1L, SyncKind.RECONCILE, START, END, 0, " "));
    }

    @Test
    void rejectsANonPositiveFolderId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SyncRunResult.success(0L, SyncKind.FLAGS, START, END, 0, 0));
    }
}
