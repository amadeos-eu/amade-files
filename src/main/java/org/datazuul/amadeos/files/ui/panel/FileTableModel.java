package org.datazuul.amadeos.files.ui.panel;

import org.datazuul.amadeos.files.domain.FileEntry;
import org.datazuul.amadeos.files.domain.SortKey;
import org.datazuul.amadeos.files.service.Formats;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The lines of one panel and which of them are marked. Marks are separate from the cursor (the
 * table selection), so moving the cursor keeps them.
 */
final class FileTableModel extends AbstractTableModel {
    static final int NAME = 0;
    static final int SIZE = 1;
    static final int MODIFIED = 2;
    static final int PERMISSIONS = 3;
    private static final String[] COLUMNS = {"Name", "Size", "Modified", "Permissions"};
    private static final SortKey[] SORT_KEYS = {SortKey.NAME, SortKey.SIZE, SortKey.MODIFIED, null};

    private List<FileEntry> entries = List.of();
    private final Set<Path> marked = new HashSet<>();

    void setEntries(List<FileEntry> newEntries) {
        entries = List.copyOf(newEntries);
        marked.clear();
        fireTableDataChanged();
    }

    FileEntry entry(int row) {
        return row >= 0 && row < entries.size() ? entries.get(row) : null;
    }

    int indexOf(String name) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).name().equals(name)) return i;
        return -1;
    }

    boolean isMarked(int row) {
        FileEntry entry = entry(row);
        return entry != null && marked.contains(entry.path());
    }

    /** The parent line cannot be marked. */
    void setMarked(int row, boolean mark) {
        FileEntry entry = entry(row);
        if (entry == null || entry.isParent()) return;
        if (mark ? marked.add(entry.path()) : marked.remove(entry.path())) fireTableRowsUpdated(row, row);
    }

    void toggle(int row) {
        setMarked(row, !isMarked(row));
    }

    void markAll(boolean mark) {
        for (int i = 0; i < entries.size(); i++) {
            FileEntry entry = entries.get(i);
            if (!entry.isParent()) {
                if (mark) marked.add(entry.path());
                else marked.remove(entry.path());
            }
        }
        fireTableDataChanged();
    }

    void invertMarks() {
        for (FileEntry entry : entries) {
            if (!entry.isParent() && !marked.remove(entry.path())) marked.add(entry.path());
        }
        fireTableDataChanged();
    }

    List<FileEntry> markedEntries() {
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : entries) if (marked.contains(entry.path())) result.add(entry);
        return result;
    }

    static SortKey sortKey(int column) {
        return column >= 0 && column < SORT_KEYS.length ? SORT_KEYS[column] : null;
    }

    static int column(SortKey key) {
        for (int i = 0; i < SORT_KEYS.length; i++) if (SORT_KEYS[i] == key) return i;
        return -1;
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        FileEntry e = entries.get(row);
        return switch (column) {
            case NAME -> e.isParent() ? ".." : e.name();
            case SIZE -> e.isParent() ? "" : e.isDirectory() ? "<DIR>" : Formats.size(e.size());
            case MODIFIED -> Formats.date(e.modified());
            case PERMISSIONS -> e.permissions();
            default -> "";
        };
    }
}
