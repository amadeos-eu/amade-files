package eu.amadeos.apps.files.service;

import com.google.inject.Inject;
import eu.amadeos.commons.config.XdgDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Connect again at login": while at least one server or share wants it, {@code ~/.config/autostart} holds an
 * entry that runs {@code amade-files --connect-saved} once at login (amadeos-session starts the entries there,
 * Settings &gt; Startup lists it). The entry goes away with the last such share.
 */
public class NetworkAutostart {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkAutostart.class);
    static final String FILE_NAME = "amade-files-network.desktop";
    public static final String ARGUMENT = "--connect-saved";

    // --8<-- [start:entry]
    static final String ENTRY = """
            [Desktop Entry]
            Type=Application
            Name=Network Drives
            Comment=Connects the network shares marked "Connect again at login" in the File Manager
            Exec=amade-files --connect-saved
            TryExec=amade-files
            Icon=amade-files
            Terminal=false
            NoDisplay=true
            """;
    // --8<-- [end:entry]

    private final Path file;

    @Inject
    public NetworkAutostart() {
        this(XdgDirectories.system().configHome().resolve("autostart").resolve(FILE_NAME));
    }

    NetworkAutostart(Path file) {
        this.file = file;
    }

    /** Writes or removes the entry. */
    public void update(boolean wanted) {
        try {
            if (wanted) {
                Files.createDirectories(file.getParent());
                if (!Files.exists(file) || !Files.readString(file).equals(ENTRY)) Files.writeString(file, ENTRY);
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.warn("Cannot update {}", file, e);
        }
    }
}
