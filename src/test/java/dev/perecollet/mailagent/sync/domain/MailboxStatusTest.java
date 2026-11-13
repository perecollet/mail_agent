package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MailboxStatusTest {

    @Test
    void reportsWhetherTheServerSentUidNext() {
        assertThat(new MailboxStatus(7L, 120L, 3).hasUidNext()).isTrue();
        assertThat(new MailboxStatus(7L, null, 3).hasUidNext()).isFalse();
    }

    @Test
    void isEmptyWithoutMessages() {
        assertThat(new MailboxStatus(7L, 1L, 0).isEmpty()).isTrue();
        assertThat(new MailboxStatus(7L, 2L, 1).isEmpty()).isFalse();
    }

    @Test
    void rejectsANonPositiveUidValidity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailboxStatus(0L, 1L, 0));
    }

    @Test
    void rejectsANonPositiveUidNext() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailboxStatus(7L, 0L, 0));
    }

    @Test
    void rejectsANegativeMessageCount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailboxStatus(7L, 1L, -1));
    }
}
