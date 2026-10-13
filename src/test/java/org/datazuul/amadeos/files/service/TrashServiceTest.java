package org.datazuul.amadeos.files.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrashServiceTest {
    @TempDir
    Path dir;

    private Path trashDir;
    private TrashService trash;

    @BeforeEach
    void setUp() {
        trashDir = dir.resolve("data/Trash");
        trash = new TrashService(trashDir, Clock.fixed(Instant.parse("2026-10-13T18:15:30Z"), ZoneOffset.UTC), 1000);
    }

    @Test
    void movesToTheHomeTrashWithInfo() throws IOException {
        Path file = Files.writeString(dir.resolve("my report.odt"), "r");

        Path trashed = trash.trash(file);

        assertFalse(Files.exists(file));
        assertEquals(trashDir.resolve("files/my report.odt"), trashed);
        assertEquals("r", Files.readString(trashed));
        assertEquals("[Trash Info]\nPath=" + TrashService.encodePath(file.toString())
                        + "\nDeletionDate=2026-10-13T18:15:30\n",
                Files.readString(trashDir.resolve("info/my report.odt.trashinfo")));
        assertTrue(Files.readString(trashDir.resolve("info/my report.odt.trashinfo")).contains("my%20report.odt"));
        assertEquals(trashDir.resolve("files"), trash.homeTrashFiles());
    }

    @Test
    void numbersDuplicateNames() throws IOException {
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a/report.odt"), "1");
        Files.writeString(dir.resolve("b/report.odt"), "2");

        assertEquals(2, trash.trash(List.of(dir.resolve("a/report.odt"), dir.resolve("b/report.odt"))));

        assertEquals("1", Files.readString(trashDir.resolve("files/report.odt")));
        assertEquals("2", Files.readString(trashDir.resolve("files/report.2.odt")));
        assertTrue(Files.exists(trashDir.resolve("info/report.2.odt.trashinfo")));
    }

    @Test
    void trashesFoldersAndLinksWithoutFollowing() throws IOException {
        Path target = Files.writeString(dir.resolve("target.txt"), "keep");
        Path link = Files.createSymbolicLink(dir.resolve("link"), target);
        Files.createDirectories(dir.resolve("folder/sub"));

        trash.trash(List.of(link, dir.resolve("folder")));

        assertEquals("keep", Files.readString(target));
        assertTrue(Files.isSymbolicLink(trashDir.resolve("files/link")));
        assertTrue(Files.isDirectory(trashDir.resolve("files/folder/sub")));
    }

    @Test
    void missingFileIsReported() {
        assertThrows(FileOperationException.class, () -> trash.trash(dir.resolve("missing")));
    }

    @Test
    void topDirectoryTrashIsPrivate() throws IOException {
        Path top = Files.createDirectory(dir.resolve("usb"));
        Path own = trash.topTrash(top);
        assertEquals(top.resolve(".Trash-1000"), own);
        assertTrue(Files.isDirectory(own.resolve("files")));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(own)));
    }

    @Test
    void sharedStickyTrashIsPreferred() throws IOException {
        Path top = Files.createDirectory(dir.resolve("disk"));
        Path shared = Files.createDirectory(top.resolve(".Trash"));
        Files.setAttribute(shared, "unix:mode", 01777);
        assertEquals(shared.resolve("1000"), trash.topTrash(top));

        Path plain = Files.createDirectory(dir.resolve("plain"));
        Files.createDirectory(plain.resolve(".Trash")); // not sticky: must be ignored
        assertEquals(plain.resolve(".Trash-1000"), trash.topTrash(plain));
    }

    @Test
    void encodesPathsLikeUris() {
        assertEquals("/home/ralf/a%20b/%C3%A4%25.txt", TrashService.encodePath("/home/ralf/a b/ä%.txt"));
        assertEquals("report.2.odt", TrashService.numbered("report.odt", 2));
        assertEquals(".bashrc.3", TrashService.numbered(".bashrc", 3));
    }

    @Test
    void mountPointOfTheTempFolder() {
        Path mount = TrashService.mountPoint(dir);
        assertTrue(dir.toAbsolutePath().startsWith(mount) || dir.toAbsolutePath().toString().startsWith(mount.toString()),
                mount + " should contain " + dir);
    }
}
