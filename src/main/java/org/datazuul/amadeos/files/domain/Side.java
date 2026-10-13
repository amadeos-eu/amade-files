package org.datazuul.amadeos.files.domain;

/**
 * One of the two file panels.
 */
public enum Side {
    LEFT, RIGHT;

    public Side other() {
        return this == LEFT ? RIGHT : LEFT;
    }
}
