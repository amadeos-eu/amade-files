package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.time.Instant;

/**
 * What the {@code .trashinfo} file of a trashed file says: where it came from and when it was
 * trashed (freedesktop.org Trash specification).
 *
 * @param originalPath the absolute path the file had before it was trashed
 * @param deletedAt    when it was moved to the trash, null if the file does not say
 */
public record TrashInfo(Path originalPath, Instant deletedAt) {
    /** The folder the file came from. */
    public Path originalFolder() {
        Path parent = originalPath.getParent();
        return parent != null ? parent : originalPath;
    }
}
