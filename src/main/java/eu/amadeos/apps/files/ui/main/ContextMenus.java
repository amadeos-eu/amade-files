package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.domain.Side;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

/**
 * The context menus of the lines, of the free space of a panel and of the folders in the tree. The
 * right mouse button first makes the line or folder the selection, so the commands work on it as
 * they do from the menu bar and the keyboard.
 */
final class ContextMenus {
    private final MainWindow window;
    private MainPresenter presenter;

    ContextMenus(MainWindow window) {
        this.window = window;
    }

    void bind(MainPresenter presenter) {
        this.presenter = presenter;
    }

    /** A file or folder in a panel. */
    JPopupMenu line(Side side, FileEntry entry, boolean inTrash) {
        JPopupMenu menu = new JPopupMenu();
        if (inTrash) {
            menu.add(item("Restore", null, presenter::restore));
            menu.add(item("Delete Permanently…", key(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), presenter::deletePermanently));
            menu.addSeparator();
            menu.add(item("Empty Trash…", null, presenter::emptyTrash));
            menu.add(item("Copy Path", key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), presenter::copyPaths));
            return menu;
        }
        boolean folder = entry.isDirectory();
        JMenuItem open = item("Open", key(KeyEvent.VK_ENTER, 0), () -> presenter.open(side, entry));
        open.setFont(open.getFont().deriveFont(Font.BOLD));
        menu.add(open);
        if (folder && window.isTreeView()) menu.add(item("Open in Two Panels", null, () -> presenter.openInPanels(entry.path())));
        if (!folder) {
            menu.add(item("View", key(KeyEvent.VK_F3, 0), presenter::view));
            menu.add(item("Edit in Text Editor", key(KeyEvent.VK_F4, 0), presenter::edit));
        }
        menu.addSeparator();
        addClipboard(menu, true);
        JMenuItem paste = folder ? item("Paste into Folder", null, () -> presenter.pasteInto(entry.path()))
                : item("Paste", key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), presenter::paste);
        paste.setEnabled(hasFiles());
        menu.add(paste);
        menu.addSeparator();
        menu.add(item("Rename…", key(KeyEvent.VK_F2, 0), presenter::rename));
        menu.add(item("Move to Trash", key(KeyEvent.VK_DELETE, 0), presenter::moveToTrash));
        menu.add(item("Delete Permanently…", key(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), presenter::deletePermanently));
        menu.addSeparator();
        menu.add(item("Copy Path", key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), presenter::copyPaths));
        return menu;
    }

    /** The free space of a panel below its lines: the folder itself. */
    JPopupMenu folderSpace(Path dir, boolean inTrash) {
        JPopupMenu menu = new JPopupMenu();
        if (inTrash) {
            menu.add(item("Empty Trash…", null, presenter::emptyTrash));
        } else {
            menu.add(item("New Folder…", key(KeyEvent.VK_F7, 0), presenter::newFolder));
            JMenuItem paste = item("Paste", key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), presenter::paste);
            paste.setEnabled(hasFiles());
            menu.add(paste);
        }
        menu.addSeparator();
        JCheckBoxMenuItem hidden = new JCheckBoxMenuItem("Show Hidden Files", window.isShowingHidden());
        hidden.setAccelerator(key(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK));
        hidden.addActionListener(e -> presenter.setShowHidden(hidden.isSelected()));
        menu.add(hidden);
        menu.add(item("Refresh", key(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), presenter::refresh));
        if (dir != null) {
            menu.addSeparator();
            menu.add(item("Copy Path", null, () -> presenter.copyPath(dir)));
        }
        return menu;
    }

    /** A folder in the tree; {@code place} for home, trash, file system and media, which stay where they are. */
    JPopupMenu treeFolder(Path dir, Place place) {
        JPopupMenu menu = new JPopupMenu();
        if (place != null && place.kind() == Place.Kind.TRASH) {
            menu.add(item("Empty Trash…", null, presenter::emptyTrash));
            menu.add(item("Open in Two Panels", null, () -> presenter.openInPanels(dir)));
            menu.addSeparator();
            menu.add(item("Copy Path", null, () -> presenter.copyPath(dir)));
            return menu;
        }
        menu.add(item("Open in Two Panels", null, () -> presenter.openInPanels(dir)));
        menu.addSeparator();
        menu.add(item("New Folder…", key(KeyEvent.VK_F7, 0), presenter::newFolder));
        JMenuItem paste = item("Paste into Folder", key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), () -> presenter.pasteInto(dir));
        paste.setEnabled(hasFiles());
        menu.add(paste);
        menu.addSeparator();
        boolean movable = place == null;
        addClipboard(menu, movable);
        menu.add(enabled(item("Rename…", key(KeyEvent.VK_F2, 0), presenter::rename), movable));
        menu.add(enabled(item("Move to Trash", key(KeyEvent.VK_DELETE, 0), presenter::moveToTrash), movable));
        menu.addSeparator();
        menu.add(item("Copy Path", key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                () -> presenter.copyPath(dir)));
        return menu;
    }

    private void addClipboard(JPopupMenu menu, boolean canCut) {
        menu.add(enabled(item("Cut", key(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), presenter::cutFiles), canCut));
        menu.add(item("Copy", key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), presenter::copyFiles));
    }

    private boolean hasFiles() {
        ClipboardFiles files = window.clipboardFiles();
        return files != null && !files.paths().isEmpty();
    }

    private static JMenuItem enabled(JMenuItem item, boolean enabled) {
        item.setEnabled(enabled);
        return item;
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    /** The accelerator is only shown here; the keys belong to the menu bar and the window. */
    private static JMenuItem item(String text, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        return item;
    }
}
