package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.DiscoveredServer;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.NetworkMount;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.SavedLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Network part of the folder tree, from what was found, what the user keeps, what is connected and
 * the share lists read so far:
 *
 * <pre>
 * Network
 *   MyCloudEX2Ultra          an SMB server: found, kept, or with a connected share
 *     Public                 connected: the gvfs folder, with its sub-folders below
 *     ralf                   not connected (in italics until chosen)
 *   office-pc (SFTP)         the other schemes have no shares: the location itself
 *   Connect to Server…
 * </pre>
 */
public final class NetworkPlaces {

    /**
     * @param top         the entries directly below Network
     * @param children    the shares below each SMB server, by the server's path
     * @param folderNames names for the folders of connected shares, for the path bar and the title
     * @param mounts      what is connected, for Copy Network Address and Disconnect
     */
    public record Tree(List<Place> top, Map<Path, List<Place>> children, Map<Path, String> folderNames,
                       List<NetworkMount> mounts) {
        public static final Tree EMPTY = new Tree(List.of(), Map.of(), Map.of(), List.of());

        /** The place for this path anywhere in the network part, or null. */
        public Place find(Path path) {
            for (Place place : top) if (place.path().equals(path)) return place;
            for (List<Place> shares : children.values()) for (Place place : shares) if (place.path().equals(path)) return place;
            return null;
        }

        /** The mount that holds this folder, or null. */
        public NetworkMount mountOf(Path folder) {
            NetworkMount best = null;
            for (NetworkMount mount : mounts) {
                if (folder.startsWith(mount.root()) && (best == null || mount.root().getNameCount() > best.root().getNameCount())) {
                    best = mount;
                }
            }
            return best;
        }
    }

    private NetworkPlaces() {
    }

    public static Place network() {
        return new Place("Network", Place.NETWORK_PATH, Place.Kind.NETWORK);
    }

    public static Place connectEntry() {
        return new Place("Connect to Server…", Place.CONNECT_PATH, Place.Kind.CONNECT);
    }

    private record Server(String name, NetworkAddress address) {
    }

    // --8<-- [start:build]
    public static Tree build(List<DiscoveredServer> found, List<SavedLocation> saved, List<NetworkMount> mounts,
                             Map<String, List<String>> shareLists) {
        Map<String, Server> servers = new LinkedHashMap<>();
        Map<String, Place> locations = new LinkedHashMap<>();
        Map<String, SavedLocation> savedByKey = new LinkedHashMap<>();
        for (SavedLocation location : saved) savedByKey.put(location.address().key(), location);

        for (DiscoveredServer server : found) {
            if (server.address().isSmb()) servers.putIfAbsent(server.address().key(), new Server(server.name(), server.address()));
            else locations.putIfAbsent(server.address().key(), location(server.name() + " (" + type(server.address()) + ")",
                    savedOr(savedByKey, server.address()), mounts));
        }
        for (SavedLocation location : saved) {
            NetworkAddress address = location.address();
            if (!location.keep()) continue;
            if (address.isSmb()) {
                SavedLocation savedServer = savedByKey.get(address.server().key());
                servers.putIfAbsent(address.server().key(), new Server(savedServer != null && !savedServer.name().isEmpty()
                        ? savedServer.name() : address.serverName(), address.server()));
            } else {
                locations.putIfAbsent(address.key(), location(location.name().isEmpty() ? label(address) : location.name(),
                        address, mounts));
            }
        }
        for (NetworkMount mount : mounts) {
            NetworkAddress address = mount.address();
            if (address.isSmb()) servers.putIfAbsent(address.server().key(), new Server(address.serverName(), address.server()));
            else if (locations.values().stream().noneMatch(p -> mount.root().equals(p.path()))) {
                locations.putIfAbsent(address.key(), location(label(address), address, mounts));
            }
        }

        List<Place> top = new ArrayList<>();
        Map<Path, List<Place>> children = new LinkedHashMap<>();
        Map<Path, String> names = new LinkedHashMap<>();
        List<Server> sorted = new ArrayList<>(servers.values());
        sorted.sort(Comparator.comparing(Server::name, String.CASE_INSENSITIVE_ORDER));
        for (Server server : sorted) {
            SavedLocation savedServer = savedByKey.get(server.address().key());
            NetworkAddress serverAddress = savedServer != null ? savedServer.address() : server.address();
            Place serverPlace = new Place(server.name(), Place.networkPath(server.address()), Place.Kind.SERVER, null, serverAddress);
            top.add(serverPlace);
            children.put(serverPlace.path(), shares(server, shareLists.get(server.address().key()), saved, mounts, names));
        }
        top.addAll(locations.values());
        for (Place location : locations.values()) if (!location.isVirtual()) names.put(location.path(), location.name());
        top.add(connectEntry());
        return new Tree(top, children, names, mounts);
    }
    // --8<-- [end:build]

    /** The shares of one server: listed, kept or connected; sorted by name. */
    private static List<Place> shares(Server server, List<String> listed, List<SavedLocation> saved, List<NetworkMount> mounts,
                                      Map<Path, String> names) {
        Map<String, NetworkAddress> shares = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        String serverKey = server.address().key();
        if (listed != null) {
            for (String share : listed) {
                NetworkAddress address = server.address().withLogin("", "").withPath(share);
                shares.putIfAbsent(address.key(), address);
            }
        }
        for (SavedLocation location : saved) {
            NetworkAddress address = location.address();
            if (!address.isSmb() || address.share().isEmpty() || !address.server().key().equals(serverKey)) continue;
            NetworkAddress share = address.shareAddress();
            shares.put(share.key(), share); // the saved one carries the user
            if (!location.name().isEmpty()) labels.put(share.key(), location.name());
        }
        for (NetworkMount mount : mounts) {
            NetworkAddress address = mount.address();
            if (address.isSmb() && address.server().key().equals(serverKey)) {
                // gvfs writes the share in lower case: the listed spelling stays, with the login of the mount.
                NetworkAddress known = shares.get(address.key());
                if (known == null) shares.put(address.key(), address);
                else if (known.user().isEmpty()) shares.put(address.key(), known.withLogin(address.domain(), address.user()));
            }
        }
        List<Place> places = new ArrayList<>();
        for (Map.Entry<String, NetworkAddress> entry : shares.entrySet()) {
            NetworkAddress address = entry.getValue();
            String name = labels.getOrDefault(entry.getKey(), address.share());
            Path folder = folderOf(address, mounts);
            Path path = folder != null ? folder : Place.networkPath(address);
            places.add(new Place(name, path, Place.Kind.SHARE, null, address));
            if (folder != null) names.put(folder, server.name() + " › " + name);
        }
        places.sort(Comparator.comparing(Place::name, String.CASE_INSENSITIVE_ORDER));
        return places;
    }

    private static Place location(String name, NetworkAddress address, List<NetworkMount> mounts) {
        Path folder = folderOf(address, mounts);
        return new Place(name, folder != null ? folder : Place.networkPath(address), Place.Kind.SHARE, null, address);
    }

    private static NetworkAddress savedOr(Map<String, SavedLocation> saved, NetworkAddress address) {
        SavedLocation location = saved.get(address.key());
        return location != null ? location.address() : address;
    }

    /** The folder that shows {@code address} if it is connected, or null; the same user first. */
    public static Path folderOf(NetworkAddress address, List<NetworkMount> mounts) {
        Path best = null;
        for (NetworkMount mount : mounts) {
            Path folder = mount.folderOf(address);
            if (folder != null && (best == null || mount.address().user().equalsIgnoreCase(address.user()))) best = folder;
        }
        return best;
    }

    /** "cloud.example.org (WebDAV)", with the folder if there is one. */
    static String label(NetworkAddress address) {
        String where = address.serverName() + (address.path().isEmpty() ? "" : "/" + address.path());
        return where + " (" + type(address) + ")";
    }

    static String type(NetworkAddress address) {
        NetworkAddress.Type type = NetworkAddress.Type.of(address.scheme());
        if (type == null) return address.scheme().toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case DAV, DAVS -> "WebDAV";
            case SFTP -> "SFTP";
            default -> type.scheme().toUpperCase(java.util.Locale.ROOT);
        };
    }
}
