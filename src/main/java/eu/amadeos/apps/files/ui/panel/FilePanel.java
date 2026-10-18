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

/**
 * One file panel: iconic path on top, the file list, and a status line with the selection and the
 * free space.
 *
 * <p>Keys: Enter opens, Backspace goes up, Tab switches to the other panel, Insert or Space marks
 * the line and moves down, Home/End jump to the first/last line. Ctrl+click marks a line,
 * Shift+click marks a range.
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
    }

    private static final Color MARK_LIGHT = new Color(0xB00000);
    private static final Color MARK_DARK = new Color(0xFF8A80);

    private final FileTableModel model = new FileTableModel();
    private final JTable table = new JTable(model);
    private final IconicPathBar pathBar = new IconicPathBar();
    private final JLabel status = new JLabel(" ");
    private Listener listener;
    private SortOrder sort = SortOrder.DEFAULT;
    private boolean updating;

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
        table.addMouseListener(new Mouse());
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
        MouseAdapter activate = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                table.requestFocusInWindow();
            }
        };
        pathBar.addMouseListener(activate);
        status.addMouseListener(activate);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(AmadeUI.sunken());
        scroll.getViewport().addMouseListener(activate);
        AmadeUI.style(scroll.getViewport(), (viewport, theme) -> viewport.setBackground(theme.fieldBackground()));

        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel statusBar = new JPanel(new BorderLayout());
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

    /**
     * @param focusName line to put the cursor on; the first line if null or not found
     */
    public void show(Path dir, List<FileEntry> entries, SortOrder newSort, String focusName) {
        updating = true;
        try {
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
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))) {
            keys.put(key, "none");
        }
    }

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

    /** Double-click opens; Ctrl+click marks; Shift+click marks from the cursor to the line. */
    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            if (row < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
            if (e.isControlDown()) {
                model.toggle(row);
                e.consume();
                moveTo(row);
                fireSelection();
            } else if (e.isShiftDown()) {
                int from = Math.max(0, table.getSelectedRow());
                for (int i = Math.min(from, row); i <= Math.max(from, row); i++) model.setMarked(i, true);
                e.consume();
                moveTo(row);
                fireSelection();
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown() && !e.isShiftDown()) {
                FileEntry entry = model.entry(table.rowAtPoint(e.getPoint()));
                if (entry != null && listener != null) listener.open(entry);
            }
        }
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
            if (cursor) {
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
}
