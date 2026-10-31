package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.domain.Resolution;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveServiceTest {
    @TempDir
    Path dir;

    private final ArchiveService service = new ArchiveService();
    private final OperationListener quiet = OperationListener.SKIP_CONFLICTS;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(dir.resolve("src/project"));
        Files.writeString(project.resolve("readme.txt"), "Hello");
        Files.createDirectories(project.resolve("bin"));
        Path run = Files.writeString(project.resolve("bin/run.sh"), "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(run, ArchiveService.permissions(0755));
        Files.createSymbolicLink(project.resolve("start"), Path.of("bin/run.sh"));
        Files.write(project.resolve("data.bin"), new byte[300_000]);
    }

    @Test
    void recognisesArchivesByTheirEnding() {
        assertTrue(ArchiveService.isArchive("photos.ZIP"));
        assertTrue(ArchiveService.isArchive("src.tar.gz"));
        assertTrue(ArchiveService.isArchive("src.tgz"));
        assertTrue(ArchiveService.isArchive("backup.7z"));
        assertTrue(ArchiveService.isArchive("notes.txt.xz"));
        assertFalse(ArchiveService.isArchive("letter.odt"));
        assertFalse(ArchiveService.isArchive(".zip"));
        assertEquals("src", ArchiveService.baseName("src.tar.gz"));
        assertEquals("notes.txt", ArchiveService.baseName("notes.txt.xz"));
    }

    @Test
    void zipRoundTripKeepsFoldersLinksAndPermissions() throws IOException {
        roundTrip(ArchiveService.Format.ZIP, "project.zip");
    }

    @Test
    void tarGzRoundTrip() throws IOException {
        roundTrip(ArchiveService.Format.TAR_GZ, "project.tar.gz");
    }

    @Test
    void tarXzRoundTrip() throws IOException {
        roundTrip(ArchiveService.Format.TAR_XZ, "project.tar.xz");
    }

    private void roundTrip(ArchiveService.Format format, String expectedName) throws IOException {
        Path out = Files.createDirectory(dir.resolve("out"));
        ArchiveService.Result packed = service.compress(List.of(project), out, "project", format, quiet);
        assertEquals(out.resolve(expectedName), packed.result());
        assertEquals(4, packed.files());
        assertNoHiddenLeftovers(out);

        Files.move(project, dir.resolve("src/old"));
        ArchiveService.Result unpacked = service.extract(packed.result(), dir.resolve("src"), quiet);
        // One top-level folder whose name is free: it lands under its own name.
        assertEquals(project, unpacked.result());
        assertEquals("Hello", Files.readString(project.resolve("readme.txt")));
        assertEquals(300_000, Files.size(project.resolve("data.bin")));
        assertTrue(Files.getPosixFilePermissions(project.resolve("bin/run.sh")).contains(PosixFilePermission.OWNER_EXECUTE));
        assertEquals(Path.of("bin/run.sh"), Files.readSymbolicLink(project.resolve("start")));
        assertNoHiddenLeftovers(dir.resolve("src"));

        // Again: the name is taken now, so it goes into "project (2)" instead of mixing with the first.
        ArchiveService.Result again = service.extract(packed.result(), dir.resolve("src"), quiet);
        assertEquals(dir.resolve("src/project (2)"), again.result());
        assertTrue(Files.isRegularFile(again.result().resolve("project/readme.txt")));
    }

    @Test
    void severalTopLevelItemsGetAFolderNamedAfterTheArchive() throws IOException {
        Path zip = dir.resolve("loose.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            put(out, "a.txt", "A");
            put(out, "b.txt", "B");
        }
        ArchiveService.Result result = service.extract(zip, dir, quiet);
        assertEquals(dir.resolve("loose"), result.result());
        assertEquals("B", Files.readString(dir.resolve("loose/b.txt")));
    }

    @Test
    void entriesThatClimbOutAreSkipped() throws IOException {
        Path zip = dir.resolve("evil.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            put(out, "../../outside.txt", "x");
            put(out, "/etc/absolute.txt", "y");
            put(out, "ok/inside.txt", "z");
        }
        Path target = Files.createDirectory(dir.resolve("target"));
        ArchiveService.Result result = service.extract(zip, target, quiet);
        assertEquals(1, result.skipped());
        assertFalse(Files.exists(dir.resolve("outside.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("outside.txt")));
        // The absolute path is kept inside the target, as tar does.
        assertTrue(Files.exists(result.result().resolve("etc/absolute.txt")));
        assertTrue(Files.exists(result.result().resolve("ok/inside.txt")));
    }

    @Test
    void linksThatPointOutsideAreSkippedAndNotWrittenThrough() throws IOException {
        Path tar = dir.resolve("links.tar.gz");
        try (OutputStream file = Files.newOutputStream(tar);
             TarArchiveOutputStream out = new TarArchiveOutputStream(new GzipCompressorOutputStream(file))) {
            TarArchiveEntry link = new TarArchiveEntry("pkg/escape", TarConstants.LF_SYMLINK);
            link.setLinkName("../../..");
            out.putArchiveEntry(link);
            out.closeArchiveEntry();
            byte[] data = "pwned".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry through = new TarArchiveEntry("pkg/escape/pwned.txt");
            through.setSize(data.length);
            out.putArchiveEntry(through);
            out.write(data);
            out.closeArchiveEntry();
            TarArchiveEntry ok = new TarArchiveEntry("pkg/lib", TarConstants.LF_SYMLINK);
            ok.setLinkName("escape-not");
            out.putArchiveEntry(ok);
            out.closeArchiveEntry();
        }
        Path target = Files.createDirectory(dir.resolve("target"));
        ArchiveService.Result result = service.extract(tar, target, quiet);
        assertFalse(Files.exists(dir.resolve("pwned.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("pwned.txt")));
        assertFalse(Files.isSymbolicLink(result.result().resolve("escape")));
        // Without the link, the file lands in a real folder inside the target.
        assertEquals("pwned", Files.readString(result.result().resolve("escape/pwned.txt")));
        assertTrue(Files.isSymbolicLink(result.result().resolve("lib")));
        assertEquals(1, result.skipped());
    }

    @Test
    void sevenZipArchivesAreRead() throws IOException {
        Path archive = dir.resolve("docs.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            SevenZArchiveEntry folder = out.createArchiveEntry(project.resolve("bin").toFile(), "docs/bin");
            out.putArchiveEntry(folder);
            out.closeArchiveEntry();
            SevenZArchiveEntry file = out.createArchiveEntry(project.resolve("readme.txt").toFile(), "docs/readme.txt");
            out.putArchiveEntry(file);
            out.write("Hello 7z".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        ArchiveService.Result result = service.extract(archive, dir, quiet);
        assertEquals(dir.resolve("docs"), result.result());
        assertEquals("Hello 7z", Files.readString(dir.resolve("docs/readme.txt")));
    }

    @Test
    void singleCompressedFilesAreUnpacked() throws IOException {
        Path gz = dir.resolve("notes.txt.gz");
        try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(gz))) {
            out.write("note".getBytes(StandardCharsets.UTF_8));
        }
        ArchiveService.Result result = service.extract(gz, dir, quiet);
        assertEquals(dir.resolve("notes.txt"), result.result());
        assertEquals("note", Files.readString(result.result()));
    }

    @Test
    void cancelledExtractionLeavesNothing() throws IOException {
        Path out = Files.createDirectory(dir.resolve("out"));
        Path zip = service.compress(List.of(project), out, "p", ArchiveService.Format.ZIP, quiet).result();
        OperationListener cancelling = new OperationListener() {
            @Override
            public void progress(Progress progress) {
            }

            @Override
            public Resolution conflict(Path source, Path target) {
                return Resolution.SKIP;
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        assertThrows(OperationCancelledException.class, () -> service.extract(zip, out, cancelling));
        assertThrows(OperationCancelledException.class,
                () -> service.compress(List.of(project), out, "q", ArchiveService.Format.TAR_GZ, cancelling));
        try (Stream<Path> files = Files.list(out)) {
            assertEquals(List.of(zip), files.toList());
        }
    }

    @Test
    void brokenArchivesGiveAMessage() throws IOException {
        Path bad = Files.writeString(dir.resolve("bad.zip"), "this is no zip");
        FileOperationException e = assertThrows(FileOperationException.class, () -> service.extract(bad, dir, quiet));
        assertTrue(e.getMessage().startsWith("Cannot extract bad.zip"), e.getMessage());
        assertNoHiddenLeftovers(dir);
    }

    @Test
    void compressAddsTheEndingOnceAndNeverOverwrites() throws IOException {
        Path out = Files.createDirectory(dir.resolve("out"));
        Path first = service.compress(List.of(project.resolve("readme.txt")), out, "readme.zip", ArchiveService.Format.ZIP, quiet).result();
        Path second = service.compress(List.of(project.resolve("readme.txt")), out, "readme", ArchiveService.Format.ZIP, quiet).result();
        assertEquals(out.resolve("readme.zip"), first);
        assertEquals(out.resolve("readme (2).zip"), second);
    }

    private static void put(ZipArchiveOutputStream out, String name, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(data.length);
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }

    private static void assertNoHiddenLeftovers(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            List<String> hidden = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).toList();
            assertEquals(List.of(), hidden);
        }
    }
}
