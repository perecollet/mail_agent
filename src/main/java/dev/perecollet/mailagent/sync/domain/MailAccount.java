package dev.perecollet.mailagent.sync.domain;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An IMAP account to synchronize, free of configuration framework types.
 *
 * @param addresses every address that is the user in this account, aliases included
 * @param folders   folders to synchronize: real names, or special-use attributes such as {@code \Sent}
 */
public record MailAccount(
        String id,
        String host,
        int port,
        boolean ssl,
        String username,
        String password,
        List<String> addresses,
        List<String> folders) {

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");

    public MailAccount {
        Objects.requireNonNull(id, "id");
        requireNotBlank(host, "host");
        requireNotBlank(username, "username");
        requireNotBlank(password, "password");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Account id must match " + ID + ": " + id);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        addresses = List.copyOf(addresses);
        folders = List.copyOf(folders);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("Account " + id + " needs at least one address");
        }
        if (folders.isEmpty()) {
            throw new IllegalArgumentException("Account " + id + " needs at least one folder");
        }
        for (String address : addresses) {
            requireNotBlank(address, "address");
            if (address.indexOf('@') < 1) {
                throw new IllegalArgumentException("Not an email address in account " + id + ": " + address);
            }
        }
        folders.forEach(folder -> requireNotBlank(folder, "folder"));
    }

    private static void requireNotBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Never print the password. */
    @Override
    public String toString() {
        return "MailAccount[id=%s, host=%s, port=%d, ssl=%s, username=%s, password=***, folders=%s]"
                .formatted(id, host, port, ssl, username, folders);
    }
}
