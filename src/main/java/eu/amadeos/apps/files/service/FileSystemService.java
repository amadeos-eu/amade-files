package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.FileEntry.Kind;
import eu.amadeos.apps.files.domain.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads folders and makes the small changes that need no progress dialog: new folder and rename.
 */
public class FileSystemService {
    private static final Logger LOG = LoggerFactory.getLogger(FileSystemService.class);
    private static final Path MOUNTS = Path.of("/proc/self/mounts");
    /** Where gvfs shows connected network shares. */
    private static final Path GVFS = NetworkService.defaultGvfsRoot();
    /** Where removable media and other mounted file systems of the user appear. */
    private static final List<Path> MEDIA_FOLDERS = List.of(Path.of("/media"), Path.of("/run/media"), Path.of("/mnt"));

    /**
     * The entries of a folder, unsorted, starting with {@code ..} unless it is the root.
     *
     * @throws FileOperationException if the folder cannot be read
     */
    public List<FileEntry> list(Path dir, boolean showHidden) {
        Path folder = dir.toAbsolutePath().normalize();
        List<FileEntry> entries = new ArrayList<>();
        if (folder.getParent() != null) entries.add(FileEntry.parent(folder.getParent()));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path path : stream) {
                FileEntry entry = describe(path);
                if (showHidden || !entry.isHidden()) entries.add(entry);
            }
        } catch (AccessDeniedException e) {
            throw new FileOperationException("No permission to read " + folder + ".", e);
        } catch (NoSuchFileException e) {
            throw new FileOperationException(folder + " no longer exists.", e);
        } catch (NotDirectoryException e) {
            throw new FileOperationException(folder + " is not a folder.", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot read " + folder + ": " + e.getMessage(), e);
        }
        return entries;
    }

    /**
     * The sub-folders of a folder for the folder tree, sorted by name; links to folders count as folders.
     *
     * @throws FileOperationException if the folder cannot be read
     */
    public List<Path> folders(Path dir, boolean showHidden) {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        return list(dir, showHidden).stream()
                .filter(e -> e.kind() == Kind.DIRECTORY)
                .sorted(Comparator.comparing(FileEntry::name, collator::compare))
                .map(FileEntry::path)
                .toList();
    }

    /**
     * The top level of the folder tree: home, trash, the file system and the mounted media below
     * {@code /media}, {@code /run/media} and {@code /mnt}.
     */
    public List<Place> places(Path home, Path trashFiles) {
        List<Place> places = new ArrayList<>();
        places.add(new Place("Home", home, Place.Kind.HOME));
        places.add(new Place("Trash", trashFiles, Place.Kind.TRASH));
        places.add(new Place("File System", Path.of("/"), Place.Kind.ROOT));
        List<String> lines = List.of();
        try {
            if (Files.isReadable(MOUNTS)) lines = Files.readAllLines(MOUNTS);
        } catch (IOException e) {
            LOG.debug("Cannot read {}", MOUNTS, e);
        }
        for (Path mount : mediaMounts(lines)) {
            places.add(new Place(String.valueOf(mount.getFileName()), mount, Place.Kind.MEDIA));
        }
        return places;
    }

    /** Mount points below the media folders, from lines of {@code /proc/self/mounts}, in their order. */
    static List<Path> mediaMounts(List<String> mountLines) {
        List<Path> mounts = new ArrayList<>();
        for (String line : mountLines) {
            String[] fields = line.split(" ");
            if (fields.length < 2) continue;
            Path mount;
            try {
                mount = Path.of(unescapeMount(fields[1]));
            } catch (java.nio.file.InvalidPathException e) {
                continue;
            }
            boolean media = MEDIA_FOLDERS.stream().anyMatch(m -> mount.startsWith(m) && !mount.equals(m));
            if (media && !mounts.contains(mount)) mounts.add(mount);
        }
        return mounts;
    }

    /** {@code /proc/self/mounts} writes space, tab, newline and backslash as octal escapes ({@code \040}). */
    static String unescapeMount(String field) {
        StringBuilder out = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\\' && i + 3 < field.length() && field.substring(i + 1, i + 4).chars().allMatch(d -> d >= '0' && d <= '7')) {
                out.append((char) Integer.parseInt(field.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** True if moving from {@code source} into {@code targetDir} is a rename, not a copy. */
    public boolean sameFileSystem(Path source, Path targetDir) {
        try {
            return Files.getFileStore(source).equals(Files.getFileStore(targetDir));
        } catch (IOException e) {
            return false;
        }
    }

    /** Attributes of one file; a symbolic link gets the kind and size of its target. */
    public FileEntry describe(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        BasicFileAttributes link;
        try {
            link = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            LOG.debug("No attributes for {}", path, e);
            return new FileEntry(path, name, Kind.OTHER, 0, null, "", false, false);
        }
        boolean symlink = link.isSymbolicLink();
        BasicFileAttributes attributes = link;
        if (symlink) {
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (IOException e) {
                // Broken link: show it, but it cannot be opened.
                return new FileEntry(path, name, Kind.OTHER, 0, link.lastModifiedTime().toInstant(), "", true, false);
            }
        }
        Kind kind = attributes.isDirectory() ? Kind.DIRECTORY : attributes.isRegularFile() ? Kind.FILE : Kind.OTHER;
        String permissions = "";
        boolean executable = false;
        try {
            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class);
            permissions = PosixFilePermissions.toString(posix.permissions());
            // gvfs shows every file of a network share as rwx------: there the bit says nothing.
            executable = kind == Kind.FILE && posix.permissions().contains(PosixFilePermission.OWNER_EXECUTE)
                    && !path.startsWith(GVFS);
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system (e.g. FAT on a USB stick): no permissions to show.
        }
        long size = kind == Kind.FILE ? attributes.size() : 0;
        return new FileEntry(path, name, kind, size, attributes.lastModifiedTime().toInstant(), permissions,
                symlink, executable);
    }

    /** Usable bytes on the file system of the folder, -1 if unknown. */
    public long freeSpace(Path dir) {
        try {
            return Files.getFileStore(dir).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    public Path createDirectory(Path parent, String name) {
        Path target = parent.resolve(checkName(name));
        try {
            return Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw new FileOperationException(name + " already exists.", e);
        } catch (AccessDeniedException e) {
            throw new FileOperationException("No permission to create a folder in " + parent + ".", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot create " + target + ": " + e.getMessage(), e);
        }
    }

    /** Renames within the same folder; never replaces an existing file. */
    public Path rename(Path path, String newName) {
        Path target = path.resolveSibling(checkName(newName));
        if (target.equals(path)) return path;
        // Case-only renames on case-insensitive file systems report the target as existing.
        boolean sameFile = false;
        try {
            sameFile = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(path, target);
        } catch (IOException e) {
            LOG.debug("Cannot compare {} and {}", path, target, e);
        }
        if (!sameFile && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileOperationException(newName + " already exists.");
        }
        try {
            return Files.move(path, target);
        } catch (AccessDeniedException e) {
            throw new FileOperationException("No permission to rename " + path.getFileName() + ".", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot rename " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** A single file name: no path separators, not {@code .} or {@code ..}. */
    static String checkName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) throw new FileOperationException("The name is empty.");
        if (trimmed.contains("/") || trimmed.indexOf('\0') >= 0) {
            throw new FileOperationException("A name cannot contain \"/\".");
        }
        if (trimmed.equals(".") || trimmed.equals("..")) throw new FileOperationException("\"" + trimmed + "\" is not a valid name.");
        return trimmed;
    }
}
