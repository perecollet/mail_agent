package dev.perecollet.mailagent.sync.domain;

import static dev.perecollet.mailagent.sync.domain.MailAccountTest.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailAccountsTest {

    private final MailAccounts accounts = new MailAccounts(List.of(
            account("gmail", List.of("ana@gmail.example")),
            account("icloud", List.of("ana@icloud.example", "alias@me.example"))));

    @Test
    void ownAddressesSpanEveryAccount() {
        OwnAddresses own = accounts.ownAddresses();

        assertThat(own.contains("ana@gmail.example")).isTrue();
        assertThat(own.contains("alias@me.example")).isTrue();
        assertThat(own.contains("someone@else.example")).isFalse();
    }

    @Test
    void findsAnAccountById() {
        assertThat(accounts.find("icloud")).get().extracting(MailAccount::id).isEqualTo("icloud");
        assertThat(accounts.find("movistar")).isEmpty();
    }

    @Test
    void listsIdsInConfigurationOrder() {
        assertThat(accounts.ids()).containsExactly("gmail", "icloud");
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccounts(List.of(
                account("gmail", List.of("a@example.com")),
                account("gmail", List.of("b@example.com")))));
    }

    @Test
    void requiresAtLeastOneAccount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MailAccounts(List.of()));
    }
}
