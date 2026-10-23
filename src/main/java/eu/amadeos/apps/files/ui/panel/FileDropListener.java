package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.apps.files.domain.DropMode;

import java.awt.Component;
import java.awt.Point;
import java.nio.file.Path;
import java.util.List;

/**
 * Drag and drop of files between the panels, the folder tree and other programs; called on the EDT.
 */
public interface FileDropListener {
    /**
     * What dropping would do, for the hint next to the mouse; null where the files cannot go.
     *
     * @param sources empty while another program does not tell the files yet
     */
    String describeDrop(List<Path> sources, Path target, DropMode mode);

    void drop(List<Path> sources, Path target, DropMode mode);

    /** Shows the hint at a point of the component, or hides it for null. */
    void dropHint(Component component, Point point, String text);
}
