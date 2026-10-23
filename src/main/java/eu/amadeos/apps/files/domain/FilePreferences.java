package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Settings kept between sessions.
 *
 * @param showHidden show files whose name starts with a dot
 * @param twoPanels  in the two-panel view, show both panels; with one panel, Copy and Move ask for the target folder
 * @param viewMode   folder tree or two panels
 * @param leftDir    folder of the left panel (in the tree view: the shown folder) when the File Manager was closed,
 *                   null for the home folder
 * @param rightDir   folder of the right panel when the File Manager was closed, null for the home folder
 */
public record FilePreferences(boolean showHidden, boolean twoPanels, ViewMode viewMode, Path leftDir, Path rightDir) {
    public static final FilePreferences DEFAULTS = new FilePreferences(false, true, ViewMode.TREE, null, null);

    public FilePreferences {
        Objects.requireNonNull(viewMode, "viewMode");
    }

    public Path dir(Side side) {
        return side == Side.LEFT ? leftDir : rightDir;
    }

    public FilePreferences withDirs(Path left, Path right) {
        return new FilePreferences(showHidden, twoPanels, viewMode, left, right);
    }

    public FilePreferences withShowHidden(boolean value) {
        return new FilePreferences(value, twoPanels, viewMode, leftDir, rightDir);
    }

    public FilePreferences withTwoPanels(boolean value) {
        return new FilePreferences(showHidden, value, viewMode, leftDir, rightDir);
    }

    public FilePreferences withViewMode(ViewMode value) {
        return new FilePreferences(showHidden, twoPanels, value, leftDir, rightDir);
    }
}
