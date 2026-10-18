package eu.amadeos.apps.files.ui.main;

import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.Side;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;
import eu.amadeos.apps.files.ui.dialogs.ConflictDialog;
import eu.amadeos.apps.files.ui.dialogs.ProgressDialog;
import eu.amadeos.apps.files.ui.panel.FilePanel;

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
 * The File Manager window: two panels side by side (or one), a status line and the function-key bar.
 */
public final class MainWindow extends JFrame implements MainView {
    private final Map<Side, FilePanel> panels = new EnumMap<>(Side.class);
    private final Map<Side, Path> dirs = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> trashViews = new EnumMap<>(Side.class);
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JPanel center = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JPanel functionKeys = new JPanel(new GridLayout(1, 0, 4, 0));
    private MainPresenter presenter;
    private MainMenuBar menuBar;
    private boolean twoPanels = true;
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
        center.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        layoutPanels();

        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        statusBar.add(status, BorderLayout.CENTER);
        functionKeys.setBorder(BorderFactory.createEmptyBorder(3, 4, 4, 4));
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusBar, BorderLayout.NORTH);
        south.add(functionKeys, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        setPreferredSize(new Dimension(1100, 720));
        pack();
        setLocationRelativeTo(null);
    }

    public void bind(MainPresenter presenter) {
        EdtExecutor.requireEdt();
        this.presenter = presenter;
        menuBar = new MainMenuBar(presenter, this);
        setJMenuBar(menuBar);
        for (Side side : Side.values()) panels.get(side).setListener(listener(side));

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
                if (twoPanels) panels.get(side.other()).focusList();
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
        if (twoPanels) {
            split.setLeftComponent(panels.get(Side.LEFT));
            split.setRightComponent(panels.get(Side.RIGHT));
            center.add(split, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
        } else {
            split.setLeftComponent(null);
            split.setRightComponent(null);
            center.add(panels.get(Side.LEFT), BorderLayout.CENTER);
        }
        center.revalidate();
        center.repaint();
    }

    private void updateTitle() {
        Path dir = dirs.get(active);
        if (Boolean.TRUE.equals(trashViews.get(active))) setTitle("File Manager - Trash");
        else setTitle(dir == null ? "File Manager" : "File Manager - " + dir);
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

    @Override
    public ConflictChoice askConflict(FileEntry source, FileEntry target) {
        return ConflictDialog.ask(this, source, target);
    }

    @Override
    public ProgressView showProgress(String title, Runnable cancel) {
        return new ProgressDialog(this, title, cancel);
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
