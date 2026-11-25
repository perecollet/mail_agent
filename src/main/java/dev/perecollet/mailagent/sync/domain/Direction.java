package dev.perecollet.mailagent.sync.domain;

/** Whether the user received or sent an email; decided by the sender, not the folder (design D10). */
public enum Direction {
    INBOUND,
    OUTBOUND
}
