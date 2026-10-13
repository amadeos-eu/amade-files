package org.datazuul.amadeos.files.ui.main;

import org.datazuul.amadeos.files.domain.FileEntry;
import org.datazuul.amadeos.files.domain.Side;
import org.datazuul.amadeos.files.domain.SortOrder;

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

    void setActive(Side side);

    void setTwoPanels(boolean twoPanels);

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

    void dispose();
}
