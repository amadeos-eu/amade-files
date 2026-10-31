package eu.amadeos.apps.files.ui.main;

import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.ViewMode;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * File, Edit, View, Go and Help. Function keys follow the orthodox file managers (Midnight Commander,
 * Total Commander): F3 view, F4 edit, F5 copy, F6 move, F7 new folder, F8 delete.
 */
final class MainMenuBar extends JMenuBar {
    private final JRadioButtonMenuItem treeView = new JRadioButtonMenuItem("Tree");
    private final JRadioButtonMenuItem panelsView = new JRadioButtonMenuItem("Two Panels");
    private final JCheckBoxMenuItem twoPanels = new JCheckBoxMenuItem("Show Second Panel");
    private final JCheckBoxMenuItem showHidden = new JCheckBoxMenuItem("Show Hidden Files");

    MainMenuBar(MainPresenter presenter, MainWindow window) {
        int ctrl = InputEvent.CTRL_DOWN_MASK;
        int shift = InputEvent.SHIFT_DOWN_MASK;
        int alt = InputEvent.ALT_DOWN_MASK;

        JMenu file = menu("File", KeyEvent.VK_F);
        file.add(item("View", key(KeyEvent.VK_F3, 0), presenter::view));
        file.add(item("Edit", key(KeyEvent.VK_F4, 0), presenter::edit));
        file.addSeparator();
        file.add(item("Copy…", key(KeyEvent.VK_F5, 0), presenter::copy));
        file.add(item("Move…", key(KeyEvent.VK_F6, 0), presenter::move));
        file.add(item("New Folder…", key(KeyEvent.VK_F7, 0), presenter::newFolder));
        file.add(item("Rename…", key(KeyEvent.VK_F2, 0), presenter::rename));
        file.addSeparator();
        file.add(item("Extract Here", null, presenter::extractHere));
        file.add(item("Extract To…", null, presenter::extractTo));
        file.add(item("Compress…", null, presenter::compress));
        file.addSeparator();
        file.add(item("Connect to Server…", key(KeyEvent.VK_K, ctrl), presenter::connectToServer));
        file.addSeparator();
        file.add(item("Move to Trash", key(KeyEvent.VK_F8, 0), presenter::moveToTrash));
        file.add(item("Delete Permanently…", key(KeyEvent.VK_F8, shift), presenter::deletePermanently));
        file.addSeparator();
        file.add(item("Restore from Trash", null, presenter::restore));
        file.add(item("Empty Trash…", null, presenter::emptyTrash));
        file.addSeparator();
        file.add(item("Copy Path", key(KeyEvent.VK_C, ctrl | shift), presenter::copyPaths));
        file.addSeparator();
        file.add(item("Close", key(KeyEvent.VK_Q, ctrl), presenter::exit));
        add(file);

        JMenu edit = menu("Edit", KeyEvent.VK_E);
        edit.add(item("Cut", key(KeyEvent.VK_X, ctrl), presenter::cutFiles));
        edit.add(item("Copy", key(KeyEvent.VK_C, ctrl), presenter::copyFiles));
        edit.add(item("Paste", key(KeyEvent.VK_V, ctrl), presenter::paste));
        edit.addSeparator();
        edit.add(item("Select All", key(KeyEvent.VK_A, ctrl), () -> window.markAll(true)));
        edit.add(item("Deselect All", key(KeyEvent.VK_A, ctrl | shift), () -> window.markAll(false)));
        edit.add(item("Invert Selection", key(KeyEvent.VK_I, ctrl), window::invertMarks));
        add(edit);

        JMenu view = menu("View", KeyEvent.VK_V);
        ButtonGroup modes = new ButtonGroup();
        modes.add(treeView);
        modes.add(panelsView);
        treeView.setAccelerator(key(KeyEvent.VK_1, ctrl));
        treeView.addActionListener(e -> presenter.setViewMode(ViewMode.TREE));
        panelsView.setAccelerator(key(KeyEvent.VK_2, ctrl));
        panelsView.addActionListener(e -> presenter.setViewMode(ViewMode.PANELS));
        view.add(treeView);
        view.add(panelsView);
        twoPanels.setAccelerator(key(KeyEvent.VK_F1, ctrl));
        twoPanels.addActionListener(e -> presenter.setTwoPanels(twoPanels.isSelected()));
        view.add(twoPanels);
        showHidden.setAccelerator(key(KeyEvent.VK_H, ctrl));
        showHidden.addActionListener(e -> presenter.setShowHidden(showHidden.isSelected()));
        view.add(showHidden);
        view.addSeparator();
        view.add(item("Sort by Name", key(KeyEvent.VK_F3, ctrl), () -> presenter.sortBy(presenter.activeSide(), SortKey.NAME)));
        view.add(item("Sort by Modified", key(KeyEvent.VK_F5, ctrl), () -> presenter.sortBy(presenter.activeSide(), SortKey.MODIFIED)));
        view.add(item("Sort by Size", key(KeyEvent.VK_F6, ctrl), () -> presenter.sortBy(presenter.activeSide(), SortKey.SIZE)));
        view.addSeparator();
        view.add(item("Refresh", key(KeyEvent.VK_R, ctrl), presenter::refresh));
        add(view);

        JMenu go = menu("Go", KeyEvent.VK_G);
        go.add(item("Up", key(KeyEvent.VK_UP, alt), () -> presenter.up(presenter.activeSide())));
        go.add(item("Home Folder", key(KeyEvent.VK_HOME, alt), presenter::goHome));
        go.add(item("Trash", null, presenter::goTrash));
        go.add(item("Go To Folder…", key(KeyEvent.VK_L, ctrl), window::editPath));
        go.addSeparator();
        go.add(item("Same Folder in Other Panel", key(KeyEvent.VK_EQUALS, ctrl), presenter::sameFolderInOtherPanel));
        go.add(item("Swap Panels", key(KeyEvent.VK_U, ctrl), presenter::swapPanels));
        add(go);

        add(Box.createHorizontalGlue());
        JMenu help = menu("Help", KeyEvent.VK_H);
        help.add(item("Keyboard", key(KeyEvent.VK_F1, 0), () -> showKeys(window)));
        help.add(item("About File Manager", null, () -> showAbout(window)));
        add(help);
    }

    void setTwoPanels(boolean value) {
        twoPanels.setSelected(value);
    }

    void setViewMode(ViewMode mode) {
        treeView.setSelected(mode == ViewMode.TREE);
        panelsView.setSelected(mode == ViewMode.PANELS);
        // One or two panels is a choice of the two-panel view.
        twoPanels.setEnabled(mode == ViewMode.PANELS);
    }

    boolean isShowingHidden() {
        return showHidden.isSelected();
    }

    void setShowHidden(boolean value) {
        showHidden.setSelected(value);
    }

    private static void showKeys(MainWindow window) {
        String text = """
                <html><table cellpadding=2>
                <tr><td><b>Ctrl+1 / Ctrl+2</b></td><td>Tree view / two panels</td></tr>
                <tr><td><b>Enter</b></td><td>Open folder or file</td></tr>
                <tr><td><b>Backspace</b></td><td>Parent folder</td></tr>
                <tr><td><b>Tab</b></td><td>Other panel; in the tree view between tree and contents</td></tr>
                <tr><td><b>Ctrl+X / Ctrl+C / Ctrl+V</b></td><td>Cut / copy / paste files</td></tr>
                <tr><td><b>Insert, Space</b></td><td>Select line and move down</td></tr>
                <tr><td><b>Ctrl+Click, Shift+Click</b></td><td>Select line, select range</td></tr>
                <tr><td><b>F2</b></td><td>Rename</td></tr>
                <tr><td><b>F3 / F4</b></td><td>View / edit in the Text Editor</td></tr>
                <tr><td><b>F5 / F6</b></td><td>Copy / move to the other panel</td></tr>
                <tr><td><b>F7</b></td><td>New folder</td></tr>
                <tr><td><b>F8, Delete</b></td><td>Move to trash</td></tr>
                <tr><td><b>Shift+F8, Shift+Delete</b></td><td>Delete permanently (in the trash also F8)</td></tr>
                <tr><td><b>Ctrl+H</b></td><td>Show hidden files</td></tr>
                <tr><td><b>Ctrl+L</b></td><td>Type a path in the path bar (Tab completes); smb://, nfs://, davs:// connect</td></tr>
                <tr><td><b>Ctrl+K</b></td><td>Connect to Server (network share, NFS, WebDAV, FTP, SFTP)</td></tr>
                <tr><td><b>Ctrl+U</b></td><td>Swap panels</td></tr>
                <tr><td><b>Ctrl+=</b></td><td>Same folder in the other panel</td></tr>
                <tr><td><b>Ctrl+F1</b></td><td>One or two panels (two-panel view)</td></tr>
                <tr><td><b>F10, Ctrl+Q</b></td><td>Quit</td></tr>
                </table></html>""";
        // As a label: JOptionPane would split a String message at each line break and show the HTML as text.
        JOptionPane.showMessageDialog(window, new JLabel(text), "File Manager - Keyboard", JOptionPane.PLAIN_MESSAGE);
    }

    private static void showAbout(MainWindow window) {
        String version = MainMenuBar.class.getPackage().getImplementationVersion();
        JOptionPane.showMessageDialog(window, "amadeOS File Manager" + (version != null ? " " + version : "")
                        + "\nFile manager with folder tree and two panels for the amadeOS desktop.\n\nLicensed under the EUPL-1.2.",
                "File Manager - About", JOptionPane.INFORMATION_MESSAGE);
    }

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private static JMenu menu(String text, int mnemonic) {
        JMenu menu = new JMenu(text);
        menu.setMnemonic(mnemonic);
        return menu;
    }

    private static JMenuItem item(String text, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        return item;
    }
}
