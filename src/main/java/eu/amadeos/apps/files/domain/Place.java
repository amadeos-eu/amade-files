package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A top-level entry of the folder tree.
 *
 * @param name shown in the tree
 * @param path the folder it stands for
 * @param kind decides the icon and what the context menu offers
 * @param drive the USB stick or disc behind a {@link Kind#MEDIA} place, null for other places and for media
 *              that were mounted by other means; while a drive is not mounted, {@code path} is its device
 */
public record Place(String name, Path path, Kind kind, Drive drive) {

    public enum Kind {
        HOME,
        /** The {@code files} folder of the home trash. */
        TRASH,
        /** The root of the file system. */
        ROOT,
        /** A USB stick, disc or other removable medium, mounted or not. */
        MEDIA
    }

    public Place(String name, Path path, Kind kind) {
        this(name, path, kind, null);
    }

    /** A drive that is plugged in but not mounted: choosing it mounts it. */
    public static Place unmounted(Drive drive) {
        return new Place(drive.name(), Path.of(drive.device()), Kind.MEDIA, drive);
    }

    public static Place mounted(Drive drive) {
        return new Place(drive.name(), drive.mountPoint(), Kind.MEDIA, drive);
    }

    /** A drive that waits to be mounted. */
    public boolean needsMount() {
        return drive != null && !drive.mounted();
    }

    public Place {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }
}
