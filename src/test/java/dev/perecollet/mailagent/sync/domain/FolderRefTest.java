package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class FolderRefTest {

    @Test
    void rejectsABlankName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FolderRef("  ", FolderRole.INBOX));
    }

    @Test
    void rejectsANullName() {
        assertThatNullPointerException().isThrownBy(() -> new FolderRef(null, FolderRole.INBOX));
    }

    @Test
    void rejectsANullRole() {
        assertThatNullPointerException().isThrownBy(() -> new FolderRef("INBOX", null));
    }
}
