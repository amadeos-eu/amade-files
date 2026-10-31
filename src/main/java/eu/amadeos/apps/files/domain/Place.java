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
 * @param address the server or share behind a {@link Kind#SERVER} or {@link Kind#SHARE}, null otherwise
 *
 * <p>The network entries that are no folder (Network, a server, a share that is not connected, Connect to
 * Server) have a relative path below {@code network:} that stands for them; a connected share has the folder
 * gvfs shows it in.
 */
public record Place(String name, Path path, Kind kind, Drive drive, NetworkAddress address) {

    public enum Kind {
        HOME,
        /** The {@code files} folder of the home trash. */
        TRASH,
        /** The root of the file system. */
        ROOT,
        /** A USB stick, disc or other removable medium, mounted or not. */
        MEDIA,
        /** The servers of the network, found or added by hand. */
        NETWORK,
        /** A Windows (SMB) server; its shares are below it. */
        SERVER,
        /** An SMB share, or an NFS, WebDAV, FTP or SFTP location; connected or not. */
        SHARE,
        /** The entry that opens Connect to Server. */
        CONNECT
    }

    /** The folder-less entries of the network all live below this relative path. */
    public static final Path NETWORK_PATH = Path.of("network:");
    public static final Path CONNECT_PATH = NETWORK_PATH.resolve("connect");

    public Place(String name, Path path, Kind kind) {
        this(name, path, kind, null, null);
    }

    public Place(String name, Path path, Kind kind, Drive drive) {
        this(name, path, kind, drive, null);
    }

    /** The relative path that stands for a server or a share that is not connected. */
    public static Path networkPath(NetworkAddress address) {
        String key = address.key().replace("://", "/");
        return NETWORK_PATH.resolve(key.endsWith("/") ? key.substring(0, key.length() - 1) : key);
    }

    /** A network entry without a folder: Network, a server, a share that is not connected, Connect to Server. */
    public boolean isVirtual() {
        return !path.isAbsolute();
    }

    /** A share that waits to be connected. */
    public boolean needsConnect() {
        return kind == Kind.SHARE && isVirtual();
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
