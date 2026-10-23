package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.apps.files.domain.ClipboardFiles;
import eu.amadeos.apps.files.domain.DropMode;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.InputEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.TooManyListenersException;

/**
 * Dragging files out of a component and dropping files on its folders. Without a key the files move
 * on the same file system and are copied onto another one; Ctrl copies and Shift moves.
 *
 * <p>Swing does not tell a drop target which keys are held. For drags from this program the keys come
 * from the drag source; files from other programs are copied or moved by the same rule, and the other
 * program is always told "copy", so it never deletes anything itself.
 */
abstract class FileDropHandler extends TransferHandler {
    private static volatile int dragModifiers;
    private static volatile boolean localDrag;
    private static boolean sourceListening;

    private FileDropListener listener;
    private Transferable cachedTransfer;
    private List<Path> cachedSources = List.of();
    private Path hintTarget;
    private DropMode hintMode;
    private String hintText;

    /** The folder a drop at this point goes to; null if nothing can be dropped there. */
    abstract Path dropTarget(TransferSupport support);

    /** The files a drag starting now takes along; empty for no drag. */
    abstract List<Path> dragged();

    /** Called while dragging over the component, for spring-loaded folders. */
    void hovering(TransferSupport support) {
    }

    /** Called when the drag leaves the component or ends. */
    void left() {
    }

    void setListener(FileDropListener listener) {
        this.listener = listener;
    }

    /** Hides the hint when the drag leaves the component; Swing's handler only asks {@code canImport}. */
    void install(JComponent component) {
        component.setTransferHandler(this);
        listenToDragSource();
        try {
            component.getDropTarget().addDropTargetListener(new DropTargetAdapter() {
                @Override
                public void dragExit(DropTargetEvent e) {
                    clearHint();
                }

                @Override
                public void drop(DropTargetDropEvent e) {
                    clearHint();
                }
            });
        } catch (TooManyListenersException | NullPointerException e) {
            // No drop target (headless): no hint either.
        }
    }

    private static synchronized void listenToDragSource() {
        if (sourceListening || GraphicsEnvironment.isHeadless()) return;
        sourceListening = true;
        DragSource.getDefaultDragSource().addDragSourceListener(new DragSourceAdapter() {
            @Override
            public void dragEnter(DragSourceDragEvent e) {
                track(e);
            }

            @Override
            public void dragOver(DragSourceDragEvent e) {
                track(e);
            }

            @Override
            public void dropActionChanged(DragSourceDragEvent e) {
                track(e);
            }

            @Override
            public void dragDropEnd(DragSourceDropEvent e) {
                localDrag = false;
            }

            private void track(DragSourceDragEvent e) {
                localDrag = true;
                dragModifiers = e.getGestureModifiersEx();
            }
        });
    }

    static DropMode mode(TransferSupport support) {
        if (localDrag) {
            if ((dragModifiers & InputEvent.CTRL_DOWN_MASK) != 0) return DropMode.COPY;
            if ((dragModifiers & InputEvent.SHIFT_DOWN_MASK) != 0) return DropMode.MOVE;
            return DropMode.AUTO;
        }
        return support.isDrop() && support.getUserDropAction() == COPY ? DropMode.COPY : DropMode.AUTO;
    }

    // ---- export -------------------------------------------------------------------------------

    @Override
    public int getSourceActions(JComponent c) {
        return COPY_OR_MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        List<Path> paths = dragged();
        return paths.isEmpty() ? null : FileTransfer.of(new ClipboardFiles(paths, false));
    }

    // ---- import -------------------------------------------------------------------------------

    @Override
    public boolean canImport(TransferSupport support) {
        if (!support.isDrop() || listener == null || !FileTransfer.mayHoldFiles(support.getDataFlavors())) return false;
        hovering(support);
        Path target = dropTarget(support);
        if (target == null) {
            hint(support, null);
            return false;
        }
        DropMode mode = mode(support);
        String text = describe(support, target, mode);
        hint(support, text);
        if (text == null) return false;
        if (localDrag) {
            support.setDropAction(mode == DropMode.COPY || text.startsWith("Copy") ? COPY : MOVE);
        } else if ((support.getSourceDropActions() & COPY) != 0) {
            support.setDropAction(COPY);
        }
        return true;
    }

    @Override
    public boolean importData(TransferSupport support) {
        boolean possible = canImport(support);
        clearHint();
        if (!possible) return false;
        Path target = dropTarget(support);
        List<Path> sources = FileTransfer.paths(support.getTransferable());
        if (sources.isEmpty()) return false;
        DropMode mode = mode(support);
        // After the drag has ended: the operation may ask questions in dialogs.
        SwingUtilities.invokeLater(() -> listener.drop(sources, target, mode));
        return true;
    }

    private String describe(TransferSupport support, Path target, DropMode mode) {
        Transferable transfer = support.getTransferable();
        if (transfer != cachedTransfer) {
            cachedTransfer = transfer;
            cachedSources = FileTransfer.paths(transfer);
            hintTarget = null;
        }
        if (!Objects.equals(target, hintTarget) || mode != hintMode) {
            hintTarget = target;
            hintMode = mode;
            hintText = listener.describeDrop(cachedSources, target, mode);
        }
        return hintText;
    }

    private void hint(TransferSupport support, String text) {
        if (listener == null) return;
        Point point = support.getDropLocation().getDropPoint();
        listener.dropHint(support.getComponent(), point, text == null ? null : text + "\nCtrl: copy  ·  Shift: move");
    }

    private void clearHint() {
        hintTarget = null;
        cachedTransfer = null;
        left();
        if (listener != null) listener.dropHint(null, null, null);
    }
}
