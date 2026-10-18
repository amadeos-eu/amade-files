package eu.amadeos.files.service;

import eu.amadeos.commons.desktop.DesktopLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenerTest {
    @TempDir
    Path dir;

    private final DesktopLauncher desktop = mock(DesktopLauncher.class);

    /** A fake amade-pad that writes its argument into {@code opened}. */
    private Path fakeEditor() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path editor = Files.writeString(bin.resolve(Opener.EDITOR),
                "#!/bin/sh\nprintf '%s' \"$1\" > \"" + dir.resolve("opened") + "\"\n");
        Files.setPosixFilePermissions(editor, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private String waitForOpened() throws Exception {
        Path opened = dir.resolve("opened");
        Instant end = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(end)) {
            if (Files.exists(opened) && Files.size(opened) > 0) return Files.readString(opened);
            Thread.sleep(20);
        }
        return null;
    }

    @Test
    void plainTextOpensInTheTextEditor() throws Exception {
        Path log = Files.writeString(dir.resolve(".bash_logout"), "# ~/.bash_logout\nclear\n");
        new Opener(desktop, fakeEditor().toString()).open(log);
        assertEquals(log.toString(), waitForOpened());
        verifyNoInteractions(desktop);
    }

    @Test
    void otherFilesUseTheDefaultApplication() throws Exception {
        Path binary = Files.write(dir.resolve("data.bin"), new byte[]{1, 0, 2, 3});
        new Opener(desktop, fakeEditor().toString()).open(binary);
        verify(desktop).open(binary);
    }

    @Test
    void missingXdgOpenIsExplained() throws Exception {
        Path binary = Files.write(dir.resolve("photo.jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, 0});
        doThrow(new IOException("Failed to show URI")).when(desktop).open(binary);
        FileOperationException e = assertThrows(FileOperationException.class,
                () -> new Opener(desktop, dir.resolve("empty-path").toString()).open(binary));
        assertTrue(e.getMessage().contains("xdg-utils"), e.getMessage());
    }

    @Test
    void recognisesPlainText() throws IOException {
        assertTrue(Opener.isPlainText(Files.writeString(dir.resolve("README"), "Hello\tworld\r\n")));
        assertTrue(Opener.isPlainText(Files.writeString(dir.resolve("app.log"), "\u001b[32mINFO\u001b[0m ok\n")));
        assertTrue(Opener.isPlainText(Files.writeString(dir.resolve("notes.txt"), "Grüße\n")));
        assertTrue(Opener.isPlainText(Files.writeString(dir.resolve("empty"), "")));
        assertFalse(Opener.isPlainText(Files.write(dir.resolve("blob"), new byte[]{'a', 0, 'b'})));
        assertFalse(Opener.isPlainText(Files.write(dir.resolve("ctrl"), new byte[]{1, 2, 3, 4, 5, 'a'})));
        assertFalse(Opener.isPlainText(dir.resolve("missing")));
    }
}
