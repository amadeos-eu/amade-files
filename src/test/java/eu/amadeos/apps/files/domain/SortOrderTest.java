package eu.amadeos.apps.files.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortOrderTest {
    private static FileEntry file(String name, long size, long time) {
        return new FileEntry(Path.of("/d", name), name, FileEntry.Kind.FILE, size, Instant.ofEpochSecond(time), "", false, false);
    }

    private static FileEntry dir(String name) {
        return new FileEntry(Path.of("/d", name), name, FileEntry.Kind.DIRECTORY, 0, Instant.EPOCH, "", false, false);
    }

    private static List<String> sorted(SortOrder order, FileEntry... entries) {
        return List.of(entries).stream().sorted(order.comparator()).map(FileEntry::name).toList();
    }

    @Test
    void parentFirstThenFoldersThenFiles() {
        assertEquals(List.of("..", "b", "Z", "a.txt", "C.txt"), sorted(SortOrder.DEFAULT,
                file("C.txt", 1, 1), dir("Z"), FileEntry.parent(Path.of("/")), file("a.txt", 1, 1), dir("b")));
    }

    @Test
    void descendingKeepsGroups() {
        SortOrder bySizeDown = new SortOrder(SortKey.SIZE, false);
        assertEquals(List.of("..", "dir", "big", "small"), sorted(bySizeDown,
                file("small", 1, 1), file("big", 100, 1), dir("dir"), FileEntry.parent(Path.of("/"))));
    }

    @Test
    void togglingReversesTheSameKeyOnly() {
        SortOrder order = SortOrder.DEFAULT.toggle(SortKey.NAME);
        assertEquals(new SortOrder(SortKey.NAME, false), order);
        assertEquals(new SortOrder(SortKey.MODIFIED, true), order.toggle(SortKey.MODIFIED));
    }

    @Test
    void hiddenAndParent() {
        assertTrue(file(".bashrc", 1, 1).isHidden());
        assertFalse(FileEntry.parent(Path.of("/")).isHidden());
        assertTrue(FileEntry.parent(Path.of("/")).isDirectory());
    }
}
