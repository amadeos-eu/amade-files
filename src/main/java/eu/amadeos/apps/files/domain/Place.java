package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A top-level entry of the folder tree.
 *
 * @param name shown in the tree
 * @param path the folder it stands for
 * @param kind decides the icon and what the context menu offers
 */
public record Place(String name, Path path, Kind kind) {

    public enum Kind {
        HOME,
        /** The {@code files} folder of the home trash. */
        TRASH,
        /** The root of the file system. */
        ROOT,
        /** A mounted USB stick, disc or other removable medium. */
        MEDIA
    }

    public Place {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }
}
