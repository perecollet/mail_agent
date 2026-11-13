package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FolderCursorTest {

    private static final FolderRef INBOX = new FolderRef("INBOX", FolderRole.INBOX);

    private static FolderCursor cursor(Long uidValidity, long lastUid) {
        return new FolderCursor(1L, "gmail", INBOX, uidValidity, lastUid);
    }

    @Nested
    class WhenUninitialized {

        private final FolderCursor cursor = FolderCursor.uninitialized(1L, "gmail", INBOX);

        @Test
        void isNotInitialized() {
            assertThat(cursor.isInitialized()).isFalse();
            assertThat(cursor.uidValidity()).isNull();
        }

        @Test
        void needsInitialSync() {
            assertThat(cursor.needsInitialSync()).isTrue();
        }

        @Test
        void isNotValidForAnyServerUidValidity() {
            assertThat(cursor.isValidFor(1L)).isFalse();
        }
    }

    @Nested
    class ResetTo {

        @Test
        void setsTheNewUidValidityAndForgetsTheLastUid() {
            FolderCursor reset = cursor(100L, 500L).resetTo(200L);

            assertThat(reset.uidValidity()).isEqualTo(200L);
            assertThat(reset.lastUid()).isZero();
            assertThat(reset.needsInitialSync()).isTrue();
        }

        @Test
        void keepsTheFolderIdentity() {
            FolderCursor reset = cursor(100L, 500L).resetTo(200L);

            assertThat(reset.folderId()).isEqualTo(1L);
            assertThat(reset.accountId()).isEqualTo("gmail");
            assertThat(reset.folder()).isEqualTo(INBOX);
        }

        @Test
        void initializesAnUninitializedCursor() {
            FolderCursor initialized = FolderCursor.uninitialized(1L, "gmail", INBOX).resetTo(7L);

            assertThat(initialized.isInitialized()).isTrue();
            assertThat(initialized.isValidFor(7L)).isTrue();
        }
    }

    @Nested
    class AdvancedTo {

        @Test
        void movesForwardToAHigherUid() {
            FolderCursor advanced = cursor(100L, 10L).advancedTo(25L);

            assertThat(advanced.lastUid()).isEqualTo(25L);
            assertThat(advanced.uidValidity()).isEqualTo(100L);
            assertThat(advanced.needsInitialSync()).isFalse();
        }

        @Test
        void neverMovesBackwards() {
            FolderCursor original = cursor(100L, 10L);

            assertThat(original.advancedTo(5L)).isSameAs(original);
        }

        @Test
        void staysPutForTheSameUid() {
            FolderCursor original = cursor(100L, 10L);

            assertThat(original.advancedTo(10L)).isSameAs(original);
        }

        @Test
        void isRejectedOnAnUninitializedCursor() {
            FolderCursor uninitialized = FolderCursor.uninitialized(1L, "gmail", INBOX);

            assertThatIllegalStateException().isThrownBy(() -> uninitialized.advancedTo(25L));
        }

        @Test
        void doesNotModifyTheOriginalCursor() {
            FolderCursor original = cursor(100L, 10L);

            original.advancedTo(25L);

            assertThat(original.lastUid()).isEqualTo(10L);
        }
    }

    @Nested
    class IsValidFor {

        @Test
        void isTrueForTheSameUidValidity() {
            assertThat(cursor(100L, 10L).isValidFor(100L)).isTrue();
        }

        @Test
        void isFalseWhenTheServerChangedIt() {
            assertThat(cursor(100L, 10L).isValidFor(101L)).isFalse();
        }
    }

    @Nested
    class Construction {

        @Test
        void rejectsANegativeLastUid() {
            assertThatIllegalArgumentException().isThrownBy(() -> cursor(100L, -1L));
        }

        @Test
        void rejectsANonPositiveUidValidity() {
            assertThatIllegalArgumentException().isThrownBy(() -> cursor(0L, 0L));
            assertThatIllegalArgumentException().isThrownBy(() -> cursor(100L, 0L).resetTo(-5L));
        }

        @Test
        void rejectsANullAccountId() {
            assertThatNullPointerException().isThrownBy(() -> new FolderCursor(1L, null, INBOX, 100L, 0L));
        }

        @Test
        void rejectsANullFolder() {
            assertThatNullPointerException().isThrownBy(() -> new FolderCursor(1L, "gmail", null, 100L, 0L));
        }

        @Test
        void exposesTheFolderName() {
            assertThat(cursor(100L, 0L).folderName()).isEqualTo("INBOX");
        }
    }
}