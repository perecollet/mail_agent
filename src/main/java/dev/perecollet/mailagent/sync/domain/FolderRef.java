package dev.perecollet.mailagent.sync.domain;

import java.util.Objects;

/**
 * A folder on the IMAP server: its full name as the server reports it (e.g. {@code [Gmail]/Sent Mail})
 * and the role it plays for the synchronizer.
 */
public record FolderRef(String name, FolderRole role) {

    public FolderRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Folder name must not be blank");
        }
    }
}
