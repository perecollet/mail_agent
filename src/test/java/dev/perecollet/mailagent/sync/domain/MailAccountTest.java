package dev.perecollet.mailagent.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MailAccountTest {

    static MailAccount account(String id, List<String> addresses) {
        return new MailAccount(id, "imap.example.com", 993, true, "user", "s3cret", addresses, List.of("INBOX", "\\Sent"));
    }

    @Test
    void neverPrintsThePassword() {
        assertThat(account("gmail", List.of("user@example.com")).toString())
                .doesNotContain("s3cret")
                .contains("password=***");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Gmail", "my account", "", "a_b", "abcdefghijklmnopqrstuvwxyz0123456"})
    void rejectsInvalidIds(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> account(id, List.of("user@example.com")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536})
    void rejectsInvalidPorts(int port) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccount(
                "gmail", "imap.example.com", port, true, "user", "pw", List.of("user@example.com"), List.of("INBOX")));
    }

    @Test
    void rejectsABlankPassword() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccount(
                "gmail", "imap.example.com", 993, true, "user", " ", List.of("user@example.com"), List.of("INBOX")));
    }

    @Test
    void rejectsANullHost() {
        assertThatNullPointerException().isThrownBy(() -> new MailAccount(
                "gmail", null, 993, true, "user", "pw", List.of("user@example.com"), List.of("INBOX")));
    }

    @Test
    void requiresAddressesAndFolders() {
        assertThatIllegalArgumentException().isThrownBy(() -> account("gmail", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccount(
                "gmail", "imap.example.com", 993, true, "user", "pw", List.of("user@example.com"), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-at-sign", "@example.com"})
    void rejectsInvalidAddresses(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> account("gmail", List.of("user@example.com", address)));
    }

    @Test
    void rejectsABlankFolder() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccount(
                "gmail", "imap.example.com", 993, true, "user", "pw", List.of("user@example.com"), List.of("INBOX", " ")));
    }

    @Test
    void isNotAffectedByLaterChangesToTheSourceLists() {
        List<String> addresses = new ArrayList<>(List.of("user@example.com"));
        MailAccount account = account("gmail", addresses);

        addresses.add("intruder@example.com");

        assertThat(account.addresses()).containsExactly("user@example.com");
    }
}
