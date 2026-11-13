package dev.perecollet.mailagent.sync.domain;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every address that is "the user", across all configured accounts. An email is {@code OUTBOUND} when its
 * sender is one of them, wherever it is stored (design D10).
 *
 * <p>Addresses are compared as bare {@code local@domain} strings, ignoring case and surrounding spaces; callers
 * pass the address without display name or angle brackets.
 */
public final class OwnAddresses {

    private final Set<String> addresses;

    public OwnAddresses(Collection<String> addresses) {
        this.addresses = Objects.requireNonNull(addresses, "addresses").stream()
                .map(OwnAddresses::normalize)
                .filter(address -> !address.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean contains(String address) {
        if (address == null) {
            return false;
        }
        String normalized = normalize(address);
        return !normalized.isEmpty() && addresses.contains(normalized);
    }

    private static String normalize(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        // Personal addresses stay out of logs.
        return "OwnAddresses[count=" + addresses.size() + "]";
    }
}
