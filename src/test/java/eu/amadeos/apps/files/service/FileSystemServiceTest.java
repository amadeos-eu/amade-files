package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.FileEntry.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemServiceTest {
    @TempDir
    Path dir;

    private final FileSystemService service = new FileSystemService();

    private Map<String, FileEntry> byName(List<FileEntry> entries) {
        return entries.stream().collect(Collectors.toMap(FileEntry::name, Function.identity()));
    }

    @Test
    void listsFilesFoldersLinksAndParent() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve(".hidden"), "x");
        Files.createSymbolicLink(dir.resolve("link"), dir.resolve("sub"));
        Files.createSymbolicLink(dir.resolve("broken"), dir.resolve("missing"));
        Path script = Files.writeString(dir.resolve("run.sh"), "#!/bin/sh");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        Map<String, FileEntry> entries = byName(service.list(dir, false));
        assertEquals(Kind.PARENT, entries.get("..").kind());
        assertEquals(dir.getParent(), entries.get("..").path());
        assertEquals(5, entries.get("a.txt").size());
        assertEquals(Kind.DIRECTORY, entries.get("sub").kind());
        assertEquals(Kind.DIRECTORY, entries.get("link").kind());
        assertTrue(entries.get("link").symlink());
        assertEquals(Kind.OTHER, entries.get("broken").kind());
        assertTrue(entries.get("run.sh").executable());
        assertEquals("rwxr-xr-x", entries.get("run.sh").permissions());
        assertFalse(entries.containsKey(".hidden"));
        assertTrue(byName(service.list(dir, true)).containsKey(".hidden"));
    }

    @Test
    void rootHasNoParentEntry() {
        assertTrue(service.list(Path.of("/"), false).stream().noneMatch(FileEntry::isParent));
    }

    @Test
    void unreadableFolderIsReported() {
        FileOperationException e = assertThrows(FileOperationException.class,
                () -> service.list(dir.resolve("missing"), false));
        assertTrue(e.getMessage().contains("no longer exists"), e.getMessage());
    }

    @Test
    void createsAndRenames() throws IOException {
        Path folder = service.createDirectory(dir, " New Folder ");
        assertEquals("New Folder", folder.getFileName().toString());
        assertThrows(FileOperationException.class, () -> service.createDirectory(dir, "New Folder"));

        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.txt"), "b");
        assertThrows(FileOperationException.class, () -> service.rename(dir.resolve("a.txt"), "b.txt"));
        assertEquals("b", Files.readString(dir.resolve("b.txt")));
        assertEquals(dir.resolve("c.txt"), service.rename(dir.resolve("a.txt"), "c.txt"));
    }

    @Test
    void rejectsPathsAsNames() {
        for (String bad : new String[]{"", "  ", "a/b", ".", ".."}) {
            assertThrows(FileOperationException.class, () -> service.createDirectory(dir, bad), bad);
        }
    }

    @Test
    void reportsFreeSpace() {
        assertTrue(service.freeSpace(dir) > 0);
    }

    @Test
    void foldersForTheTreeAreSortedAndIncludeLinksToFolders() throws IOException {
        Files.createDirectory(dir.resolve("beta"));
        Files.createDirectory(dir.resolve("Alpha"));
        Files.createDirectory(dir.resolve(".config"));
        Files.writeString(dir.resolve("file.txt"), "x");
        Files.createSymbolicLink(dir.resolve("gamma"), dir.resolve("beta"));
        assertEquals(List.of(dir.resolve("Alpha"), dir.resolve("beta"), dir.resolve("gamma")), service.folders(dir, false));
        assertEquals(dir.resolve(".config"), service.folders(dir, true).getFirst());
    }

    @Test
    void placesStartWithHomeTrashAndTheFileSystem() {
        List<eu.amadeos.apps.files.domain.Place> places = service.places(dir, dir.resolve("trash/files"));
        assertEquals(dir, places.get(0).path());
        assertEquals(eu.amadeos.apps.files.domain.Place.Kind.TRASH, places.get(1).kind());
        assertEquals(Path.of("/"), places.get(2).path());
    }

    @Test
    void mediaMountsComeFromTheMountTable() {
        List<String> lines = List.of(
                "/dev/sda2 / ext4 rw,relatime 0 0",
                "tmpfs /run tmpfs rw 0 0",
                "/dev/sdb1 /media/ralf/USB-STICK vfat rw 0 0",
                "/dev/sr0 /media/ralf/Urlaub\\0402026 iso9660 ro 0 0",
                "/dev/sdc1 /run/media/ralf/Backup ext4 rw 0 0",
                "/dev/sdd1 /mnt ext4 rw 0 0",
                "/dev/sdd2 /mnt/data ext4 rw 0 0",
                "/dev/sdb1 /media/ralf/USB-STICK vfat rw 0 0");
        assertEquals(List.of(Path.of("/media/ralf/USB-STICK"), Path.of("/media/ralf/Urlaub 2026"),
                Path.of("/run/media/ralf/Backup"), Path.of("/mnt/data")), FileSystemService.mediaMounts(lines));
    }
}
