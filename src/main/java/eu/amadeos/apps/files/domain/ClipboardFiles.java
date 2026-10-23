package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Files on the clipboard.
 *
 * @param paths absolute paths
 * @param cut   Cut instead of Copy: Paste moves them
 */
public record ClipboardFiles(List<Path> paths, boolean cut) {
    public ClipboardFiles {
        paths = List.copyOf(paths);
    }
}
