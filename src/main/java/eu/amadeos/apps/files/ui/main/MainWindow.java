package eu.amadeos.apps.files.ui.main;

import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.DropMode;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.apps.files.service.ArchiveService;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.domain.ViewMode;
import eu.amadeos.apps.files.ui.dialogs.ConflictDialog;
import eu.amadeos.apps.files.ui.dialogs.ConnectDialog;
import eu.amadeos.apps.files.ui.dialogs.LoginDialog;
import eu.amadeos.apps.files.ui.dialogs.ProgressDialog;
import eu.amadeos.apps.files.ui.panel.FileDropListener;
import eu.amadeos.apps.files.ui.panel.FilePanel;
import eu.amadeos.apps.files.ui.panel.FileTransfer;
import eu.amadeos.apps.files.ui.panel.FolderTree;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The File Manager window, in one of two views: the folder tree with the contents of the chosen folder
 * (functions in the menus, the context menus and by drag and drop), or two panels side by side (or
 * one) with the function-key bar. Both have a status line.
 */
public final class MainWindow extends JFrame implements MainView {
    /** Every few seconds the tree looks for USB sticks and other media that came or went. */
    private static final int PLACES_POLL_MS = 3000;
    /** The format chosen last in the Compress dialog, for the next one. */
    private ArchiveService.Format lastFormat = ArchiveService.Format.ZIP;

    private final Map<Side, FilePanel> panels = new EnumMap<>(Side.class);
    private final Map<Side, Path> dirs = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> trashViews = new EnumMap<>(Side.class);
    private Map<Path, String> folderNames = Map.of();
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JSplitPane treeSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final FolderTree tree = new FolderTree();
    private final JPanel center = new JPanel(new BorderLayout());
    private final JPanel top = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final JPanel statusRow = new JPanel(new GridLayout(1, 0, 2, 0));
    private final JPanel functionKeys = new JPanel(new GridLayout(1, 0, 4, 0));
    private final DropHint dropHint = new DropHint();
    private final ContextMenus menus = new ContextMenus(this);
    private MainPresenter presenter;
    private MainMenuBar menuBar;
    private boolean twoPanels = true;
    private ViewMode viewMode = ViewMode.TREE;
    private Side active = Side.LEFT;

    public MainWindow() {
        super("File Manager");
        EdtExecutor.requireEdt();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        URL icon = getClass().getResource("/icons/amade-files.png");
        if (icon != null) setIconImage(new ImageIcon(icon).getImage());

        for (Side side : Side.values()) panels.put(side, new FilePanel());
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        treeSplit.setResizeWeight(0.25);
        treeSplit.setContinuousLayout(true);
        treeSplit.setBorder(BorderFactory.createEmptyBorder());
        tree.setPreferredSize(new Dimension(260, 400));
        center.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        top.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));

        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        statusBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        statusBar.add(status, BorderLayout.CENTER);
        functionKeys.setBorder(BorderFactory.createEmptyBorder(3, 4, 4, 4));
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusRow, BorderLayout.NORTH);
        south.add(functionKeys, BorderLayout.SOUTH);
        layoutPanels();

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getLayeredPane().add(dropHint, JLayeredPane.DRAG_LAYER);
        setPreferredSize(new Dimension(1100, 720));
        pack();
        setLocationRelativeTo(null);
    }

    public void bind(MainPresenter presenter) {
        EdtExecutor.requireEdt();
        this.presenter = presenter;
        menuBar = new MainMenuBar(presenter, this);
        setJMenuBar(menuBar);
        menus.bind(presenter);
        for (Side side : Side.values()) {
            panels.get(side).setListener(listener(side));
            panels.get(side).setDropListener(dropListener());
        }
        tree.setListener(treeListener());
        tree.setDropListener(dropListener());
        Timer places = new Timer(PLACES_POLL_MS, e -> {
            if (isShowing() && viewMode == ViewMode.TREE) presenter.refreshPlaces();
        });
        places.start();

        functionKey("F2 Rename", presenter::rename);
        functionKey("F3 View", presenter::view);
        functionKey("F4 Edit", presenter::edit);
        functionKey("F5 Copy", presenter::copy);
        functionKey("F6 Move", presenter::move);
        functionKey("F7 New Folder", presenter::newFolder);
        functionKey("F8 Trash", presenter::moveToTrash);
        functionKey("F10 Quit", presenter::exit);

        // Keys besides the menu accelerators. F10 would otherwise open the menu bar (Swing default).
        menuBar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), "none");
        windowKey(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), "quit", presenter::exit);
        windowKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "trash", presenter::moveToTrash);
        windowKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), "delete", presenter::deletePermanently);
        windowKey(KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.SHIFT_DOWN_MASK), "rename", presenter::rename);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                presenter.exit();
            }
        });
        // Coming back to the window puts the keyboard focus into the active panel.
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                panels.get(active).focusList();
            }
        });
    }

    private FilePanel.Listener listener(Side side) {
        return new FilePanel.Listener() {
            @Override
            public void activated() {
                presenter.panelActivated(side);
            }

            @Override
            public void selectionChanged(List<FileEntry> marked, FileEntry current) {
                presenter.selectionChanged(side, marked, current);
            }

            @Override
            public void open(FileEntry entry) {
                presenter.open(side, entry);
            }

            @Override
            public void up() {
                presenter.up(side);
            }

            @Override
            public void navigate(Path dir) {
                presenter.navigate(side, dir);
            }

            @Override
            public void sortBy(SortKey key) {
                presenter.sortBy(side, key);
            }

            @Override
            public void switchPanel() {
                if (viewMode == ViewMode.TREE) tree.focusTree();
                else if (twoPanels) panels.get(side.other()).focusList();
            }

            @Override
            public void popup(FileEntry entry, Component component, int x, int y) {
                JPopupMenu menu = entry == null || entry.isParent()
                        ? menus.folderSpace(dirs.get(side), Boolean.TRUE.equals(trashViews.get(side)))
                        : menus.line(side, entry, Boolean.TRUE.equals(trashViews.get(side)));
                menu.show(component, x, y);
            }

            @Override
            public void typedPath(String text) {
                presenter.goTo(side, text);
            }

            @Override
            public void copyPath(Path path) {
                presenter.copyPath(path);
            }

            @Override
            public String completePath(String text) {
                return presenter.completePath(side, text);
            }
        };
    }

    private FolderTree.Listener treeListener() {
        return new FolderTree.Listener() {
            @Override
            public void folderSelected(Path dir) {
                presenter.folderSelected(dir);
            }

            @Override
            public void focused(Path dir) {
                presenter.treeFocused(dir);
            }

            @Override
            public void expand(Path dir) {
                presenter.expandFolder(dir);
            }

            @Override
            public void popup(Path dir, Place place, Component component, int x, int y) {
                menus.treeFolder(dir, place).show(component, x, y);
            }

            @Override
            public void switchToContents() {
                panels.get(Side.LEFT).focusList();
            }
        };
    }

    private FileDropListener dropListener() {
        return new FileDropListener() {
            @Override
            public String describeDrop(List<Path> sources, Path target, DropMode mode) {
                return presenter.describeDrop(sources, target, mode);
            }

            @Override
            public void drop(List<Path> sources, Path target, DropMode mode) {
                presenter.drop(sources, target, mode);
            }

            @Override
            public void dropHint(Component component, Point point, String text) {
                if (component == null || text == null) {
                    dropHint.setVisible(false);
                    return;
                }
                dropHint.show(SwingUtilities.convertPoint(component, point, getLayeredPane()), text, getLayeredPane().getSize());
            }
        };
    }

    private void functionKey(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(1, 4, 1, 4));
        button.addActionListener(e -> action.run());
        functionKeys.add(button);
    }

    private void windowKey(KeyStroke key, String name, Runnable action) {
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void layoutPanels() {
        center.removeAll();
        top.removeAll();
        statusRow.removeAll();
        FilePanel left = panels.get(Side.LEFT);
        left.setExplorer(viewMode == ViewMode.TREE);
        functionKeys.setVisible(viewMode == ViewMode.PANELS);
        if (viewMode == ViewMode.TREE) {
            // The path bar spans the window; the contents side's status line sits next to the messages.
            split.setLeftComponent(null);
            split.setRightComponent(null);
            top.add(left.pathBar(), BorderLayout.CENTER);
            treeSplit.setLeftComponent(tree);
            treeSplit.setRightComponent(left);
            center.add(treeSplit, BorderLayout.CENTER);
            statusRow.add(left.statusBar());
            statusRow.add(statusBar);
            SwingUtilities.invokeLater(() -> treeSplit.setDividerLocation(Math.max(200, getWidth() / 4)));
        } else if (twoPanels) {
            treeSplit.setRightComponent(null);
            split.setLeftComponent(panels.get(Side.LEFT));
            split.setRightComponent(panels.get(Side.RIGHT));
            center.add(split, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
        } else {
            treeSplit.setRightComponent(null);
            split.setLeftComponent(null);
            split.setRightComponent(null);
            center.add(left, BorderLayout.CENTER);
        }
        if (viewMode == ViewMode.PANELS) statusRow.add(statusBar);
        Container content = getContentPane();
        content.revalidate();
        content.repaint();
    }

    /** Ctrl+L: the path bar of the active panel as text. */
    void editPath() {
        panels.get(active).editPath();
    }

    boolean isShowingHidden() {
        return menuBar != null && menuBar.isShowingHidden();
    }

    boolean isTreeView() {
        return viewMode == ViewMode.TREE;
    }

    ClipboardFiles clipboardFiles() {
        return FileTransfer.readClipboard(Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    private void updateTitle() {
        Path dir = dirs.get(active);
        if (Boolean.TRUE.equals(trashViews.get(active))) setTitle("File Manager - Trash");
        else setTitle(dir == null ? "File Manager" : "File Manager - " + displayName(dir));
    }

    /** A folder of a connected share as "MyCloudEX2Ultra › Public/Photos", others as their path. */
    private String displayName(Path dir) {
        Path best = null;
        for (Path root : folderNames.keySet()) {
            if (dir.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) best = root;
        }
        if (best == null) return dir.toString();
        String rest = best.relativize(dir).toString();
        return folderNames.get(best) + (rest.isEmpty() ? "" : "/" + rest);
    }

    // ---- selection commands of the Edit menu ----------------------------------------------------

    void markAll(boolean mark) {
        panels.get(active).markAll(mark);
    }

    void invertMarks() {
        panels.get(active).invertMarks();
    }

    // ---- MainView -------------------------------------------------------------------------------

    @Override
    public void showDirectory(Side side, Path dir, List<FileEntry> entries, SortOrder sort, String focusName) {
        dirs.put(side, dir);
        panels.get(side).show(dir, entries, sort, focusName);
        if (side == Side.LEFT && viewMode == ViewMode.TREE) tree.reveal(dir);
        if (side == active) updateTitle();
    }

    @Override
    public void setPanelStatus(Side side, String text) {
        panels.get(side).setStatus(text);
    }

    @Override
    public void setTrashView(Side side, boolean trash) {
        trashViews.put(side, trash);
        panels.get(side).setTrashView(trash);
        if (side == active) updateTitle();
    }

    @Override
    public void setActive(Side side) {
        active = side;
        for (Side s : Side.values()) panels.get(s).setActive(s == side);
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus == null || !SwingUtilities.isDescendingFrom(focus, panels.get(side))) panels.get(side).focusList();
        updateTitle();
    }

    @Override
    public void setTwoPanels(boolean value) {
        if (twoPanels == value && center.getComponentCount() > 0) {
            if (menuBar != null) menuBar.setTwoPanels(value);
            return;
        }
        twoPanels = value;
        layoutPanels();
        if (menuBar != null) menuBar.setTwoPanels(value);
        panels.get(active).focusList();
    }

    @Override
    public void setViewMode(ViewMode mode) {
        if (menuBar != null) menuBar.setViewMode(mode);
        if (viewMode == mode) return;
        viewMode = mode;
        layoutPanels();
        Path left = dirs.get(Side.LEFT);
        if (mode == ViewMode.TREE && left != null) tree.reveal(left);
        panels.get(active).focusList();
    }

    @Override
    public void setPlaces(List<Place> places) {
        tree.setPlaces(places);
    }

    @Override
    public void showFolders(Path dir, List<Path> folders) {
        tree.showFolders(dir, folders);
    }

    @Override
    public void showPlaces(Path parent, List<Place> places, boolean complete) {
        tree.showPlaces(parent, places, complete);
    }

    @Override
    public void setFolderNames(Map<Path, String> names) {
        if (names.equals(folderNames)) return;
        folderNames = Map.copyOf(names);
        for (FilePanel panel : panels.values()) panel.setFolderNames(folderNames);
        updateTitle();
    }

    @Override
    public LoginChoice askLogin(LoginRequest request) {
        return LoginDialog.ask(this, request);
    }

    @Override
    public ConnectChoice askConnect(List<NetworkAddress> recent, NetworkAddress initial) {
        return ConnectDialog.ask(this, recent, initial);
    }

    @Override
    public void refreshFolders() {
        tree.refresh();
    }

    @Override
    public void setShowHidden(boolean showHidden) {
        if (menuBar != null) menuBar.setShowHidden(showHidden);
    }

    @Override
    public void setStatus(String text) {
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    @Override
    public void showError(String message) {
        setStatus(message);
        JOptionPane.showMessageDialog(this, message, "File Manager - Error", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public boolean confirm(String title, String message) {
        return JOptionPane.showConfirmDialog(this, message, "File Manager - " + title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    @Override
    public String askText(String title, String message, String initial) {
        JTextField field = new JTextField(initial, 40);
        // A file name gets its base name selected, so typing keeps the extension; paths are selected whole.
        int dot = initial.lastIndexOf('.');
        boolean fileName = !initial.contains("/") && dot > 0;
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (fileName) field.select(0, dot);
                else field.selectAll();
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(new JLabel(message), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
            @Override
            public void selectInitialValue() {
                field.requestFocusInWindow();
            }
        };
        field.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
        JDialog dialog = pane.createDialog(this, "File Manager - " + title);
        dialog.setVisible(true);
        dialog.dispose();
        return Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue()) ? field.getText() : null;
    }

    // --8<-- [start:ask-archive]
    @Override
    public ArchiveChoice askArchive(String message, String initialName) {
        JTextField name = new JTextField(initialName, 30);
        JComboBox<ArchiveService.Format> format = new JComboBox<>(ArchiveService.Format.values());
        format.setSelectedItem(lastFormat);
        JLabel ending = new JLabel(lastFormat.extension());
        format.addActionListener(e -> ending.setText(((ArchiveService.Format) format.getSelectedItem()).extension()));
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(name, BorderLayout.CENTER);
        row.add(ending, BorderLayout.EAST);
        JPanel formatRow = new JPanel(new BorderLayout(6, 0));
        formatRow.setOpaque(false);
        formatRow.add(new JLabel("Format:"), BorderLayout.WEST);
        formatRow.add(format, BorderLayout.CENTER);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
        panel.setOpaque(false);
        panel.add(new JLabel(message));
        panel.add(row);
        panel.add(formatRow);
        JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
            @Override
            public void selectInitialValue() {
                name.requestFocusInWindow();
                name.selectAll();
            }
        };
        name.addActionListener(e -> pane.setValue(JOptionPane.OK_OPTION));
        JDialog dialog = pane.createDialog(this, "File Manager - Compress");
        dialog.setVisible(true);
        dialog.dispose();
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue()) || name.getText().isBlank()) return null;
        lastFormat = (ArchiveService.Format) format.getSelectedItem();
        return new ArchiveChoice(name.getText().strip(), lastFormat);
    }
    // --8<-- [end:ask-archive]

    @Override
    public ConflictChoice askConflict(FileEntry source, FileEntry target) {
        return ConflictDialog.ask(this, source, target);
    }

    @Override
    public ProgressView showProgress(String title, Runnable cancel) {
        return new ProgressDialog(this, title, cancel);
    }

    @Override
    public void putFilesOnClipboard(ClipboardFiles files) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                files.paths().isEmpty() ? new StringSelection("") : FileTransfer.of(files), null);
    }

    @Override
    public ClipboardFiles filesOnClipboard() {
        return clipboardFiles();
    }

    @Override
    public void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        // X11 primary selection, for pasting with the middle mouse button.
        java.awt.datatransfer.Clipboard primary = Toolkit.getDefaultToolkit().getSystemSelection();
        if (primary != null) primary.setContents(selection, selection);
    }
}
