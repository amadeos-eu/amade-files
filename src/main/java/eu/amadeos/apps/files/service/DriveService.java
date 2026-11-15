package eu.amadeos.apps.files.service;

import com.google.inject.Inject;
import eu.amadeos.apps.files.domain.Drive;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramMissingException;
import eu.amadeos.commons.process.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * USB sticks, memory cards, external disks and discs: found with {@code lsblk}, mounted, unmounted and
 * switched off with {@code udisksctl} (package udisks2). udisks lets the user of the active session mount
 * removable drives without a password; for anything else the desktop's polkit agent asks.
 *
 * <p>Only drives that can be removed count: {@code RM} or {@code HOTPLUG} set, or on USB, and discs. A
 * disk that the system itself uses (the live medium the computer started from, mounted below
 * {@code /run/live}) is left out with all its partitions.
 */
public class DriveService {
    private static final Logger LOG = LoggerFactory.getLogger(DriveService.class);

    // --8<-- [start:lsblk]
    static final List<String> LSBLK = List.of("lsblk", "--pairs", "--bytes", "--output",
            "PATH,PKNAME,TYPE,FSTYPE,LABEL,SIZE,MOUNTPOINT,RM,HOTPLUG,MODEL,TRAN");
    // --8<-- [end:lsblk]
    /** File systems that hold no files to show: swap, encrypted containers, volume groups and RAID parts. */
    private static final Set<String> NOT_FILES = Set.of("swap", "crypto_LUKS", "LVM2_member", "linux_raid_member",
            "bcache", "zfs_member");
    /** Where the desktop mounts drives: udisks below /media/USER, older systems below /run/media. */
    private static final List<Path> MEDIA_FOLDERS = List.of(Path.of("/media"), Path.of("/run/media"), Path.of("/mnt"));
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(5);
    /** Long enough for the password window of the polkit agent and a slow disc. */
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(120);
    /** A check of a large disk reads all of its file tables. */
    private static final Duration REPAIR_TIMEOUT = Duration.ofHours(2);
    /**
     * File systems that udisks can check and repair (libblockdev: e2fsck, fsck.vfat, fsck.exfat, ntfsfix).
     * NTFS repair only clears the "not cleanly removed" mark and resets the journal; Windows' chkdsk does more.
     */
    static final Set<String> REPAIRABLE = Set.of("ntfs", "vfat", "exfat", "ext2", "ext3", "ext4");
    /**
     * What mount says about a file system that was not unmounted before the drive went away. The kernel's
     * ntfs3 driver refuses such a volume ("volume is dirty and force flag is not set"), which mount reports
     * as "wrong fs type, bad option, bad superblock".
     */
    private static final List<String> NEEDS_CHECK = List.of("wrong fs type", "bad superblock", "dirty",
            "structure needs cleaning", "unclean", "run chkdsk", "fsck");

    private final CommandRunner runner;

    @Inject
    public DriveService() {
        this(CommandRunner.system());
    }

    DriveService(CommandRunner runner) {
        this.runner = runner;
    }

    /** The removable drives with a file system, mounted or not; empty if {@code lsblk} fails. */
    public List<Drive> drives() {
        try {
            return parse(runner.output(LSBLK, LIST_TIMEOUT));
        } catch (IOException e) {
            LOG.debug("Cannot list the drives", e);
            return List.of();
        }
    }

    // --8<-- [start:parse]
    /** The drives in the output of {@link #LSBLK}, in its order. */
    static List<Drive> parse(String output) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) rows.add(pairs(line));
        }
        // Disks with a partition mounted somewhere else than the media folders belong to the system.
        Set<String> systemDisks = new HashSet<>();
        Map<String, Map<String, String>> byName = new HashMap<>();
        for (Map<String, String> row : rows) byName.put(name(row.get("PATH")), row);
        for (Map<String, String> row : rows) {
            String mount = row.getOrDefault("MOUNTPOINT", "");
            if (!mount.isEmpty() && !isMediaMount(mount)) systemDisks.add(diskOf(row, byName));
        }
        List<Drive> drives = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String type = row.getOrDefault("TYPE", "");
            String fs = row.getOrDefault("FSTYPE", "");
            if (!Set.of("part", "disk", "rom").contains(type) || fs.isEmpty() || NOT_FILES.contains(fs)) continue;
            String disk = diskOf(row, byName);
            if (systemDisks.contains(disk)) continue;
            Map<String, String> diskRow = byName.getOrDefault(name(disk), row);
            boolean optical = type.equals("rom");
            boolean removable = flag(row, "RM") || flag(row, "HOTPLUG") || flag(diskRow, "RM")
                    || flag(diskRow, "HOTPLUG") || "usb".equalsIgnoreCase(diskRow.getOrDefault("TRAN", ""));
            if (!optical && !removable) continue;
            String mount = row.getOrDefault("MOUNTPOINT", "");
            drives.add(new Drive(row.get("PATH"), disk, row.get("LABEL"), diskRow.get("MODEL"), fs,
                    size(row.get("SIZE")), mount.isEmpty() ? null : Path.of(mount), optical));
        }
        return drives;
    }
    // --8<-- [end:parse]

    /**
     * The places of the folder tree with the drives: a mounted medium gets its drive, and drives that are
     * not mounted follow at the end.
     */
    public static List<Place> withDrives(List<Place> places, List<Drive> drives) {
        List<Place> result = new ArrayList<>();
        Set<Drive> placed = new HashSet<>();
        for (Place place : places) {
            Drive drive = place.kind() != Place.Kind.MEDIA ? null : drives.stream()
                    .filter(d -> place.path().equals(d.mountPoint())).findFirst().orElse(null);
            if (drive != null) {
                placed.add(drive);
                result.add(new Place(place.name(), place.path(), place.kind(), drive));
            } else {
                result.add(place);
            }
        }
        for (Drive drive : drives) {
            if (placed.contains(drive)) continue;
            // Mounted, but not yet in /proc/self/mounts when the places were read: shown as mounted.
            result.add(drive.mounted() ? Place.mounted(drive) : Place.unmounted(drive));
        }
        return result;
    }

    // --8<-- [start:mount]
    /**
     * Mounts the drive and returns where.
     *
     * @throws FileOperationException with a message for the user if udisks refuses or is missing
     */
    public Path mount(Drive drive) {
        if (drive.mounted()) return drive.mountPoint();
        ProgramResult result = udisksResult("mount", List.of("udisksctl", "mount", "--block-device", drive.device()),
                drive, ACTION_TIMEOUT);
        if (!result.succeeded()) {
            String stderr = result.stderrText();
            if (needsCheck(drive, stderr)) throw new NeedsRepairException(drive, failure("mount", drive, stderr));
            throw new FileOperationException(failure("mount", drive, stderr));
        }
        // udisks chooses the folder (/media/USER/LABEL); lsblk tells it.
        return drives().stream().filter(d -> d.device().equals(drive.device()) && d.mounted())
                .map(Drive::mountPoint).findFirst()
                .orElseThrow(() -> new FileOperationException(drive.name() + " was mounted, but cannot be found."));
    }
    // --8<-- [end:mount]

    /**
     * The same medium as it is connected now. A drive that was pulled out and plugged in again can come
     * back under another device name ({@code /dev/sdc2} instead of {@code /dev/sdb2}), and its mount
     * point is gone; null if it is not connected.
     */
    public Drive current(Drive drive) {
        List<Drive> now = drives();
        for (Drive d : now) if (d.device().equals(drive.device()) && sameMedium(d, drive)) return d;
        for (Drive d : now) if (sameMedium(d, drive) && !d.label().isEmpty()) return d;
        return null;
    }

    private static boolean sameMedium(Drive a, Drive b) {
        return a.label().equals(b.label()) && a.fileSystem().equals(b.fileSystem()) && a.size() == b.size()
                && a.optical() == b.optical();
    }

    /** A failed mount that a check of the file system may cure: a known file system and a matching message. */
    static boolean needsCheck(Drive drive, String stderr) {
        if (!REPAIRABLE.contains(drive.fileSystem()) || stderr == null) return false;
        String text = stderr.toLowerCase(Locale.ROOT);
        return NEEDS_CHECK.stream().anyMatch(text::contains);
    }

    // --8<-- [start:repair]
    /**
     * Checks the file system and repairs it, through udisks (method {@code Filesystem.Repair}), so that the
     * desktop's polkit agent asks for the password if needed. The drive must not be mounted.
     *
     * @throws FileOperationException if udisks refuses or the file system could not be repaired
     */
    public void repair(Drive drive) {
        List<String> command = List.of("busctl", "--system", "--timeout=" + REPAIR_TIMEOUT.toSeconds(),
                "call", "org.freedesktop.UDisks2", objectPath(drive.device()),
                "org.freedesktop.UDisks2.Filesystem", "Repair", "a{sv}", "0");
        ProgramResult result = udisksResult("repair", command, drive, REPAIR_TIMEOUT);
        if (!result.succeeded()) throw new FileOperationException(failure("repair", drive, result.stderrText()));
        // "b true": repaired or nothing to repair; "b false": errors are left.
        if (!result.stdoutText().strip().equals("b true")) {
            throw new FileOperationException(drive.name() + " could not be repaired. Copy what you can from it"
                    + " on another computer, or format it with Disks.");
        }
    }
    // --8<-- [end:repair]

    /** udisks' D-Bus object of a block device: {@code /dev/sdb2} → {@code .../block_devices/sdb2}. */
    static String objectPath(String device) {
        String name = name(device);
        StringBuilder path = new StringBuilder("/org/freedesktop/UDisks2/block_devices/");
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) path.append(c);
            else path.append('_').append(String.format("%02x", b & 0xff));
        }
        return path.toString();
    }

    /** Unmounts the drive, so that it can be pulled out. */
    public void unmount(Drive drive) {
        if (!drive.mounted()) return;
        udisks("unmount", List.of("udisksctl", "unmount", "--block-device", drive.device()), drive);
    }

    // --8<-- [start:eject]
    /**
     * Unmounts every file system on the drive's disk, then opens the disc tray or switches the USB disk off,
     * so that it can be removed safely.
     */
    public void eject(Drive drive) {
        for (Drive other : drives()) {
            if (other.disk().equals(drive.disk()) && other.mounted()) unmount(other);
        }
        if (drive.optical()) {
            udisks("eject", List.of("eject", drive.disk()), drive);
        } else {
            udisks("power-off", List.of("udisksctl", "power-off", "--block-device", drive.disk()), drive);
        }
    }
    // --8<-- [end:eject]

    private void udisks(String action, List<String> command, Drive drive) {
        ProgramResult result = udisksResult(action, command, drive, ACTION_TIMEOUT);
        if (!result.succeeded()) throw new FileOperationException(failure(action, drive, result.stderrText()));
    }

    private ProgramResult udisksResult(String action, List<String> command, Drive drive, Duration timeout) {
        try {
            return runner.run(command, timeout);
        } catch (ProgramMissingException e) {
            throw new FileOperationException(command.getFirst() + " is missing; install the package "
                    + (switch (command.getFirst()) {
                case "eject" -> "eject";
                case "busctl" -> "systemd";
                default -> "udisks2";
            }) + ".", e);
        } catch (IOException e) {
            throw new FileOperationException("Cannot " + action + " " + drive.name() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileOperationException("Interrupted while " + drive.name() + " was being changed.", e);
        }
    }

    /** udisks error messages, turned into a sentence for the user. */
    static String failure(String action, Drive drive, String stderr) {
        String text = stderr == null ? "" : stderr.strip();
        if (text.contains("NotAuthorized")) return "You are not allowed to " + action + " " + drive.name() + ".";
        if (text.contains("busy") || text.contains("DeviceBusy")) {
            return drive.name() + " is still in use. Close the files and programs that use it, then try again.";
        }
        if (text.contains("AlreadyMounted")) return drive.name() + " is already mounted.";
        // "Error mounting /dev/sdb1: GDBus.Error:org.freedesktop.UDisks2.Error.Failed: Error mounting ...: wrong fs type"
        int colon = text.lastIndexOf(": ");
        String detail = colon >= 0 ? text.substring(colon + 2) : text;
        return "Cannot " + action + " " + drive.name() + (detail.isEmpty() ? "." : ": " + detail);
    }

    // ---- lsblk output ---------------------------------------------------------------------------

    /** {@code PATH="/dev/sdb1" LABEL="My\x20Stick"} → a map; lsblk writes unsafe characters as {@code \xHH}. */
    static Map<String, String> pairs(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        int i = 0;
        while (i < line.length()) {
            int eq = line.indexOf("=\"", i);
            if (eq < 0) break;
            String key = line.substring(i, eq).strip();
            int end = line.indexOf('"', eq + 2);
            if (end < 0) break;
            values.put(key, unescape(line.substring(eq + 2, end)));
            i = end + 1;
        }
        return values;
    }

    static String unescape(String value) {
        if (!value.contains("\\x")) return value;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 3 < value.length() && value.charAt(i + 1) == 'x'
                    && isHex(value.charAt(i + 2)) && isHex(value.charAt(i + 3))) {
                bytes.write(Integer.parseInt(value.substring(i + 2, i + 4), 16));
                i += 3;
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static String diskOf(Map<String, String> row, Map<String, Map<String, String>> byName) {
        String parent = row.getOrDefault("PKNAME", "");
        if (parent.isEmpty()) return row.get("PATH");
        Map<String, String> parentRow = byName.get(parent);
        return parentRow != null ? parentRow.get("PATH") : "/dev/" + parent;
    }

    private static String name(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean isMediaMount(String mount) {
        Path path = Path.of(mount);
        return MEDIA_FOLDERS.stream().anyMatch(m -> path.startsWith(m) && !path.equals(m));
    }

    private static boolean flag(Map<String, String> row, String key) {
        String value = row.getOrDefault(key, "").toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true");
    }

    private static long size(String text) {
        try {
            return text == null || text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
