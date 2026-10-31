package eu.amadeos.apps.files.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A USB stick, memory card, external disk or disc with a file system, as {@code lsblk} reports it.
 *
 * @param device     the block device of the file system, such as {@code /dev/sdb1}
 * @param disk       the whole disk it belongs to, such as {@code /dev/sdb}; switched off by Eject
 * @param label      the name of the file system, may be empty
 * @param model      the maker's name of the disk, may be empty
 * @param fileSystem such as {@code vfat}, {@code exfat}, {@code ntfs}, {@code ext4}, {@code iso9660}
 * @param size       in bytes
 * @param mountPoint where it is mounted, null while it is not
 * @param optical    a CD, DVD or Blu-ray disc
 */
public record Drive(String device, String disk, String label, String model, String fileSystem, long size,
                    Path mountPoint, boolean optical) {

    public Drive {
        Objects.requireNonNull(device, "device");
        disk = disk == null || disk.isBlank() ? device : disk;
        label = label == null ? "" : label.strip();
        model = model == null ? "" : model.strip();
        fileSystem = fileSystem == null ? "" : fileSystem;
    }

    public boolean mounted() {
        return mountPoint != null;
    }

    /** The name in the folder tree: the label, otherwise the size and what it is ("16 GB Drive"). */
    public String name() {
        if (!label.isEmpty()) return label;
        String kind = optical ? "Disc" : "Drive";
        return size > 0 ? sizeText(size) + " " + kind : kind;
    }

    /** The same drive mounted somewhere. */
    public Drive mountedAt(Path path) {
        return new Drive(device, disk, label, model, fileSystem, size, path, optical);
    }

    /** Sizes as the maker prints them on the stick: decimal and rounded ("16 GB", "500 MB"). */
    static String sizeText(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return (value >= 10 || unit == 0 ? String.valueOf(Math.round(value))
                : String.valueOf(Math.round(value * 10) / 10.0).replace(".0", "")) + " " + units[unit];
    }
}
