package org.datazuul.amadeos.files.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * One line in a file panel.
 *
 * @param path        absolute path; for {@link Kind#PARENT} the parent folder
 * @param name        file name, {@code ..} for the parent entry
 * @param kind        what the entry is; symbolic links have the kind of their target
 * @param size        bytes, 0 for folders
 * @param modified    last modification, null if unknown
 * @param permissions {@code rwxr-xr-x} style, empty if the file system has none
 * @param symlink     the entry is a symbolic link
 * @param executable  the owner may execute the file
 */
public record FileEntry(Path path, String name, Kind kind, long size, Instant modified, String permissions,
                        boolean symlink, boolean executable) {

    public enum Kind {
        /** The {@code ..} line leading to the parent folder. */
        PARENT,
        DIRECTORY,
        FILE,
        /** Devices, sockets, pipes and broken links. */
        OTHER
    }

    public FileEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        permissions = permissions == null ? "" : permissions;
    }

    public static FileEntry parent(Path parent) {
        return new FileEntry(parent, "..", Kind.PARENT, 0, null, "", false, false);
    }

    public boolean isParent() {
        return kind == Kind.PARENT;
    }

    /** Folders and the parent entry; Enter changes into them. */
    public boolean isDirectory() {
        return kind == Kind.DIRECTORY || kind == Kind.PARENT;
    }

    public boolean isHidden() {
        return !isParent() && name.startsWith(".");
    }
}
