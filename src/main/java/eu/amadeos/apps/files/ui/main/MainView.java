package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.domain.ViewMode;

import java.nio.file.Path;
import java.util.List;

/**
 * What the presenter needs from the main window. All methods run on the EDT.
 */
public interface MainView {
    /**
     * @param entries   sorted, the parent entry first
     * @param focusName the entry to put the cursor on, null for the first line
     */
    void showDirectory(Side side, Path dir, List<FileEntry> entries, SortOrder sort, String focusName);

    void setPanelStatus(Side side, String text);

    /** The panel shows a trash: origin and deletion date instead of modification date and permissions. */
    void setTrashView(Side side, boolean trash);

    void setActive(Side side);

    void setTwoPanels(boolean twoPanels);

    /** Folder tree or two panels; the tree view shows the left panel next to the tree. */
    void setViewMode(ViewMode mode);

    /** The top level of the folder tree. */
    void setPlaces(List<Place> places);

    /** The sub-folders of a folder in the tree, after it was asked for with {@code MainPresenter.expandFolder}. */
    void showFolders(Path dir, List<Path> folders);

    /** Reads the folders of the tree again that were read before. */
    void refreshFolders();

    void setShowHidden(boolean showHidden);

    void setStatus(String text);

    void showError(String message);

    boolean confirm(String title, String message);

    /** A line of text; null if cancelled. */
    String askText(String title, String message, String initial);

    ConflictChoice askConflict(FileEntry source, FileEntry target);

    /** Shows a progress dialog; {@code cancel} runs when the user presses Cancel. */
    ProgressView showProgress(String title, Runnable cancel);

    void copyToClipboard(String text);

    /** Files for Paste here and in other file managers; an empty list clears them. */
    void putFilesOnClipboard(ClipboardFiles files);

    /** The files on the clipboard, null if there are none. */
    ClipboardFiles filesOnClipboard();

    void dispose();
}
