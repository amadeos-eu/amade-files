package eu.amadeos.apps.files.domain;

/**
 * What dropping files on a folder does, from the keys held while dragging.
 */
public enum DropMode {
    /** No key: move on the same file system, copy onto another one. */
    AUTO,
    /** Ctrl held. */
    COPY,
    /** Shift held. */
    MOVE
}
