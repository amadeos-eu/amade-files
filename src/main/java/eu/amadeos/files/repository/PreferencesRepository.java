package eu.amadeos.files.repository;

import com.google.inject.Inject;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;
import eu.amadeos.files.domain.FilePreferences;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Stores {@link FilePreferences} in {@code files.conf}.
 */
public class PreferencesRepository {
    static final String FILE_NAME = "files.conf";

    private final Path file;

    @Inject
    public PreferencesRepository(AppDirectories directories) {
        this(directories.configFile(FILE_NAME));
    }

    PreferencesRepository(Path file) {
        this.file = file;
    }

    public synchronized FilePreferences load() {
        Properties p = PropertiesFile.load(file);
        FilePreferences d = FilePreferences.DEFAULTS;
        return new FilePreferences(
                Boolean.parseBoolean(p.getProperty("view.showHidden", Boolean.toString(d.showHidden()))),
                Boolean.parseBoolean(p.getProperty("view.twoPanels", Boolean.toString(d.twoPanels()))),
                path(p.getProperty("panel.left.dir")),
                path(p.getProperty("panel.right.dir")));
    }

    public synchronized void save(FilePreferences preferences) {
        Properties p = new Properties();
        p.setProperty("view.showHidden", Boolean.toString(preferences.showHidden()));
        p.setProperty("view.twoPanels", Boolean.toString(preferences.twoPanels()));
        if (preferences.leftDir() != null) p.setProperty("panel.left.dir", preferences.leftDir().toString());
        if (preferences.rightDir() != null) p.setProperty("panel.right.dir", preferences.rightDir().toString());
        PropertiesFile.store(file, p, "amade-files preferences");
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value.strip());
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
