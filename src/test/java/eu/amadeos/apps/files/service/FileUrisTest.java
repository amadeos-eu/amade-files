package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.ClipboardFiles;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileUrisTest {
    private final Path spaced = Path.of("/home/ralf/Meine Dateien/brief #1.odt");
    private final Path folder = Path.of("/home/ralf/Dokumente");

    @Test
    void urisAreEscapedAndFoldersHaveNoTrailingSlash() {
        assertEquals("file:///home/ralf/Meine%20Dateien/brief%20%231.odt", FileUris.uri(spaced));
        assertEquals("file:///", FileUris.uri(Path.of("/")));
        assertEquals("file:///home/ralf/Dokumente\r\nfile:///home/ralf/Meine%20Dateien/brief%20%231.odt\r\n",
                FileUris.uriList(List.of(folder, spaced)));
    }

    @Test
    void uriListRoundTripSkipsCommentsAndRemoteFiles() {
        String text = "# from a browser\r\n" + FileUris.uriList(List.of(spaced, folder))
                + "https://example.org/x\r\nfile://otherhost/etc/passwd\r\nfile://localhost/tmp/x\r\n";
        assertEquals(List.of(spaced, folder, Path.of("/tmp/x")), FileUris.parseUriList(text));
    }

    @Test
    void gnomeCopiedFilesTellsCutFromCopy() {
        ClipboardFiles cut = new ClipboardFiles(List.of(spaced, folder), true);
        String text = FileUris.gnomeCopiedFiles(cut);
        assertTrue(text.startsWith("cut\nfile:///home/ralf/Meine%20Dateien/"), text);
        assertEquals(cut, FileUris.parseGnomeCopiedFiles(text));
        assertEquals(new ClipboardFiles(List.of(folder), false), FileUris.parseGnomeCopiedFiles("copy\nfile:///home/ralf/Dokumente\n"));
        assertNull(FileUris.parseGnomeCopiedFiles("hello"));
    }
}
