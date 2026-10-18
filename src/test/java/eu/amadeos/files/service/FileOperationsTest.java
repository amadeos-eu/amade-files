package eu.amadeos.files.service;

import eu.amadeos.files.domain.Progress;
import eu.amadeos.files.domain.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileOperationsTest {
    @TempDir
    Path dir;

    private final FileOperations operations = new FileOperations();
    private Path source;
    private Path target;

    /** Answers conflicts from a list and records progress. */
    private static final class Recorder implements OperationListener {
        final List<Resolution> answers = new ArrayList<>();
        final List<Path> conflicts = new ArrayList<>();
        final List<Progress> progress = new ArrayList<>();
        boolean cancelAfterFirstProgress;

        @Override
        public void progress(Progress p) {
            progress.add(p);
        }

        @Override
        public Resolution conflict(Path source, Path target) {
            conflicts.add(target);
            return answers.isEmpty() ? Resolution.CANCEL : answers.removeFirst();
        }

        @Override
        public boolean isCancelled() {
            return cancelAfterFirstProgress && !progress.isEmpty();
        }
    }

    @BeforeEach
    void folders() throws IOException {
        source = Files.createDirectory(dir.resolve("source"));
        target = Files.createDirectory(dir.resolve("target"));
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void copiesFilesWithTimeAndPermissions() throws IOException {
        Path file = Files.writeString(source.resolve("a.txt"), "hello");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Recorder recorder = new Recorder();

        FileOperations.Summary summary = operations.copy(List.of(file), target, recorder);

        Path copy = target.resolve("a.txt");
        assertEquals("hello", Files.readString(copy));
        assertEquals(FileTime.fromMillis(1_000_000_000_000L), Files.getLastModifiedTime(copy));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)));
        assertEquals(new FileOperations.Summary(1, 0), summary);
        Progress last = recorder.progress.getLast();
        assertEquals(5, last.bytesTotal());
        assertEquals(5, last.bytesDone());
        assertEquals(1.0, last.fraction());
        assertTrue(Files.exists(file));
        assertEquals(List.of("a.txt"), names(target), "no temporary files left");
    }

    @Test
    void copiesTreesAndKeepsLinksAsLinks() throws IOException {
        Path tree = Files.createDirectories(source.resolve("tree/deep"));
        Files.writeString(tree.resolve("f.txt"), "x");
        Files.createSymbolicLink(source.resolve("tree/link"), Path.of("deep/f.txt"));

        operations.copy(List.of(source.resolve("tree")), target, new Recorder());

        assertEquals("x", Files.readString(target.resolve("tree/deep/f.txt")));
        assertTrue(Files.isSymbolicLink(target.resolve("tree/link")));
        assertEquals(Path.of("deep/f.txt"), Files.readSymbolicLink(target.resolve("tree/link")));
    }

    @Test
    void mergesFoldersAndAsksOnlyForFiles() throws IOException {
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("docs/new.txt"), "new");
        Files.writeString(source.resolve("docs/same.txt"), "from source");
        Files.createDirectories(target.resolve("docs"));
        Files.writeString(target.resolve("docs/old.txt"), "old");
        Files.writeString(target.resolve("docs/same.txt"), "in target");
        Recorder recorder = new Recorder();
        recorder.answers.add(Resolution.OVERWRITE);

        operations.copy(List.of(source.resolve("docs")), target, recorder);

        assertEquals(List.of(target.resolve("docs/same.txt")), recorder.conflicts);
        assertEquals(List.of("new.txt", "old.txt", "same.txt"), names(target.resolve("docs")));
        assertEquals("from source", Files.readString(target.resolve("docs/same.txt")));
    }

    @Test
    void skipAndKeepBoth() throws IOException {
        Files.writeString(source.resolve("a.txt"), "source a");
        Files.writeString(source.resolve("b.txt"), "source b");
        Files.writeString(target.resolve("a.txt"), "target a");
        Files.writeString(target.resolve("b.txt"), "target b");
        Recorder recorder = new Recorder();
        recorder.answers.add(Resolution.SKIP);
        recorder.answers.add(Resolution.KEEP_BOTH);

        FileOperations.Summary summary = operations.copy(
                List.of(source.resolve("a.txt"), source.resolve("b.txt")), target, recorder);

        assertEquals("target a", Files.readString(target.resolve("a.txt")));
        assertEquals("target b", Files.readString(target.resolve("b.txt")));
        assertEquals("source b", Files.readString(target.resolve("b (2).txt")));
        assertEquals(new FileOperations.Summary(1, 1), summary);
    }

    @Test
    void cancellingAConflictStopsAndKeepsTheTarget() throws IOException {
        Files.writeString(source.resolve("a.txt"), "source");
        Files.writeString(target.resolve("a.txt"), "target");
        assertThrows(OperationCancelledException.class,
                () -> operations.copy(List.of(source.resolve("a.txt")), target, new Recorder()));
        assertEquals("target", Files.readString(target.resolve("a.txt")));
    }

    @Test
    void copyIntoTheSameFolderMakesANumberedCopy() throws IOException {
        Path file = Files.writeString(source.resolve("report.odt"), "r");
        Path folder = Files.createDirectory(source.resolve("folder.d"));
        operations.copy(List.of(file, folder), source, new Recorder());
        assertEquals(List.of("folder.d", "folder.d (2)", "report (2).odt", "report.odt"), names(source));
    }

    @Test
    void refusesToCopyAFolderIntoItself() throws IOException {
        Path inner = Files.createDirectories(source.resolve("a/b"));
        FileOperationException e = assertThrows(FileOperationException.class,
                () -> operations.copy(List.of(source.resolve("a")), inner, new Recorder()));
        assertTrue(e.getMessage().contains("into itself"), e.getMessage());
    }

    @Test
    void moveRenamesWithinTheFileSystem() throws IOException {
        Files.createDirectories(source.resolve("tree/sub"));
        Files.writeString(source.resolve("tree/sub/f.txt"), "f");
        Files.writeString(source.resolve("g.txt"), "g");

        FileOperations.Summary summary = operations.move(
                List.of(source.resolve("tree"), source.resolve("g.txt")), target, new Recorder());

        assertEquals(List.of(), names(source));
        assertEquals("f", Files.readString(target.resolve("tree/sub/f.txt")));
        assertEquals("g", Files.readString(target.resolve("g.txt")));
        assertEquals(2, summary.done());
    }

    @Test
    void moveMergesAndLeavesSkippedFilesInTheSource() throws IOException {
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("docs/keep.txt"), "source");
        Files.writeString(source.resolve("docs/move.txt"), "moved");
        Files.createDirectories(target.resolve("docs"));
        Files.writeString(target.resolve("docs/keep.txt"), "target");
        Recorder recorder = new Recorder();
        recorder.answers.add(Resolution.SKIP);

        operations.move(List.of(source.resolve("docs")), target, recorder);

        assertEquals(List.of("keep.txt"), names(source.resolve("docs")));
        assertEquals("target", Files.readString(target.resolve("docs/keep.txt")));
        assertEquals("moved", Files.readString(target.resolve("docs/move.txt")));
    }

    @Test
    void moveIntoTheSameFolderDoesNothing() throws IOException {
        Path file = Files.writeString(source.resolve("a.txt"), "a");
        FileOperations.Summary summary = operations.move(List.of(file), source, new Recorder());
        assertEquals(new FileOperations.Summary(0, 1), summary);
        assertEquals(List.of("a.txt"), names(source));
    }

    @Test
    void moveAcrossFileSystemsCopiesAndDeletes() throws IOException {
        Path shm = Path.of("/dev/shm");
        assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm));
        assumeTrue(!Files.getFileStore(shm).equals(Files.getFileStore(dir)), "needs two file systems");
        Path other = Files.createTempDirectory(shm, "amade-files-test");
        try {
            Files.createDirectories(source.resolve("tree"));
            Files.writeString(source.resolve("tree/f.txt"), "f");
            operations.move(List.of(source.resolve("tree")), other, new Recorder());
            assertFalse(Files.exists(source.resolve("tree")));
            assertEquals("f", Files.readString(other.resolve("tree/f.txt")));
        } finally {
            operations.delete(List.of(other), OperationListener.SKIP_CONFLICTS);
        }
    }

    @Test
    void cancellingDuringACopyLeavesNoPartialFile() throws IOException {
        byte[] big = new byte[FileOperations.BLOCK * 3];
        Path file = Files.write(source.resolve("big.bin"), big);
        Recorder recorder = new Recorder();
        recorder.cancelAfterFirstProgress = true;
        // The first block reports progress (the throttle lets the first report through), then it is cancelled.
        assertThrows(OperationCancelledException.class, () -> operations.copy(List.of(file), target, recorder));
        assertEquals(List.of(), names(target));
    }

    @Test
    void deletesTreesButNotLinkTargets() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "keep");
        Files.createDirectories(source.resolve("tree/sub"));
        Files.writeString(source.resolve("tree/sub/f.txt"), "f");
        Files.createSymbolicLink(source.resolve("tree/link"), outside);

        FileOperations.Summary summary = operations.delete(List.of(source.resolve("tree")), new Recorder());

        assertFalse(Files.exists(source.resolve("tree"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(outside));
        assertEquals(2, summary.done());
    }

    @Test
    void numberedNames() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "");
        Files.writeString(dir.resolve("a (2).txt"), "");
        assertEquals(dir.resolve("a (3).txt"), FileOperations.uniqueName(dir.resolve("a.txt")));
        assertEquals(dir.resolve(".bashrc (2)"), FileOperations.uniqueName(dir.resolve(".bashrc")));
    }
}
