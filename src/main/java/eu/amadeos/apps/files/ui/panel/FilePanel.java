package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import eu.amadeos.apps.files.domain.FileEntry;
import eu.amadeos.apps.files.domain.SortKey;
import eu.amadeos.apps.files.domain.SortOrder;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One file panel: iconic path on top, the file list, and a status line with the selection and the
 * free space.
 *
 * <p>Keys: Enter opens, Backspace goes up, Tab switches to the other panel, Insert or Space marks
 * the line and moves down, Home/End jump to the first/last line. Ctrl+click marks a line,
 * Shift+click marks a range.
 *
 * <p>In the tree view the panel is the contents side of the window: the window shows its path bar
 * and status line, a plain click selects only that line, and selected lines look selected instead of
 * bold and red. The right mouse button opens the context menu; files can be dragged out and dropped
 * on folders in both views.
 */
public final class FilePanel extends JPanel {

    /** Events of a panel; called on the EDT. */
    public interface Listener {
        void activated();

        void selectionChanged(List<FileEntry> marked, FileEntry current);

        void open(FileEntry entry);

        void up();

        void navigate(Path dir);

        void sortBy(SortKey key);

        void switchPanel();

        /** Right mouse button on a line, or on the free space below the lines for {@code entry == null}. */
        void popup(FileEntry entry, Component component, int x, int y);

        /** A path typed into the path bar. */
        void typedPath(String text);

        /** Copy Path in the path bar's context menu. */
        void copyPath(Path path);

        /** Tab in the path bar. */
        String completePath(String text);
    }

    private static final Color MARK_LIGHT = new Color(0xB00000);
    private static final Color MARK_DARK = new Color(0xFF8A80);

    private final FileTableModel model = new FileTableModel();
    private final JTable table = new JTable(model);
    private final IconicPathBar pathBar = new IconicPathBar();
    private final JLabel status = new JLabel(" ");
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final JScrollPane scroll = new JScrollPane(table);
    private final DropHandler dropHandler = new DropHandler();
    private Listener listener;
    private SortOrder sort = SortOrder.DEFAULT;
    private Path dir;
    private boolean explorer;
    private boolean updating;
    /** The cursor line, and the one before; the table has moved the cursor by the time a click reaches us. */
    private int cursorRow = -1;
    private int previousRow = -1;

    public FilePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(Math.max(FileIcons.SIZE + 2, table.getFontMetrics(table.getFont()).getHeight() + 3));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);
        configureColumns();
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (row != cursorRow) {
                previousRow = cursorRow;
                cursorRow = row;
            }
            if (!e.getValueIsAdjusting() && !updating) fireSelection();
        });
        table.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                table.repaint();
                if (listener != null) listener.activated();
            }

            @Override
            public void focusLost(FocusEvent e) {
                table.repaint();
            }
        });
        Mouse mouse = new Mouse();
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);
        installKeys();

        JTableHeader header = table.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                SortKey key = model.sortKey(header.columnAtPoint(e.getPoint()));
                if (key != null && listener != null) listener.sortBy(key);
            }
        });

        pathBar.setNavigate(dir -> {
            if (listener != null) listener.navigate(dir);
        });
        pathBar.setTyped(text -> {
            if (listener != null) listener.typedPath(text);
        });
        pathBar.setCopy(path -> {
            if (listener != null) listener.copyPath(path);
        });
        pathBar.setComplete(text -> listener == null ? text : listener.completePath(text));
        pathBar.setDone(table::requestFocusInWindow);
        MouseAdapter activate = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                table.requestFocusInWindow();
            }
        };
        pathBar.addMouseListener(activate);
        status.addMouseListener(activate);

        scroll.setBorder(AmadeUI.sunken());
        scroll.getViewport().addMouseListener(activate);
        scroll.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && listener != null) {
                    table.requestFocusInWindow();
                    listener.popup(null, scroll.getViewport(), e.getX(), e.getY());
                }
            }
        });
        table.setDragEnabled(true);
        table.setDropMode(javax.swing.DropMode.ON);
        // The table fills the viewport, so dropping below the lines lands on the table: the panel's folder.
        dropHandler.install(table);
        AmadeUI.style(scroll.getViewport(), (viewport, theme) -> viewport.setBackground(theme.fieldBackground()));

        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        statusBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        statusBar.add(status, BorderLayout.CENTER);

        add(pathBar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setDropListener(FileDropListener dropListener) {
        dropHandler.setListener(dropListener);
    }

    /**
     * Tree view: the window shows the path bar and the status line (see {@link #pathBar()} and
     * {@link #statusBar()}), and selection works as in other graphical file managers.
     */
    public void setExplorer(boolean value) {
        explorer = value;
        if (value) {
            remove(pathBar);
            remove(statusBar);
        } else {
            add(pathBar, BorderLayout.NORTH);
            add(statusBar, BorderLayout.SOUTH);
        }
        revalidate();
        table.repaint();
    }

    public JComponent pathBar() {
        return pathBar;
    }

    public JComponent statusBar() {
        return statusBar;
    }

    /** Names for the folders of connected network shares in the path bar. */
    public void setFolderNames(Map<Path, String> names) {
        pathBar.setNames(names);
    }

    /** Ctrl+L: the path bar as text. */
    public void editPath() {
        pathBar.edit();
    }

    /**
     * @param focusName line to put the cursor on; the first line if null or not found
     */
    public void show(Path dir, List<FileEntry> entries, SortOrder newSort, String focusName) {
        updating = true;
        try {
            this.dir = dir;
            sort = newSort;
            model.setEntries(entries);
            pathBar.setPath(dir);
            showSortInHeader();
            int row = focusName == null ? -1 : model.indexOf(focusName);
            if (row < 0 && model.getRowCount() > 0) row = 0;
            if (row >= 0) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
            }
        } finally {
            updating = false;
        }
        fireSelection();
    }

    public void setStatus(String text) {
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    /** Trash columns (origin, deletion date) or the usual ones; called before {@link #show}. */
    public void setTrashView(boolean trash) {
        if (model.isTrashView() == trash) return;
        model.setTrashView(trash);
        // The table made new columns for the new structure: renderer and widths again.
        configureColumns();
    }

    public void setActive(boolean active) {
        pathBar.setActive(active);
        table.repaint();
    }

    public void focusList() {
        table.requestFocusInWindow();
    }

    public void markAll(boolean mark) {
        model.markAll(mark);
        fireSelection();
    }

    public void invertMarks() {
        model.invertMarks();
        fireSelection();
    }

    // ---- internals ------------------------------------------------------------------------------

    private void configureColumns() {
        Renderer renderer = new Renderer();
        for (int i = 0; i < model.getColumnCount(); i++) table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        width(FileTableModel.NAME, 260, -1);
        width(FileTableModel.SIZE, 70, 110);
        if (model.isTrashView()) {
            width(FileTableModel.ORIGIN, 220, -1);
            width(FileTableModel.DELETED, 120, 170);
        } else {
            width(FileTableModel.MODIFIED, 120, 170);
            width(FileTableModel.PERMISSIONS, 80, 100);
        }
    }

    /** "Name ▲" on the sorted column. */
    private void showSortInHeader() {
        int sorted = model.column(sort.key());
        for (int i = 0; i < model.getColumnCount(); i++) {
            String name = model.getColumnName(i);
            table.getColumnModel().getColumn(i).setHeaderValue(i == sorted ? name + (sort.ascending() ? " ▲" : " ▼") : name);
        }
        table.getTableHeader().repaint();
    }

    private FileEntry current() {
        return model.entry(table.getSelectedRow());
    }

    private void fireSelection() {
        if (listener != null) listener.selectionChanged(model.markedEntries(), current());
    }

    private void width(int column, int preferred, int max) {
        TableColumn c = table.getColumnModel().getColumn(column);
        c.setPreferredWidth(preferred);
        if (max > 0) c.setMaxWidth(max);
    }

    // --8<-- [start:keys]
    private void installKeys() {
        InputMap keys = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = table.getActionMap();
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "amade.open", () -> {
            FileEntry entry = current();
            if (entry != null && listener != null) listener.open(entry);
        });
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "amade.up", () -> {
            if (listener != null) listener.up();
        });
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "amade.switch", () -> {
            if (listener != null) listener.switchPanel();
        });
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "amade.switch", null);
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "amade.mark", this::markAndMoveDown);
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "amade.mark", null);
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "amade.first", () -> moveTo(0));
        bind(keys, actions, KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "amade.last", () -> moveTo(model.getRowCount() - 1));
        // Leave these to the window's menu accelerators instead of the table's own bindings.
        for (KeyStroke key : List.of(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK),
                // Cut, Copy and Paste are the window's, for files; the table's own would copy cell text.
                KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))) {
            keys.put(key, "none");
        }
    }
    // --8<-- [end:keys]

    /** {@code action == null} binds the key to an action registered before. */
    private static void bind(InputMap keys, ActionMap actions, KeyStroke key, String name, Runnable action) {
        keys.put(key, name);
        if (action != null) {
            actions.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.run();
                }
            });
        }
    }

    private void markAndMoveDown() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        model.toggle(row);
        if (row + 1 < model.getRowCount()) moveTo(row + 1);
        else fireSelection();
    }

    private void moveTo(int row) {
        if (row < 0 || row >= model.getRowCount()) return;
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
    }

    /**
     * Double-click opens; Ctrl+click marks; Shift+click marks from the cursor to the line. In the tree
     * view a plain click selects only the clicked line; on a selected line only once the button is
     * released without dragging, so several selected lines can be dragged.
     */
    private final class Mouse extends MouseAdapter {
        private boolean clearOnRelease;

        @Override
        public void mousePressed(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            clearOnRelease = false;
            if (row >= 0 && SwingUtilities.isRightMouseButton(e)) {
                // The menu is for the line under the mouse; a line outside the selection becomes the selection.
                if (!model.isMarked(row)) {
                    if (!model.markedEntries().isEmpty()) model.markAll(false);
                    moveTo(row);
                }
                table.requestFocusInWindow();
                fireSelection();
                if (listener != null) listener.popup(model.entry(row), table, e.getX(), e.getY());
                return;
            }
            if (row < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
            if (explorer && !e.isControlDown() && !e.isShiftDown()) {
                if (model.isMarked(row)) clearOnRelease = true;
                else if (!model.markedEntries().isEmpty()) {
                    model.markAll(false);
                    fireSelection();
                }
                return;
            }
            if (e.isControlDown()) {
                // In the tree view the line under the cursor already counts as selected: Ctrl+click adds to it.
                int before = cursorBefore(row);
                if (explorer && model.markedEntries().isEmpty() && before >= 0 && before != row) model.setMarked(before, true);
                model.toggle(row);
                e.consume();
                moveTo(row);
                fireSelection();
            } else if (e.isShiftDown()) {
                int from = Math.max(0, cursorBefore(row));
                for (int i = Math.min(from, row); i <= Math.max(from, row); i++) model.setMarked(i, true);
                e.consume();
                moveTo(row);
                fireSelection();
            }
        }

        /** Where the cursor was before this click moved it to {@code row}. */
        private int cursorBefore(int row) {
            return cursorRow == row ? previousRow : cursorRow;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            clearOnRelease = false;
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (!clearOnRelease) return;
            clearOnRelease = false;
            int row = table.rowAtPoint(e.getPoint());
            model.markAll(false);
            if (row >= 0) moveTo(row);
            fireSelection();
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown() && !e.isShiftDown()) {
                FileEntry entry = model.entry(table.rowAtPoint(e.getPoint()));
                if (entry != null && listener != null) listener.open(entry);
            }
        }
    }

    private static boolean focused(JTable t) {
        return t == KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
    }

    /** Icon and name in the first column, numbers right-aligned, marked lines bold and red. */
    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, false, row, column);
            int modelColumn = t.convertColumnIndexToModel(column);
            FileEntry entry = model.entry(row);
            DesktopTheme theme = AmadeUI.theme();
            setIcon(modelColumn == FileTableModel.NAME && entry != null ? FileIcons.of(entry) : null);
            setHorizontalAlignment(modelColumn == FileTableModel.SIZE ? RIGHT : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            boolean marked = model.isMarked(row);
            // The cursor is only highlighted in the panel that has the keyboard focus.
            boolean cursor = selected && t.isFocusOwner();
            JTable.DropLocation drop = t.getDropLocation();
            boolean dropTarget = drop != null && drop.getRow() == row && entry != null && entry.isDirectory();
            if (dropTarget) {
                setBackground(theme.selectionBackground());
                setForeground(theme.selectionText());
            } else if (explorer) {
                // Selected lines look selected; without the focus in a softer colour.
                boolean chosen = marked || selected;
                // The permanent focus owner keeps the colour while a context menu is open.
                boolean strong = focused(t) || t.getDropLocation() != null;
                setBackground(chosen ? (strong ? theme.selectionBackground() : theme.sunkenBg()) : theme.fieldBackground());
                setForeground(chosen && strong ? theme.selectionText() : theme.fieldText());
                setFont(t.getFont());
                return this;
            } else if (cursor) {
                setBackground(theme.selectionBackground());
                setForeground(marked ? (theme.isDark() ? MARK_LIGHT : MARK_DARK) : theme.selectionText());
            } else {
                setBackground(selected ? theme.sunkenBg() : theme.fieldBackground());
                setForeground(marked ? (theme.isDark() ? MARK_DARK : MARK_LIGHT) : theme.fieldText());
            }
            setFont(t.getFont().deriveFont(marked ? Font.BOLD : Font.PLAIN));
            return this;
        }
    }

    /** Drags the selected lines; drops on a folder line, or elsewhere into the panel's folder. */
    private final class DropHandler extends FileDropHandler {
        @Override
        List<Path> dragged() {
            List<FileEntry> marked = model.markedEntries();
            FileEntry current = current();
            List<FileEntry> items = !marked.isEmpty() ? marked : current != null ? List.of(current) : List.of();
            return items.stream().filter(e -> !e.isParent()).map(FileEntry::path).toList();
        }

        @Override
        Path dropTarget(TransferSupport support) {
            if (dir == null) return null;
            if (support.getComponent() == table && !model.isTrashView()
                    && support.getDropLocation() instanceof JTable.DropLocation location) {
                FileEntry entry = model.entry(location.getRow());
                if (entry != null && entry.isDirectory()) return entry.path();
            }
            return dir;
        }
    }
}
