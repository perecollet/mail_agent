package dev.perecollet.mailagent.sync.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The configured accounts. Ids are unique; the user's addresses span all of them (design D10). */
public record MailAccounts(List<MailAccount> all) {

    public MailAccounts {
        all = List.copyOf(all);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("At least one account is required");
        }
        Set<String> ids = new HashSet<>();
        for (MailAccount account : all) {
            if (!ids.add(account.id())) {
                throw new IllegalArgumentException("Duplicate account id: " + account.id());
            }
        }
    }

    public Optional<MailAccount> find(String id) {
        return all.stream().filter(account -> account.id().equals(id)).findFirst();
    }

    public List<String> ids() {
        return all.stream().map(MailAccount::id).toList();
    }

    /** Addresses of every account, so a reply sent from one account and received in another is still OUTBOUND. */
    public OwnAddresses ownAddresses() {
        return new OwnAddresses(all.stream().flatMap(account -> account.addresses().stream()).toList());
    }
}
