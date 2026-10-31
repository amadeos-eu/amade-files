package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Progress;
import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Extracts and creates archives: zip, tar (plain, gzip, bzip2, xz), 7z, and single files compressed with
 * gzip, bzip2 or xz. Pure Java (Apache Commons Compress), so nothing has to be installed.
 *
 * <ul>
 *     <li>Extracting never overwrites anything. The archive is unpacked into a hidden folder next to it;
 *     when it is complete, a single top-level folder or file moves into place under its own name (if that
 *     name is free), and anything else becomes a folder named after the archive ("photos (2)" if taken).
 *     A cancelled or failed extraction leaves nothing behind.</li>
 *     <li>Entries that would land outside the target ({@code ../}, absolute paths) and links that point
 *     outside it are skipped; links inside it are kept.</li>
 *     <li>A new archive is written to a hidden file and renamed when complete.</li>
 * </ul>
 */
public class ArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveService.class);
    private static final int BUFFER = 64 * 1024;
    private static final long REPORT_INTERVAL_NANOS = 50_000_000L;

    // --8<-- [start:formats]
    /** The formats Compress can write; the first is the default. */
    public enum Format {
        ZIP(".zip", "Zip (all systems)"),
        TAR_GZ(".tar.gz", "tar.gz (Linux, gzip)"),
        TAR_XZ(".tar.xz", "tar.xz (Linux, smallest)");

        private final String extension;
        private final String label;

        Format(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        public String extension() {
            return extension;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What can be read, by file name ending; longer endings first. */
    private enum Reader {
        TAR_GZ(".tar.gz", ".tgz"), TAR_BZ2(".tar.bz2", ".tbz2", ".tbz"), TAR_XZ(".tar.xz", ".txz"),
        TAR(".tar"), ZIP(".zip"), SEVEN_Z(".7z"), GZ(".gz"), BZ2(".bz2"), XZ(".xz");

        final List<String> endings;

        Reader(String... endings) {
            this.endings = List.of(endings);
        }

        static Reader of(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (Reader reader : values()) {
                for (String ending : reader.endings) {
                    if (lower.endsWith(ending) && lower.length() > ending.length()) return reader;
                }
            }
            return null;
        }

        String ending(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return endings.stream().filter(lower::endsWith).findFirst().orElse("");
        }
    }
    // --8<-- [end:formats]

    /**
     * @param result  the folder or file that was created, null if the archive was empty
     * @param files   files and links written
     * @param skipped entries left out because they would land outside the target or are special files
     */
    public record Result(Path result, int files, int skipped) {
    }

    private final SecureRandom random = new SecureRandom();

    /** True for the file names {@link #extract} understands. */
    public static boolean isArchive(String name) {
        return name != null && Reader.of(name) != null;
    }

    /** {@code photos.tar.gz} → {@code photos}; names that are no archive stay as they are. */
    public static String baseName(String name) {
        Reader reader = Reader.of(name);
        return reader == null ? name : name.substring(0, name.length() - reader.ending(name).length());
    }

    // ---- extract --------------------------------------------------------------------------------

    // --8<-- [start:extract]
    /**
     * Unpacks the archive into {@code targetDir}.
     *
     * @throws FileOperationException       with a message for the user if the archive cannot be read
     * @throws OperationCancelledException if the listener cancels; nothing is left behind then
     */
    public Result extract(Path archive, Path targetDir, OperationListener listener) {
        String name = archive.getFileName().toString();
        Reader reader = Reader.of(name);
        if (reader == null) throw new FileOperationException(name + " is not an archive Files can open.");
        if (!Files.isDirectory(targetDir)) throw new FileOperationException(targetDir + " is not a folder.");
        String base = baseName(name);
        Path work = targetDir.resolve("." + base + ".extracting-" + HexFormat.of().toHexDigits(random.nextInt()));
        try {
            Files.createDirectory(work);
        } catch (AccessDeniedException e) {
            throw new FileOperationException("No permission to extract into " + targetDir + ".", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot extract into " + targetDir + ": " + e.getMessage(), e);
        }
        boolean complete = false;
        try {
            Unpacker unpacker = new Unpacker(work, archive, listener);
            switch (reader) {
                case ZIP -> unpacker.zip();
                case SEVEN_Z -> unpacker.sevenZ();
                case TAR, TAR_GZ, TAR_BZ2, TAR_XZ -> unpacker.tar(reader);
                case GZ, BZ2, XZ -> unpacker.single(reader, base);
            }
            unpacker.finish();
            Path result = moveIntoPlace(work, targetDir, base);
            complete = true;
            return new Result(result, unpacker.files, unpacker.skipped);
        } catch (PasswordRequiredException e) {
            throw new FileOperationException(name + " is protected by a password, which Files cannot open yet.", e);
        } catch (UnsupportedZipFeatureException e) {
            throw new FileOperationException(name + " uses " + describe(e) + ", which Files cannot open.", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot extract " + name + ": " + e.getMessage(), e);
        } finally {
            if (!complete) deleteQuietly(work);
        }
    }
    // --8<-- [end:extract]

    private static String describe(UnsupportedZipFeatureException e) {
        if (e.getFeature() == UnsupportedZipFeatureException.Feature.ENCRYPTION) return "a password";
        return "a compression method";
    }

    // --8<-- [start:move-into-place]
    /** One top-level folder or file keeps its name if it is free; anything else gets a folder. */
    static Path moveIntoPlace(Path work, Path targetDir, String base) throws IOException {
        List<Path> top;
        try (Stream<Path> children = Files.list(work)) {
            top = children.toList();
        }
        if (top.isEmpty()) {
            Files.delete(work);
            return null;
        }
        if (top.size() == 1) {
            Path only = top.getFirst();
            Path target = targetDir.resolve(only.getFileName().toString());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(only, target);
                Files.delete(work);
                return target;
            }
        }
        Path folder = freeName(targetDir, base.isBlank() ? "Archive" : base, "");
        Files.move(work, folder);
        return folder;
    }
    // --8<-- [end:move-into-place]

    /** {@code name + ending}, or {@code name (2) + ending}, … whichever does not exist yet. */
    public static Path freeName(Path dir, String name, String ending) {
        Path path = dir.resolve(name + ending);
        for (int n = 2; Files.exists(path, LinkOption.NOFOLLOW_LINKS); n++) {
            path = dir.resolve(name + " (" + n + ")" + ending);
        }
        return path;
    }

    /** Writes the entries of one archive below the work folder. */
    private final class Unpacker {
        final Path root;
        final Path archive;
        final OperationListener listener;
        final long archiveSize;
        /** Folders get their permissions and times last, so a read-only folder can still be filled. */
        final Map<Path, ArchiveEntryInfo> folders = new LinkedHashMap<>();
        int files;
        int skipped;
        long bytesRead;
        long lastReport;

        Unpacker(Path root, Path archive, OperationListener listener) throws IOException {
            this.root = root.toRealPath();
            this.archive = archive;
            this.listener = listener;
            this.archiveSize = Math.max(1, Files.size(archive));
        }

        void zip() throws IOException {
            try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
                for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                    checkCancelled();
                    if (!entry.isDirectory() && !zip.canReadEntryData(entry)) {
                        throw new UnsupportedZipFeatureException(
                                entry.getGeneralPurposeBit().usesEncryption()
                                        ? UnsupportedZipFeatureException.Feature.ENCRYPTION
                                        : UnsupportedZipFeatureException.Feature.METHOD, entry);
                    }
                    int mode = entry.getPlatform() == ZipArchiveEntry.PLATFORM_UNIX ? entry.getUnixMode() & 07777 : 0;
                    ArchiveEntryInfo info = new ArchiveEntryInfo(entry.getName(), mode, entry.getLastModifiedTime());
                    if (entry.isDirectory()) {
                        directory(info);
                    } else if (entry.isUnixSymlink()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            symlink(info, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    } else {
                        try (InputStream in = zip.getInputStream(entry)) {
                            file(info, in);
                        }
                    }
                    bytesRead += Math.max(0, entry.getCompressedSize());
                    report(entry.getName(), false);
                }
            }
        }

        void sevenZ() throws IOException {
            try (SevenZFile sevenZ = SevenZFile.builder().setPath(archive).get()) {
                long total = 0;
                for (SevenZArchiveEntry entry : sevenZ.getEntries()) total += Math.max(0, entry.getSize());
                long done = 0;
                SevenZArchiveEntry entry;
                while ((entry = sevenZ.getNextEntry()) != null) {
                    checkCancelled();
                    FileTime time = entry.getHasLastModifiedDate() ? entry.getLastModifiedTime() : null;
                    ArchiveEntryInfo info = new ArchiveEntryInfo(entry.getName(), 0, time);
                    if (entry.isDirectory()) {
                        directory(info);
                    } else {
                        SevenZFile source = sevenZ;
                        file(info, new InputStream() {
                            @Override
                            public int read() throws IOException {
                                return source.read();
                            }

                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                return source.read(b, off, len);
                            }
                        });
                    }
                    done += Math.max(0, entry.getSize());
                    // Progress by unpacked size, as a solid 7z has no compressed size per entry.
                    bytesRead = total > 0 ? Math.round((double) done / total * archiveSize) : bytesRead;
                    report(entry.getName(), false);
                }
            }
        }

        void tar(Reader reader) throws IOException {
            try (InputStream raw = counting(Files.newInputStream(archive));
                 InputStream decompressed = decompress(reader, raw);
                 TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    checkCancelled();
                    ArchiveEntryInfo info = new ArchiveEntryInfo(entry.getName(), entry.getMode() & 07777,
                            entry.getLastModifiedTime());
                    if (entry.isDirectory()) directory(info);
                    else if (entry.isSymbolicLink()) symlink(info, entry.getLinkName());
                    else if (entry.isLink()) hardLink(info, entry.getLinkName());
                    else if (entry.isFile()) file(info, tar);
                    else skipped++; // devices, fifos
                    report(entry.getName(), false);
                }
            }
        }

        void single(Reader reader, String name) throws IOException {
            try (InputStream raw = counting(Files.newInputStream(archive));
                 InputStream in = decompress(reader, raw)) {
                FileTime time = Files.getLastModifiedTime(archive);
                file(new ArchiveEntryInfo(name, 0, time), in);
                report(name, false);
            }
        }

        private InputStream decompress(Reader reader, InputStream raw) throws IOException {
            InputStream in = new BufferedInputStream(raw, BUFFER);
            return switch (reader) {
                case TAR_GZ, GZ -> new GzipCompressorInputStream(in, true);
                case TAR_BZ2, BZ2 -> new BZip2CompressorInputStream(in, true);
                case TAR_XZ, XZ -> new XZCompressorInputStream(in, true);
                default -> in;
            };
        }

        private InputStream counting(InputStream in) {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) bytesRead++;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) bytesRead += n;
                    return n;
                }
            };
        }

        // --8<-- [start:resolve]
        /** Where an entry goes; null if it would land outside the work folder. */
        Path resolve(String name) {
            String clean = name.replace('\\', '/');
            while (clean.startsWith("/")) clean = clean.substring(1);
            if (clean.isEmpty() || clean.indexOf('\0') >= 0) return null;
            Path path;
            try {
                path = root.resolve(clean).normalize();
            } catch (java.nio.file.InvalidPathException e) {
                return null;
            }
            return path.startsWith(root) && !path.equals(root) ? path : null;
        }
        // --8<-- [end:resolve]

        /** Creates the parent folders and checks that no link leads out of the work folder. */
        private boolean prepareParent(Path path) throws IOException {
            Path parent = path.getParent();
            Files.createDirectories(parent);
            if (!parent.toRealPath().startsWith(root)) return false;
            if (Files.isSymbolicLink(path)) Files.delete(path);
            return true;
        }

        void directory(ArchiveEntryInfo info) throws IOException {
            Path path = resolve(info.name());
            if (path == null) {
                skipped++;
                return;
            }
            Files.createDirectories(path);
            if (!path.toRealPath().startsWith(root)) {
                skipped++;
                return;
            }
            folders.put(path, info);
        }

        void file(ArchiveEntryInfo info, InputStream in) throws IOException {
            Path path = resolve(info.name());
            if (path == null || !prepareParent(path)) {
                skipped++;
                return;
            }
            byte[] buffer = new byte[BUFFER];
            try (OutputStream out = Files.newOutputStream(path)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    checkCancelled();
                    out.write(buffer, 0, n);
                    report(info.name(), false);
                }
            }
            applyAttributes(path, info, false);
            files++;
        }

        void symlink(ArchiveEntryInfo info, String target) throws IOException {
            Path path = resolve(info.name());
            if (path == null || target == null || target.isEmpty() || target.startsWith("/") || !prepareParent(path)) {
                skipped++;
                return;
            }
            Path resolved = path.getParent().resolve(target).normalize();
            if (!resolved.startsWith(root)) {
                skipped++;
                return;
            }
            Files.deleteIfExists(path);
            Files.createSymbolicLink(path, Path.of(target));
            files++;
        }

        /** A tar hard link: a copy of the file it names, which came earlier in the archive. */
        void hardLink(ArchiveEntryInfo info, String target) throws IOException {
            Path path = resolve(info.name());
            Path source = target == null ? null : resolve(target);
            if (path == null || source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !prepareParent(path)) {
                skipped++;
                return;
            }
            Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            files++;
        }

        void finish() throws IOException {
            List<Path> deepestFirst = new ArrayList<>(folders.keySet());
            deepestFirst.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
            for (Path folder : deepestFirst) applyAttributes(folder, folders.get(folder), true);
            report(null, true);
        }

        /** Permissions from the archive (the owner can always read and write), and the modification time. */
        private void applyAttributes(Path path, ArchiveEntryInfo info, boolean folder) {
            try {
                if (info.mode() != 0) {
                    Set<PosixFilePermission> permissions = permissions(info.mode());
                    permissions.add(PosixFilePermission.OWNER_READ);
                    permissions.add(PosixFilePermission.OWNER_WRITE);
                    if (folder) permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(path, permissions);
                }
            } catch (UnsupportedOperationException | IOException e) {
                LOG.debug("No permissions for {}", path, e);
            }
            try {
                if (info.time() != null) Files.setLastModifiedTime(path, info.time());
            } catch (IOException e) {
                LOG.debug("No time for {}", path, e);
            }
        }

        private void checkCancelled() {
            if (listener.isCancelled()) throw new OperationCancelledException();
        }

        private void report(String current, boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastReport < REPORT_INTERVAL_NANOS) return;
            lastReport = now;
            listener.progress(new Progress(Math.min(bytesRead, archiveSize), archiveSize, files, 0,
                    current == null ? null : safePath(current)));
        }
    }

    private static Path safePath(String name) {
        try {
            return Path.of(name.replace('\\', '/'));
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Name, Unix permission bits (0 if the archive has none) and modification time of an entry. */
    private record ArchiveEntryInfo(String name, int mode, FileTime time) {
    }

    static Set<PosixFilePermission> permissions(int mode) {
        Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
        for (int bit = 0; bit < order.length; bit++) {
            if ((mode & (1 << bit)) != 0) set.add(order[bit]);
        }
        return set;
    }

    static int mode(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            mode |= switch (permission) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return mode;
    }

    // ---- compress -------------------------------------------------------------------------------

    // --8<-- [start:compress]
    /**
     * Packs files and folders into a new archive in {@code targetDir}, named {@code name} plus the
     * format's ending ({@code name (2).zip} if taken). Paths in the archive start at the items' names.
     * Links are stored as links; devices and pipes are skipped.
     *
     * @return the archive, with the number of files and links in it
     */
    public Result compress(List<Path> sources, Path targetDir, String name, Format format, OperationListener listener) {
        if (sources.isEmpty()) throw new FileOperationException("Nothing to compress.");
        String clean = FileSystemService.checkName(name);
        if (clean.toLowerCase(Locale.ROOT).endsWith(format.extension())) {
            clean = clean.substring(0, clean.length() - format.extension().length());
        }
        Path target = freeName(targetDir, clean, format.extension());
        Path part = targetDir.resolve("." + target.getFileName() + ".part-" + HexFormat.of().toHexDigits(random.nextInt()));
        Packer packer = new Packer(listener);
        for (Path source : sources) packer.measure(source);
        boolean complete = false;
        try {
            try (OutputStream file = Files.newOutputStream(part);
                 ArchiveOutputStream<? extends ArchiveEntry> out = open(format, new BufferedOutputStream(file, BUFFER))) {
                packer.out = out;
                for (Path source : sources) packer.pack(source.toAbsolutePath().normalize());
                out.finish();
            }
            packer.report(null, true);
            Files.move(part, target);
            complete = true;
            return new Result(target, packer.files, packer.skipped);
        } catch (AccessDeniedException e) {
            throw new FileOperationException("No permission: " + e.getFile(), e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot create " + target.getFileName() + ": " + e.getMessage(), e);
        } finally {
            if (!complete) deleteQuietly(part);
        }
    }
    // --8<-- [end:compress]

    private static ArchiveOutputStream<? extends ArchiveEntry> open(Format format, OutputStream out) throws IOException {
        return switch (format) {
            case ZIP -> {
                ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out);
                zip.setEncoding("UTF-8");
                zip.setUseLanguageEncodingFlag(true);
                yield zip;
            }
            case TAR_GZ -> tar(new GzipCompressorOutputStream(out));
            case TAR_XZ -> tar(new XZCompressorOutputStream(out));
        };
    }

    private static TarArchiveOutputStream tar(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out, "UTF-8");
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        return tar;
    }

    /** Walks the items and writes their entries. */
    private static final class Packer {
        final OperationListener listener;
        ArchiveOutputStream<? extends ArchiveEntry> out;
        long bytesTotal;
        int filesTotal;
        long bytesDone;
        int files;
        int skipped;
        long lastReport;

        Packer(OperationListener listener) {
            this.listener = listener;
        }

        void measure(Path source) {
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        filesTotal++;
                        if (attrs.isRegularFile()) bytesTotal += attrs.size();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOG.debug("Cannot measure {}", source, e);
            }
        }

        void pack(Path source) throws IOException {
            Path base = source.getParent() == null ? source : source.getParent();
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    check();
                    entry(dir, name(base, dir) + "/", attrs, Kind.DIRECTORY, null);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    check();
                    String name = name(base, file);
                    if (attrs.isSymbolicLink()) {
                        entry(file, name, attrs, Kind.LINK, Files.readSymbolicLink(file).toString());
                        files++;
                    } else if (attrs.isRegularFile()) {
                        entry(file, name, attrs, Kind.FILE, null);
                        files++;
                    } else {
                        skipped++;
                    }
                    report(file, false);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    throw exc;
                }
            });
        }

        private enum Kind { DIRECTORY, FILE, LINK }

        @SuppressWarnings("unchecked")
        private void entry(Path path, String name, BasicFileAttributes attrs, Kind kind, String linkTarget) throws IOException {
            int mode = posixMode(path, kind);
            FileTime time = attrs.lastModifiedTime();
            if (out instanceof ZipArchiveOutputStream zip) {
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                int type = kind == Kind.DIRECTORY ? 040000 : kind == Kind.LINK ? 0120000 : 0100000;
                entry.setUnixMode(type | mode);
                entry.setLastModifiedTime(time);
                if (kind == Kind.FILE) entry.setSize(attrs.size());
                zip.putArchiveEntry(entry);
                if (kind == Kind.LINK) zip.write(linkTarget.getBytes(StandardCharsets.UTF_8));
                if (kind == Kind.FILE) copy(path, zip);
                zip.closeArchiveEntry();
            } else if (out instanceof TarArchiveOutputStream tar) {
                TarArchiveEntry entry = kind == Kind.LINK ? new TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                        : new TarArchiveEntry(name);
                if (kind == Kind.LINK) entry.setLinkName(linkTarget);
                entry.setMode((kind == Kind.DIRECTORY ? 040000 : kind == Kind.LINK ? 0120000 : 0100000) | mode);
                entry.setLastModifiedTime(time);
                if (kind == Kind.FILE) entry.setSize(attrs.size());
                tar.putArchiveEntry(entry);
                if (kind == Kind.FILE) copy(path, tar);
                tar.closeArchiveEntry();
            }
        }

        private static int posixMode(Path path, Kind kind) {
            try {
                return mode(Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS).permissions());
            } catch (UnsupportedOperationException | IOException e) {
                return kind == Kind.DIRECTORY ? 0755 : kind == Kind.LINK ? 0777 : 0644;
            }
        }

        private void copy(Path file, OutputStream out) throws IOException {
            byte[] buffer = new byte[BUFFER];
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    check();
                    out.write(buffer, 0, n);
                    bytesDone += n;
                    report(file, false);
                }
            }
        }

        private static String name(Path base, Path path) {
            return base.relativize(path).toString().replace('\\', '/');
        }

        private void check() {
            if (listener.isCancelled()) throw new OperationCancelledException();
        }

        void report(Path current, boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastReport < REPORT_INTERVAL_NANOS) return;
            lastReport = now;
            listener.progress(new Progress(bytesDone, bytesTotal, files + skipped, filesTotal, current));
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // A folder from the archive may be read-only; it must still be emptied.
                    try {
                        Files.setPosixFilePermissions(dir, permissions(0700));
                    } catch (UnsupportedOperationException | IOException e) {
                        LOG.debug("Cannot open {}", dir, e);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Cannot remove {}", path, e);
        }
    }
}
