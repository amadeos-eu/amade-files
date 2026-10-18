package eu.amadeos.apps.files.domain;

import java.nio.file.Path;

/**
 * Settings kept between sessions.
 *
 * @param showHidden show files whose name starts with a dot
 * @param twoPanels  show both panels; with one panel, Copy and Move ask for the target folder
 * @param leftDir    folder of the left panel when the File Manager was closed, null for the home folder
 * @param rightDir   folder of the right panel when the File Manager was closed, null for the home folder
 */
public record FilePreferences(boolean showHidden, boolean twoPanels, Path leftDir, Path rightDir) {
    public static final FilePreferences DEFAULTS = new FilePreferences(false, true, null, null);

    public Path dir(Side side) {
        return side == Side.LEFT ? leftDir : rightDir;
    }

    public FilePreferences withDirs(Path left, Path right) {
        return new FilePreferences(showHidden, twoPanels, left, right);
    }

    public FilePreferences withShowHidden(boolean value) {
        return new FilePreferences(value, twoPanels, leftDir, rightDir);
    }

    public FilePreferences withTwoPanels(boolean value) {
        return new FilePreferences(showHidden, value, leftDir, rightDir);
    }
}
