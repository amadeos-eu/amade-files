package eu.amadeos.apps.files.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.SavedLocation;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Servers and shares under Network and the recent addresses of Connect to Server, in {@code network.conf}
 * next to {@code files.conf}. No passwords: those are in the login keyring.
 *
 * <pre>
 * location.1.address=smb://WORKGROUP;ralf@mycloudex2ultra.local/ralf
 * location.1.name=
 * location.1.guest=false
 * location.1.keep=true
 * location.1.autoConnect=true
 * recent.1=smb://mycloudex2ultra.local/Public
 * </pre>
 */
public class NetworkRepository {
    static final String FILE_NAME = "network.conf";
    public static final int MAX_RECENT = 8;

    private final Path file;

    @Inject
    public NetworkRepository(AppDirectories directories) {
        this(directories.configFile(FILE_NAME));
    }

    public NetworkRepository(Path file) {
        this.file = file;
    }

    public record Data(List<SavedLocation> locations, List<NetworkAddress> recent) {
        public static final Data EMPTY = new Data(List.of(), List.of());

        public Data {
            locations = List.copyOf(locations);
            recent = List.copyOf(recent);
        }

        /** The saved entry for the same server or share, whatever the user; null if there is none. */
        public SavedLocation find(NetworkAddress address) {
            for (SavedLocation location : locations) if (location.address().key().equals(address.key())) return location;
            return null;
        }

        /** Adds or replaces the entry for the same place. */
        public Data with(SavedLocation location) {
            List<SavedLocation> list = new ArrayList<>(locations);
            list.removeIf(l -> l.address().key().equals(location.address().key()));
            list.add(location);
            return new Data(list, recent);
        }

        public Data without(NetworkAddress address) {
            List<SavedLocation> list = new ArrayList<>(locations);
            list.removeIf(l -> l.address().key().equals(address.key()));
            return new Data(list, recent);
        }

        /** The address at the top of the recent list, without user; the list keeps {@link #MAX_RECENT}. */
        public Data withRecent(NetworkAddress address) {
            NetworkAddress plain = address.withLogin("", "");
            List<NetworkAddress> list = new ArrayList<>(recent);
            list.removeIf(a -> a.key().equals(plain.key()));
            list.addFirst(plain);
            while (list.size() > MAX_RECENT) list.removeLast();
            return new Data(locations, list);
        }

        public boolean anyAutoConnect() {
            return locations.stream().anyMatch(SavedLocation::autoConnect);
        }
    }

    public synchronized Data load() {
        Properties p = PropertiesFile.load(file);
        List<SavedLocation> locations = new ArrayList<>();
        for (int i = 1; p.getProperty("location." + i + ".address") != null; i++) {
            String prefix = "location." + i + ".";
            try {
                locations.add(new SavedLocation(p.getProperty(prefix + "name", ""),
                        NetworkAddress.parse(p.getProperty(prefix + "address")),
                        Boolean.parseBoolean(p.getProperty(prefix + "guest")),
                        Boolean.parseBoolean(p.getProperty(prefix + "keep", "true")),
                        Boolean.parseBoolean(p.getProperty(prefix + "autoConnect"))));
            } catch (IllegalArgumentException ignored) {
                // an address edited by hand that cannot be read
            }
        }
        List<NetworkAddress> recent = new ArrayList<>();
        for (int i = 1; p.getProperty("recent." + i) != null; i++) {
            try {
                recent.add(NetworkAddress.parse(p.getProperty("recent." + i)));
            } catch (IllegalArgumentException ignored) {
                // as above
            }
        }
        return new Data(locations, recent);
    }

    public synchronized void save(Data data) {
        Properties p = new Properties();
        int i = 1;
        for (SavedLocation location : data.locations()) {
            String prefix = "location." + i++ + ".";
            p.setProperty(prefix + "address", location.address().uri());
            p.setProperty(prefix + "name", location.name());
            p.setProperty(prefix + "guest", Boolean.toString(location.guest()));
            p.setProperty(prefix + "keep", Boolean.toString(location.keep()));
            p.setProperty(prefix + "autoConnect", Boolean.toString(location.autoConnect()));
        }
        i = 1;
        for (NetworkAddress address : data.recent()) p.setProperty("recent." + i++, address.uri());
        PropertiesFile.store(file, p, "amade-files network servers and shares (passwords are in the login keyring)");
    }
}
