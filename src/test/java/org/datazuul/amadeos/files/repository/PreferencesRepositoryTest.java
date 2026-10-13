package org.datazuul.amadeos.files.repository;

import org.datazuul.amadeos.files.domain.FilePreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesRepositoryTest {
    @TempDir
    Path dir;

    @Test
    void defaultsWithoutFile() {
        assertEquals(FilePreferences.DEFAULTS, new PreferencesRepository(dir.resolve("files.conf")).load());
    }

    @Test
    void roundTrip() {
        PreferencesRepository repository = new PreferencesRepository(dir.resolve("files/files.conf"));
        FilePreferences preferences = new FilePreferences(true, false, Path.of("/home/ralf"), Path.of("/media/usb"));
        repository.save(preferences);
        assertEquals(preferences, repository.load());
    }

    @Test
    void ignoresRelativeAndBrokenPaths() throws IOException {
        Path file = dir.resolve("files.conf");
        Files.writeString(file, "panel.left.dir=relative/dir\nview.twoPanels=nonsense\n");
        FilePreferences loaded = new PreferencesRepository(file).load();
        assertNull(loaded.leftDir());
        assertFalse(loaded.twoPanels());
    }
}
