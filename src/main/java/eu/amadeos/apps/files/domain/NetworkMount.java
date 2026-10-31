package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A server or share that gvfs has connected, with the folder below {@code $XDG_RUNTIME_DIR/gvfs} it shows it in.
 *
 * @param address what is connected: for SMB the share, for the other schemes the server with gvfs's own prefix
 * @param root    the folder that shows it, such as {@code /run/user/1000/gvfs/smb-share:server=nas,share=public}
 */
public record NetworkMount(NetworkAddress address, Path root) {

    public NetworkMount {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(root, "root");
    }

    /**
     * The folder of {@code location} in this mount, or null if the mount does not hold it: for SMB the share
     * must match, for the other schemes the location must lie below the mount's prefix.
     */
    public Path folderOf(NetworkAddress location) {
        if (!NetworkAddress.hostKey(location.host()).equals(NetworkAddress.hostKey(address.host()))
                || location.port() != address.port() || !family(location).equals(family(address))) {
            return null;
        }
        String below;
        if (location.isSmb()) {
            if (!location.share().equalsIgnoreCase(address.share())) return null;
            below = location.path().substring(location.share().length());
        } else {
            String prefix = address.path();
            if (!prefix.isEmpty() && !(location.path().equals(prefix) || location.path().startsWith(prefix + "/"))) return null;
            below = location.path().substring(prefix.length());
        }
        while (below.startsWith("/")) below = below.substring(1);
        return below.isEmpty() ? root : root.resolve(below);
    }

    /** The network address of a folder in this mount, or null if it lies outside. */
    public NetworkAddress addressOf(Path folder) {
        if (!folder.startsWith(root)) return null;
        String rel = root.relativize(folder).toString();
        String base = address.path();
        return address.withPath(base.isEmpty() ? rel : rel.isEmpty() ? base : base + "/" + rel);
    }

    private static String family(NetworkAddress a) {
        return a.scheme().equals("davs") ? "dav" : a.scheme();
    }
}
