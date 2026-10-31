package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.Drive;
import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.commons.process.FinishedProcess;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DriveServiceTest {
    /** A laptop started from a live USB stick (sda), with a second stick (sdb), a card reader and a DVD. */
    static final String LSBLK = """
            PATH="/dev/nvme0n1" PKNAME="" TYPE="disk" FSTYPE="" LABEL="" SIZE="512110190592" MOUNTPOINT="" RM="0" HOTPLUG="0" MODEL="Samsung SSD 980" TRAN="nvme"
            PATH="/dev/nvme0n1p1" PKNAME="nvme0n1" TYPE="part" FSTYPE="vfat" LABEL="" SIZE="536870912" MOUNTPOINT="/boot/efi" RM="0" HOTPLUG="0" MODEL="" TRAN="nvme"
            PATH="/dev/nvme0n1p2" PKNAME="nvme0n1" TYPE="part" FSTYPE="ntfs" LABEL="Windows" SIZE="200000000000" MOUNTPOINT="" RM="0" HOTPLUG="0" MODEL="" TRAN="nvme"
            PATH="/dev/sda" PKNAME="" TYPE="disk" FSTYPE="iso9660" LABEL="amadeOS" SIZE="15376318464" MOUNTPOINT="/run/live/medium" RM="1" HOTPLUG="1" MODEL="Ultra" TRAN="usb"
            PATH="/dev/sda2" PKNAME="sda" TYPE="part" FSTYPE="vfat" LABEL="EFI" SIZE="5242880" MOUNTPOINT="" RM="1" HOTPLUG="1" MODEL="" TRAN=""
            PATH="/dev/sdb" PKNAME="" TYPE="disk" FSTYPE="" LABEL="" SIZE="32015679488" MOUNTPOINT="" RM="1" HOTPLUG="1" MODEL="DataTraveler 3.0" TRAN="usb"
            PATH="/dev/sdb1" PKNAME="sdb" TYPE="part" FSTYPE="exfat" LABEL="Holiday\\x20Photos" SIZE="32014630912" MOUNTPOINT="/media/ralf/Holiday\\x20Photos" RM="1" HOTPLUG="1" MODEL="" TRAN=""
            PATH="/dev/sdc" PKNAME="" TYPE="disk" FSTYPE="" LABEL="" SIZE="0" MOUNTPOINT="" RM="1" HOTPLUG="1" MODEL="SD Card Reader" TRAN="usb"
            PATH="/dev/mmcblk0p1" PKNAME="mmcblk0" TYPE="part" FSTYPE="vfat" LABEL="" SIZE="15931539456" MOUNTPOINT="" RM="0" HOTPLUG="1" MODEL="" TRAN=""
            PATH="/dev/sdd1" PKNAME="sdd" TYPE="part" FSTYPE="swap" LABEL="" SIZE="1000" MOUNTPOINT="" RM="1" HOTPLUG="1" MODEL="" TRAN=""
            PATH="/dev/sr0" PKNAME="" TYPE="rom" FSTYPE="udf" LABEL="FILM" SIZE="4700000000" MOUNTPOINT="" RM="1" HOTPLUG="0" MODEL="DVD-RW" TRAN="sata"
            """;

    @Test
    void findsRemovableDrivesButNotTheSystemOrTheLiveStick() {
        List<Drive> drives = DriveService.parse(LSBLK);
        assertEquals(List.of("/dev/sdb1", "/dev/mmcblk0p1", "/dev/sr0"), drives.stream().map(Drive::device).toList());
        Drive stick = drives.getFirst();
        assertEquals("Holiday Photos", stick.name());
        assertEquals(Path.of("/media/ralf/Holiday Photos"), stick.mountPoint());
        assertEquals("/dev/sdb", stick.disk());
        assertEquals("DataTraveler 3.0", stick.model());
        Drive card = drives.get(1);
        assertFalse(card.mounted());
        assertEquals("/dev/mmcblk0", card.disk());
        assertEquals("16 GB Drive", card.name());
        assertTrue(drives.get(2).optical());
        assertEquals("FILM", drives.get(2).name());
    }

    @Test
    void readsQuotedPairsWithEscapes() {
        var pairs = DriveService.pairs("PATH=\"/dev/sdb1\" LABEL=\"Fotos\\x20M\\xc3\\xbcnchen\" MOUNTPOINT=\"\"");
        assertEquals("/dev/sdb1", pairs.get("PATH"));
        assertEquals("Fotos München", pairs.get("LABEL"));
        assertEquals("", pairs.get("MOUNTPOINT"));
    }

    @Test
    void mediaPlacesGetTheirDriveAndUnmountedDrivesFollow() {
        List<Drive> drives = DriveService.parse(LSBLK);
        Path stickMount = Path.of("/media/ralf/Holiday Photos");
        List<Place> places = DriveService.withDrives(List.of(
                new Place("Home", Path.of("/home/ralf"), Place.Kind.HOME),
                new Place("Holiday Photos", stickMount, Place.Kind.MEDIA)), drives);
        assertEquals(4, places.size());
        assertEquals("/dev/sdb1", places.get(1).drive().device());
        assertFalse(places.get(1).needsMount());
        assertTrue(places.get(2).needsMount());
        assertEquals(Path.of("/dev/mmcblk0p1"), places.get(2).path());
        assertEquals("FILM", places.get(3).name());
    }

    @Test
    void mountAsksUdisksAndReadsTheFolderFromLsblk() {
        List<List<String>> commands = new ArrayList<>();
        String after = LSBLK.replace("""
                FSTYPE="vfat" LABEL="" SIZE="15931539456" MOUNTPOINT=""\
                """, """
                FSTYPE="vfat" LABEL="" SIZE="15931539456" MOUNTPOINT="/media/ralf/1234-ABCD"\
                """);
        DriveService service = new DriveService(command -> {
            commands.add(command);
            if (command.getFirst().equals("udisksctl")) return FinishedProcess.printing("Mounted /dev/mmcblk0p1 at /media/ralf/1234-ABCD");
            return FinishedProcess.printing(after);
        });
        Drive card = DriveService.parse(LSBLK).get(1);
        assertEquals(Path.of("/media/ralf/1234-ABCD"), service.mount(card));
        assertEquals(List.of("udisksctl", "mount", "--block-device", "/dev/mmcblk0p1"), commands.getFirst());
    }

    @Test
    void ejectUnmountsTheDiskAndSwitchesItOff() {
        List<List<String>> commands = new ArrayList<>();
        DriveService service = new DriveService(command -> {
            commands.add(command);
            return FinishedProcess.printing(command.getFirst().equals("lsblk") ? LSBLK : "");
        });
        service.eject(DriveService.parse(LSBLK).getFirst());
        assertEquals(List.of(
                DriveService.LSBLK,
                List.of("udisksctl", "unmount", "--block-device", "/dev/sdb1"),
                List.of("udisksctl", "power-off", "--block-device", "/dev/sdb")), commands);
    }

    @Test
    void discsComeOutOfTheTray() {
        List<List<String>> commands = new ArrayList<>();
        DriveService service = new DriveService(command -> {
            commands.add(command);
            return FinishedProcess.printing(command.getFirst().equals("lsblk") ? LSBLK : "");
        });
        service.eject(DriveService.parse(LSBLK).get(2));
        assertEquals(List.of("eject", "/dev/sr0"), commands.getLast());
    }

    @Test
    void busyDrivesGetASentence() {
        DriveService service = new DriveService(command -> FinishedProcess.failing(1,
                "Error unmounting /dev/sdb1: GDBus.Error:org.freedesktop.UDisks2.Error.DeviceBusy: target is busy"));
        Drive stick = DriveService.parse(LSBLK).getFirst();
        FileOperationException e = assertThrows(FileOperationException.class, () -> service.unmount(stick));
        assertEquals("Holiday Photos is still in use. Close the files and programs that use it, then try again.", e.getMessage());
    }

    @Test
    void otherFailuresKeepTheLastPartOfTheMessage() {
        Drive card = DriveService.parse(LSBLK).get(1);
        assertEquals("Cannot mount 16 GB Drive: wrong fs type",
                DriveService.failure("mount", card, "Error mounting /dev/mmcblk0p1: GDBus.Error:org.freedesktop.UDisks2.Error.Failed: wrong fs type\n"));
        assertEquals("You are not allowed to mount 16 GB Drive.",
                DriveService.failure("mount", card, "GDBus.Error:org.freedesktop.UDisks2.Error.NotAuthorizedCanObtain: Not authorized"));
    }

    @Test
    void noLsblkMeansNoDrives() {
        DriveService service = new DriveService(command -> {
            throw new eu.amadeos.commons.process.ProgramMissingException("lsblk", new java.io.IOException("missing"));
        });
        assertEquals(List.of(), service.drives());
    }
}
