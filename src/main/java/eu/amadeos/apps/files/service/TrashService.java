package eu.amadeos.apps.files.service;

import com.google.inject.Inject;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.apps.files.domain.TrashInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Moves files to the trash following the freedesktop.org Trash specification 1.0, so other desktops
 * and {@code gio trash} can list and restore them.
 *
 * <ul>
 *     <li>Files on the file system of the home folder go to {@code $XDG_DATA_HOME/Trash}.</li>
 *     <li>Files on other file systems (USB sticks, other partitions) go to {@code $topdir/.Trash/$uid} if
 *     the administrator provided a sticky {@code .Trash}, otherwise to {@code $topdir/.Trash-$uid}.
 *     They are never copied to the home trash: trashing stays a rename and the space is freed when the
 *     trash is emptied on that device.</li>
 *     <li>The {@code .trashinfo} file is created first with exclusive create, which reserves the name.</li>
 * </ul>
 */
public class TrashService {
    private static final Logger LOG = LoggerFactory.getLogger(TrashService.class);
    private static final DateTimeFormatter DELETION_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int STICKY = 01000;

    private final Path homeTrash;
    private final Clock clock;
    private final int uid;

    @Inject
    public TrashService(XdgDirectories xdg) {
        this(xdg.dataHome().resolve("Trash"), Clock.systemDefaultZone(), currentUid());
    }

    TrashService(Path homeTrash, Clock clock, int uid) {
        this.homeTrash = homeTrash;
        this.clock = clock;
        this.uid = uid;
    }

    /** Where the trashed files of the home trash are, for "Go → Trash". */
    public Path homeTrashFiles() {
        return homeTrash.resolve("files");
    }

    /** Creates the home trash if needed and returns its {@code files} folder. */
    public Path ensureHomeTrash() {
        try {
            ensureDirectories(homeTrash, false);
        } catch (IOException e) {
            throw new TrashUnavailableException("Cannot create the trash " + homeTrash + ": " + e.getMessage(), e);
        }
        return homeTrashFiles();
    }

    // ---- the trash as a folder: info, restore, empty --------------------------------------------

    /**
     * True for the {@code files} folder of a trash: the home trash, or {@code .Trash-$uid} and
     * {@code .Trash/$uid} on another file system. Such a folder is shown as the trash, with the origin
     * of each file and with Restore and Empty Trash.
     */
    public boolean isTrashFiles(Path dir) {
        Path files = dir.toAbsolutePath().normalize();
        Path root = files.getParent();
        if (root == null || !"files".equals(String.valueOf(files.getFileName()))
                || !Files.isDirectory(root.resolve("info"))) {
            return false;
        }
        if (root.equals(homeTrash.toAbsolutePath().normalize())) return true;
        String name = String.valueOf(root.getFileName());
        if (name.equals(".Trash-" + uid)) return true;
        Path shared = root.getParent();
        return shared != null && ".Trash".equals(String.valueOf(shared.getFileName())) && name.equals(Integer.toString(uid));
    }

    /**
     * Where a trashed file came from and when, from its {@code .trashinfo}; null if there is none or
     * it cannot be read. Relative paths of a top-directory trash are resolved against that file system's
     * top folder.
     */
    public TrashInfo info(Path trashedFile) {
        Path info = infoFile(trashedFile);
        String path = null;
        Instant deleted = null;
        try {
            for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                // The first Path and DeletionDate count, as the specification says.
                if (line.startsWith("Path=") && path == null) path = decodePath(line.substring(5).strip());
                else if (line.startsWith("DeletionDate=") && deleted == null) deleted = parseDate(line.substring(13).strip());
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("No trash info for {}", trashedFile, e);
            return null;
        }
        if (path == null || path.isEmpty()) return null;
        Path original = Path.of(path);
        if (!original.isAbsolute()) original = topOf(trashedFile.toAbsolutePath().normalize().getParent().getParent()).resolve(original);
        return new TrashInfo(original.normalize(), deleted);
    }

    /**
     * Moves a trashed file back to where it came from, creating missing folders on the way; refuses to
     * replace a file that is there now.
     *
     * @return the restored path
     */
    public Path restore(Path trashedFile) {
        Path file = trashedFile.toAbsolutePath().normalize();
        TrashInfo info = info(file);
        if (info == null) {
            throw new FileOperationException("The trash does not say where " + file.getFileName() + " came from.");
        }
        Path target = info.originalPath();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileOperationException(target + " already exists; move it away first.");
        }
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.move(file, target);
        } catch (IOException e) {
            throw new FileOperationException("Cannot restore " + file.getFileName() + " to " + target + ": "
                    + e.getMessage(), e);
        }
        forget(List.of(file));
        return target;
    }

    /** Removes the {@code .trashinfo} files of trashed files that were deleted for good. */
    public void forget(List<Path> trashedFiles) {
        for (Path file : trashedFiles) {
            try {
                Files.deleteIfExists(infoFile(file));
            } catch (IOException e) {
                LOG.warn("Cannot remove the trash info of {}", file, e);
            }
        }
    }

    /** Removes every {@code .trashinfo} of a trash, after its files were deleted (Empty Trash). */
    public void clearInfo(Path trashFiles) {
        Path info = trashFiles.toAbsolutePath().normalize().resolveSibling("info");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(info)) {
            for (Path entry : stream) Files.deleteIfExists(entry);
        } catch (IOException e) {
            LOG.warn("Cannot clear {}", info, e);
        }
    }

    static Path infoFile(Path trashedFile) {
        Path file = trashedFile.toAbsolutePath().normalize();
        return file.getParent().resolveSibling("info").resolve(file.getFileName() + ".trashinfo");
    }

    /** The folder a top-directory trash belongs to: {@code .Trash-$uid} and {@code .Trash/$uid} lie in it. */
    private static Path topOf(Path trashRoot) {
        Path parent = trashRoot.getParent();
        if (parent != null && ".Trash".equals(String.valueOf(parent.getFileName())) && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent != null ? parent : trashRoot;
    }

    private static Instant parseDate(String text) {
        try {
            return LocalDateTime.parse(text, DELETION_DATE).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The inverse of {@link #encodePath}: {@code %20} and friends back to UTF-8 characters. */
    static String decodePath(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write(hi << 4 | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.write(utf8, 0, utf8.length);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Moves each path to the trash of its file system. Stops at the first path that cannot be trashed;
     * the paths before it stay in the trash.
     *
     * @return the number of trashed paths
     * @throws TrashUnavailableException if a file system has no usable trash
     */
    public int trash(List<Path> paths) {
        int count = 0;
        for (Path path : paths) {
            trash(path);
            count++;
        }
        return count;
    }

    // --8<-- [start:trash]
    /** @return the path of the file inside the trash */
    public Path trash(Path path) {
        Path file = path.toAbsolutePath().normalize();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileOperationException(file + " no longer exists.");
        }
        Location location = locate(file);
        String name = file.getFileName().toString();
        Path info = null;
        Path target = null;
        for (int n = 1; info == null; n++) {
            String candidate = n == 1 ? name : numbered(name, n);
            Path candidateInfo = location.info().resolve(candidate + ".trashinfo");
            try {
                Files.writeString(candidateInfo, trashInfo(location.relativePath(file)),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                info = candidateInfo;
                target = location.files().resolve(candidate);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    // A file without info (left by another program): keep looking.
                    Files.delete(info);
                    info = null;
                }
            } catch (FileAlreadyExistsException e) {
                // Name taken; try the next number.
            } catch (IOException e) {
                throw new TrashUnavailableException("Cannot write to the trash " + location.root() + ": "
                        + e.getMessage(), e);
            }
        }
        try {
            Files.move(file, target);
            return target;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(info);
            } catch (IOException cleanup) {
                LOG.warn("Cannot remove {}", info, cleanup);
            }
            throw new FileOperationException("Cannot move " + file + " to the trash: " + e.getMessage(), e);
        }
    }
    // --8<-- [end:trash]

    /** {@code report.odt} → {@code report.2.odt}, as GLib names duplicates. */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + "." + n;
        return name.substring(0, dot) + "." + n + name.substring(dot);
    }

    // --8<-- [start:trash-info]
    private String trashInfo(String path) {
        return "[Trash Info]\nPath=" + encodePath(path) + "\nDeletionDate="
                + DELETION_DATE.format(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)) + "\n";
    }
    // --8<-- [end:trash-info]

    /** URI escaping (RFC 2396) of the UTF-8 bytes, keeping {@code /}. */
    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "/-_.!~*'()".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    // ---- choosing the trash -----------------------------------------------------------------------

    /**
     * @param root  the trash directory
     * @param top   the mount point paths in the info file are relative to; null for absolute paths
     */
    private record Location(Path root, Path top) {
        Path files() {
            return root.resolve("files");
        }

        Path info() {
            return root.resolve("info");
        }

        String relativePath(Path file) {
            return top == null ? file.toString() : top.relativize(file).toString();
        }
    }

    private Location locate(Path file) {
        Path parent = Objects.requireNonNullElse(file.getParent(), file);
        try {
            ensureDirectories(homeTrash, false);
            if (store(parent).equals(store(homeTrash))) return new Location(homeTrash, null);
        } catch (IOException e) {
            throw new TrashUnavailableException("Cannot create the trash " + homeTrash + ": " + e.getMessage(), e);
        }
        Path top = mountPoint(parent);
        return new Location(topTrash(top), top);
    }

    /** {@code $topdir/.Trash/$uid} if the administrator set up {@code .Trash}, else {@code $topdir/.Trash-$uid}. */
    Path topTrash(Path top) {
        Path shared = top.resolve(".Trash");
        try {
            if (Files.isDirectory(shared, LinkOption.NOFOLLOW_LINKS)
                    && (((Integer) Files.getAttribute(shared, "unix:mode", LinkOption.NOFOLLOW_LINKS)) & STICKY) != 0) {
                Path mine = shared.resolve(Integer.toString(uid));
                ensureDirectories(mine, true);
                return mine;
            }
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            LOG.debug("Shared trash {} not usable", shared, e);
        }
        Path own = top.resolve(".Trash-" + uid);
        try {
            if (Files.isSymbolicLink(own)) throw new IOException(own + " is a symbolic link");
            ensureDirectories(own, true);
            return own;
        } catch (IOException e) {
            throw new TrashUnavailableException("There is no trash on " + top + " (" + e.getMessage() + ").", e);
        }
    }

    /** Creates {@code dir/files} and {@code dir/info}; top-directory trashes are private to the user. */
    private static void ensureDirectories(Path dir, boolean privateDir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            if (privateDir) restrict(dir);
        }
        for (String sub : new String[]{"files", "info"}) {
            Path path = dir.resolve(sub);
            if (!Files.isDirectory(path)) {
                Files.createDirectories(path);
                if (privateDir) restrict(path);
            }
        }
    }

    private static void restrict(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // FAT: no permissions to set.
        }
    }

    private static FileStore store(Path path) throws IOException {
        return Files.getFileStore(path);
    }

    /** The topmost folder on the same file system. */
    static Path mountPoint(Path path) {
        try {
            FileStore store = store(path);
            Path current = path.toRealPath();
            while (current.getParent() != null && store(current.getParent()).equals(store)) {
                current = current.getParent();
            }
            return current;
        } catch (IOException e) {
            throw new TrashUnavailableException("Cannot find the device of " + path + ": " + e.getMessage(), e);
        }
    }

    private static int currentUid() {
        try {
            // /proc/self belongs to the effective user of this process.
            return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            try {
                return (Integer) Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException inner) {
                return 0;
            }
        }
    }
}
