package eu.amadeos.files.domain;

import java.text.Collator;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * How a panel is sorted. The parent entry always comes first and folders always before files,
 * as in other orthodox file managers; only the order within those groups follows the key.
 */
public record SortOrder(SortKey key, boolean ascending) {
    public static final SortOrder DEFAULT = new SortOrder(SortKey.NAME, true);

    public SortOrder {
        Objects.requireNonNull(key, "key");
    }

    /** Clicking the same column again reverses the order; another column starts ascending. */
    public SortOrder toggle(SortKey newKey) {
        return newKey == key ? new SortOrder(key, !ascending) : new SortOrder(newKey, true);
    }

    public Comparator<FileEntry> comparator() {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Comparator<FileEntry> byName = Comparator.comparing(FileEntry::name, collator::compare);
        Comparator<FileEntry> byKey = switch (key) {
            case NAME -> byName;
            case SIZE -> Comparator.comparingLong(FileEntry::size).thenComparing(byName);
            case MODIFIED -> Comparator.comparing(FileEntry::modified,
                    Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).thenComparing(byName);
        };
        if (!ascending) byKey = byKey.reversed();
        return Comparator.comparing(SortOrder::group).thenComparing(byKey);
    }

    private static int group(FileEntry entry) {
        if (entry.isParent()) return 0;
        return entry.isDirectory() ? 1 : 2;
    }
}
