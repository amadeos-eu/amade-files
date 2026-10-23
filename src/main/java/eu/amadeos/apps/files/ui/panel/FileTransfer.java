package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.service.FileUris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorMap;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Files on the clipboard and in drag and drop, in the formats other programs understand: a Java file
 * list (which AWT offers as {@code text/uri-list}), {@code x-special/gnome-copied-files} for Cut, and
 * the plain paths as text.
 */
public final class FileTransfer {
    private static final Logger LOG = LoggerFactory.getLogger(FileTransfer.class);
    private static final String GNOME_NATIVE = "x-special/gnome-copied-files";
    static final DataFlavor GNOME_COPIED_FILES = flavor(GNOME_NATIVE + ";class=java.io.InputStream");

    // --8<-- [start:flavor-map]
    static {
        // Without this AWT would offer the flavour under an encoded name that no other program asks for.
        FlavorMap map = SystemFlavorMap.getDefaultFlavorMap();
        if (map instanceof SystemFlavorMap system && GNOME_COPIED_FILES != null) {
            system.addUnencodedNativeForFlavor(GNOME_COPIED_FILES, GNOME_NATIVE);
            system.addFlavorForUnencodedNative(GNOME_NATIVE, GNOME_COPIED_FILES);
        }
    }
    // --8<-- [end:flavor-map]

    private FileTransfer() {
    }

    /** For the clipboard and for dragging. */
    public static Transferable of(ClipboardFiles files) {
        return new Selection(files);
    }

    /** The local files in a transfer, empty if there are none. */
    public static List<Path> paths(Transferable transfer) {
        ClipboardFiles files = read(transfer);
        return files == null ? List.of() : files.paths();
    }

    /** The files and whether they were cut; null if the transfer holds no files. */
    public static ClipboardFiles read(Transferable transfer) {
        if (transfer == null) return null;
        try {
            if (GNOME_COPIED_FILES != null && transfer.isDataFlavorSupported(GNOME_COPIED_FILES)) {
                try (InputStream in = (InputStream) transfer.getTransferData(GNOME_COPIED_FILES)) {
                    ClipboardFiles files = FileUris.parseGnomeCopiedFiles(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (files != null && !files.paths().isEmpty()) return files;
                }
            }
            if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                List<?> list = (List<?>) transfer.getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = list.stream().filter(File.class::isInstance).map(f -> ((File) f).toPath()).toList();
                if (!paths.isEmpty()) return new ClipboardFiles(paths, false);
            }
            for (DataFlavor flavor : transfer.getTransferDataFlavors()) {
                if (flavor.isMimeTypeEqual("text/uri-list") && flavor.isRepresentationClassReader()) {
                    try (Reader reader = flavor.getReaderForText(transfer)) {
                        StringBuilder text = new StringBuilder();
                        char[] buffer = new char[4096];
                        for (int n; (n = reader.read(buffer)) > 0; ) text.append(buffer, 0, n);
                        List<Path> paths = FileUris.parseUriList(text.toString());
                        if (!paths.isEmpty()) return new ClipboardFiles(paths, false);
                    }
                }
            }
        } catch (UnsupportedFlavorException | IOException | RuntimeException e) {
            LOG.debug("No files in the transfer", e);
        }
        return null;
    }

    /** True if the transfer may hold files; asking for the data itself is not always possible while dragging. */
    public static boolean mayHoldFiles(DataFlavor[] flavors) {
        for (DataFlavor flavor : flavors) {
            if (flavor.equals(DataFlavor.javaFileListFlavor) || flavor.isMimeTypeEqual("text/uri-list")
                    || flavor.isMimeTypeEqual(GNOME_NATIVE)) {
                return true;
            }
        }
        return false;
    }

    public static ClipboardFiles readClipboard(Clipboard clipboard) {
        try {
            return read(clipboard.getContents(null));
        } catch (IllegalStateException e) {
            // Another program holds the clipboard just now.
            return null;
        }
    }

    private static DataFlavor flavor(String mime) {
        try {
            return new DataFlavor(mime);
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            LOG.warn("Unsupported flavour {}", mime, e);
            return null;
        }
    }

    private record Selection(ClipboardFiles files) implements Transferable, ClipboardOwner {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return GNOME_COPIED_FILES == null
                    ? new DataFlavor[]{DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor}
                    : new DataFlavor[]{DataFlavor.javaFileListFlavor, GNOME_COPIED_FILES, DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor f : getTransferDataFlavors()) if (f.equals(flavor)) return true;
            return false;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(DataFlavor.javaFileListFlavor)) return files.paths().stream().map(Path::toFile).toList();
            if (flavor.equals(GNOME_COPIED_FILES)) {
                return new ByteArrayInputStream(FileUris.gnomeCopiedFiles(files).getBytes(StandardCharsets.UTF_8));
            }
            if (flavor.equals(DataFlavor.stringFlavor)) {
                return files.paths().stream().map(Path::toString).collect(Collectors.joining("\n"));
            }
            throw new UnsupportedFlavorException(flavor);
        }

        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents) {
        }
    }
}
