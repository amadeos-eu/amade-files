package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.domain.Resolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Copy, move and permanent delete with progress, cancellation and conflict handling.
 *
 * <ul>
 *     <li>Files are copied into a hidden temporary file next to the target and renamed into place when
 *     complete, so a cancelled or failed copy never leaves a half-written file under the real name and an
 *     overwritten file is replaced in one step.</li>
 *     <li>Folders that exist in the target are merged; only files ask for a {@link Resolution}.</li>
 *     <li>Symbolic links are copied as links, never followed.</li>
 *     <li>Moving within one file system renames; across file systems it copies and deletes each source
 *     file after its copy is complete.</li>
 * </ul>
 */
public class FileOperations {
    private static final Logger LOG = LoggerFactory.getLogger(FileOperations.class);
    static final int BLOCK = 4 * 1024 * 1024;
    private static final long REPORT_INTERVAL_NANOS = 50_000_000L;

    private final SecureRandom random = new SecureRandom();

    /**
     * @param done    files copied, moved or deleted
     * @param skipped files left alone (conflicts, special files)
     */
    public record Summary(int done, int skipped) {
    }

    private enum Mode {
        COPY("copy"), MOVE("move");

        final String verb;

        Mode(String verb) {
            this.verb = verb;
        }
    }

    public Summary copy(List<Path> sources, Path targetDir, OperationListener listener) {
        return transfer(Mode.COPY, sources, targetDir, listener);
    }

    public Summary move(List<Path> sources, Path targetDir, OperationListener listener) {
        return transfer(Mode.MOVE, sources, targetDir, listener);
    }

    /** Deletes files and whole folders without the trash. Links are removed, not their targets. */
    public Summary delete(List<Path> paths, OperationListener listener) {
        Run run = new Run(Mode.COPY, listener);
        for (Path path : paths) run.countEntries(path);
        for (Path path : paths) run.deleteTree(path);
        run.report(null, true);
        return new Summary(run.done, run.skipped);
    }

    private Summary transfer(Mode mode, List<Path> sources, Path targetDir, OperationListener listener) {
        Path target = targetDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) throw new FileOperationException(target + " is not a folder.");
        List<Path> items = sources.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        for (Path source : items) {
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && isInside(target, source)) {
                throw new FileOperationException("Cannot " + mode.verb + " the folder " + source.getFileName()
                        + " into itself.");
            }
        }
        Run run = new Run(mode, listener);
        for (Path source : items) run.measure(source);
        for (Path source : items) {
            Path destination = target.resolve(source.getFileName());
            if (destination.equals(source)) {
                if (mode == Mode.MOVE) {
                    run.skipTree(source); // already there
                    continue;
                }
                destination = uniqueName(destination); // copy into the same folder: "name (2)"
            }
            run.transfer(source, destination);
        }
        run.report(null, true);
        return new Summary(run.done, run.skipped);
    }

    private static boolean isInside(Path target, Path folder) {
        if (target.startsWith(folder)) return true;
        try {
            return target.toRealPath().startsWith(folder.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code report.odt} → {@code report (2).odt}, {@code report (3).odt}, … */
    static Path uniqueName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        boolean hasExtension = dot > 0 && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        String base = hasExtension ? name.substring(0, dot) : name;
        String extension = hasExtension ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            Path candidate = path.resolveSibling(base + " (" + n + ")" + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
    }

    private static boolean sameFileSystem(Path source, Path targetDir) {
        try {
            return Files.getFileStore(source).equals(Files.getFileStore(targetDir));
        } catch (IOException e) {
            return false;
        }
    }

    private static FileOperationException failure(String action, Path path, IOException e) {
        if (e instanceof AccessDeniedException) {
            return new FileOperationException("No permission to " + action + " " + path + ".", e);
        }
        if (e instanceof NoSuchFileException) return new FileOperationException(path + " no longer exists.", e);
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new FileOperationException("Cannot " + action + " " + path + ": " + reason, e);
    }

    /** State of one operation. */
    private final class Run {
        final Mode mode;
        final OperationListener listener;
        long bytesTotal;
        long bytesDone;
        int filesTotal;
        int filesDone;
        int done;
        int skipped;
        long lastReport;

        Run(Mode mode, OperationListener listener) {
            this.mode = mode;
            this.listener = listener;
        }

        // ---- measuring --------------------------------------------------------------------------

        /** Adds the files and bytes below {@code path} to the totals. */
        void measure(Path path) {
            long[] totals = tree(path);
            filesTotal += (int) totals[0];
            bytesTotal += totals[1];
        }

        /** Counts every entry, folders included; deleting reports progress by entries. */
        void countEntries(Path path) {
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        filesTotal++;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                        filesTotal++;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        filesTotal++;
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw failure("read", path, e);
            }
        }

        /** {files, bytes} below a path; links and special files count as files without bytes. */
        long[] tree(Path path) {
            long[] totals = new long[2];
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        checkCancelled();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        totals[0]++;
                        if (attrs.isRegularFile()) totals[1] += attrs.size();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        throw e;
                    }
                });
            } catch (IOException e) {
                throw failure("read", path, e);
            }
            return totals;
        }

        // ---- copy and move ----------------------------------------------------------------------

        void transfer(Path source, Path destination) {
            checkCancelled();
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                throw failure("read", source, e);
            }
            boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (mode == Mode.MOVE && !exists && sameFileSystem(source, destination.getParent())) {
                // A rename moves a whole folder at once.
                long[] totals = tree(source);
                try {
                    Files.move(source, destination);
                } catch (IOException e) {
                    throw failure("move", source, e);
                }
                finished(totals[1], (int) totals[0], (int) totals[0], source);
                return;
            }
            if (attributes.isDirectory()) {
                transferDirectory(source, destination, attributes, exists);
            } else {
                transferFile(source, destination, attributes, exists);
            }
        }

        private void transferDirectory(Path source, Path destination, BasicFileAttributes attributes, boolean exists) {
            if (exists && !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                // A file is in the way of the folder.
                switch (ask(source, destination)) {
                    case SKIP -> {
                        skipTree(source);
                        return;
                    }
                    case KEEP_BOTH -> {
                        destination = uniqueName(destination);
                        exists = false;
                    }
                    default -> {
                        delete(destination);
                        exists = false;
                    }
                }
            }
            if (!exists) {
                try {
                    Files.createDirectory(destination);
                } catch (IOException e) {
                    throw failure("create", destination, e);
                }
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) transfer(child, destination.resolve(child.getFileName()));
            } catch (IOException e) {
                throw failure("read", source, e);
            }
            copyAttributes(source, destination, attributes);
            if (mode == Mode.MOVE) {
                try {
                    Files.delete(source);
                } catch (DirectoryNotEmptyException e) {
                    LOG.debug("{} keeps skipped files", source); // skipped files stay in the source
                } catch (IOException e) {
                    throw failure("delete", source, e);
                }
            }
        }

        private void transferFile(Path source, Path destination, BasicFileAttributes attributes, boolean exists) {
            long size = attributes.isRegularFile() ? attributes.size() : 0;
            if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                // Devices, pipes and sockets: reading a pipe would block forever.
                LOG.info("Skipping special file {}", source);
                skipped++;
                finished(0, 1, 0, source);
                return;
            }
            if (exists) {
                if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileOperationException("Cannot replace the folder " + destination
                            + " with a file.");
                }
                switch (ask(source, destination)) {
                    case SKIP -> {
                        skipped++;
                        finished(size, 1, 0, source);
                        return;
                    }
                    case KEEP_BOTH -> destination = uniqueName(destination);
                    default -> {
                        // OVERWRITE: the copy replaces the target atomically below.
                    }
                }
            }
            if (mode == Mode.MOVE && sameFileSystem(source, destination.getParent())) {
                try {
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw failure("move", source, e);
                }
                finished(size, 1, 1, source);
                return;
            }
            if (attributes.isSymbolicLink()) copyLink(source, destination);
            else copyContent(source, destination, attributes);
            if (mode == Mode.MOVE) {
                try {
                    Files.delete(source);
                } catch (IOException e) {
                    throw failure("delete", source, e);
                }
            }
            done++;
            filesDone++;
            report(source, false);
        }

        private void copyLink(Path source, Path destination) {
            try {
                Path linkTarget = Files.readSymbolicLink(source);
                Files.deleteIfExists(destination);
                Files.createSymbolicLink(destination, linkTarget);
            } catch (IOException e) {
                throw failure("copy", source, e);
            }
        }

        // --8<-- [start:copy-content]
        private void copyContent(Path source, Path destination, BasicFileAttributes attributes) {
            Path temp = destination.resolveSibling(".amade-files-" + HexFormat.of().formatHex(randomBytes()) + ".part");
            try {
                try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
                     FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    long size = in.size();
                    long position = 0;
                    while (position < size) {
                        checkCancelled();
                        long copied = in.transferTo(position, Math.min(BLOCK, size - position), out);
                        if (copied <= 0) break; // the file shrank while copying
                        position += copied;
                        bytesDone += copied;
                        report(source, false);
                    }
                    // Files that grew while copying are copied completely.
                    long tail;
                    while ((tail = in.transferTo(position, BLOCK, out)) > 0) {
                        checkCancelled();
                        position += tail;
                    }
                }
                copyAttributes(source, temp, attributes);
                try {
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                deleteQuietly(temp);
                throw failure("copy", source, e);
            } catch (RuntimeException e) {
                deleteQuietly(temp);
                throw e;
            }
        }
        // --8<-- [end:copy-content]

        private byte[] randomBytes() {
            byte[] bytes = new byte[6];
            random.nextBytes(bytes);
            return bytes;
        }

        private void copyAttributes(Path source, Path target, BasicFileAttributes attributes) {
            try {
                Files.setLastModifiedTime(target, attributes.lastModifiedTime());
            } catch (IOException e) {
                LOG.debug("Cannot set the time of {}", target, e);
            }
            try {
                Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException | IOException e) {
                // FAT and similar file systems have no permissions.
            }
        }

        private Resolution ask(Path source, Path destination) {
            Resolution resolution = listener.conflict(source, destination);
            if (resolution == null || resolution == Resolution.CANCEL) throw new OperationCancelledException();
            return resolution;
        }

        void skipTree(Path source) {
            long[] totals = tree(source);
            skipped += (int) totals[0];
            finished(totals[1], (int) totals[0], 0, source);
        }

        private void finished(long bytes, int files, int doneFiles, Path current) {
            bytesDone += bytes;
            filesDone += files;
            done += doneFiles;
            report(current, false);
        }

        // ---- delete -----------------------------------------------------------------------------

        void deleteTree(Path path) {
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        checkCancelled();
                        Files.delete(file);
                        done++;
                        filesDone++;
                        report(file, false);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                        if (e != null) throw e;
                        checkCancelled();
                        Files.delete(dir);
                        filesDone++;
                        report(dir, false);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        throw e;
                    }
                });
            } catch (IOException e) {
                throw failure("delete", path, e);
            }
        }

        private void delete(Path path) {
            Run inner = new Run(mode, OperationListener.SKIP_CONFLICTS);
            inner.deleteTree(path);
        }

        private void deleteQuietly(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Cannot remove the temporary file {}", path, e);
            }
        }

        // ---- reporting --------------------------------------------------------------------------

        // --8<-- [start:report]
        void checkCancelled() {
            if (listener.isCancelled()) throw new OperationCancelledException();
        }

        /** Throttled to one report per 50 ms; the final report ({@code force}) always goes out. */
        void report(Path current, boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastReport < REPORT_INTERVAL_NANOS) return;
            lastReport = now;
            listener.progress(new Progress(bytesDone, bytesTotal, filesDone, filesTotal, current));
        }
        // --8<-- [end:report]
    }
}
