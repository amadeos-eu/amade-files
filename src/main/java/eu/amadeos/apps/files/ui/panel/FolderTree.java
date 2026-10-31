package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.apps.files.domain.Place;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The folder tree of the tree view: home, trash, the file system and mounted media at the top, the
 * sub-folders below. Folders are read when they are opened for the first time, through the listener,
 * so a slow or hanging file system never blocks the window.
 *
 * <p>Choosing a folder shows it on the contents side. Files can be dropped on any folder; a closed
 * folder opens when the mouse rests on it while dragging.
 */
public final class FolderTree extends JPanel {

    /** Events of the tree; called on the EDT. */
    public interface Listener {
        /** The user chose a folder. */
        void folderSelected(Path dir);

        /** The tree got the keyboard focus, with this folder chosen (or null). */
        void focused(Path dir);

        /** The sub-folders of a folder are needed; answer with {@link #showFolders}. */
        void expand(Path dir);

        /** Right mouse button on a folder; {@code place} is null for an ordinary folder. */
        void popup(Path dir, Place place, Component component, int x, int y);

        /** Tab: on to the contents side. */
        void switchToContents();
    }

    private static final String LOADING = "…";
    private static final int SPRING_DELAY_MS = 800;

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final DropHandler dropHandler = new DropHandler();
    private Listener listener;
    private Path pendingReveal;
    private boolean revealing;
    /** The right mouse button chooses a folder for its menu; a drive is only mounted from the menu then. */
    private boolean choosingForMenu;

    // --8<-- [start:lazy-node]
    /** One folder in the tree. */
    private static final class FolderNode extends DefaultMutableTreeNode {
        final Path path;
        final Place place;
        boolean loaded;
        boolean loading;

        FolderNode(Path path, Place place) {
            super(place != null ? place.name() : String.valueOf(path.getFileName()));
            this.path = path;
            this.place = place;
            // Until it is read, a folder shows that it may have sub-folders.
            if (place == null || place.kind() != Place.Kind.TRASH && place.kind() != Place.Kind.CONNECT) {
                add(new DefaultMutableTreeNode(LOADING));
            } else {
                loaded = true;
            }
        }
    }
    // --8<-- [end:lazy-node]

    public FolderTree() {
        super(new BorderLayout());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(Math.max(FileIcons.SIZE + 4, tree.getFontMetrics(tree.getFont()).getHeight() + 3));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.setToggleClickCount(0);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                if (e.getPath().getLastPathComponent() instanceof FolderNode node) load(node);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            FolderNode node = selectedNode();
            if (node == null || listener == null) return;
            if (choosingForMenu && node.place != null && (node.place.needsMount() || node.place.isVirtual())) {
                listener.focused(node.path);
                return;
            }
            // Network and a server are no folders: choosing one opens it in the tree.
            if (!revealing && node.place != null && (node.place.kind() == Place.Kind.NETWORK
                    || node.place.kind() == Place.Kind.SERVER)) {
                tree.expandPath(new TreePath(node.getPath()));
            }
            if (!revealing) listener.folderSelected(node.path);
            else if (tree.isFocusOwner()) listener.focused(node.path);
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                tree.repaint();
                FolderNode node = selectedNode();
                if (listener != null) listener.focused(node == null ? null : node.path);
            }

            @Override
            public void focusLost(FocusEvent e) {
                tree.repaint();
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;
                int row = tree.getRowForLocation(e.getX(), e.getY());
                if (row < 0) return;
                tree.requestFocusInWindow();
                choosingForMenu = true;
                try {
                    tree.setSelectionRow(row);
                } finally {
                    choosingForMenu = false;
                }
                FolderNode node = selectedNode();
                if (node != null && listener != null) listener.popup(node.path, node.place, tree, e.getX(), e.getY());
            }
        });
        installKeys();
        tree.setDragEnabled(true);
        tree.setDropMode(javax.swing.DropMode.ON);
        dropHandler.install(tree);

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(AmadeUI.sunken());
        AmadeUI.style(tree, (t, theme) -> t.setBackground(theme.fieldBackground()));
        AmadeUI.style(scroll.getViewport(), (viewport, theme) -> viewport.setBackground(theme.fieldBackground()));
        add(scroll, BorderLayout.CENTER);
    }

    private void installKeys() {
        InputMap keys = tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        InputMap focused = tree.getInputMap(JComponent.WHEN_FOCUSED);
        // Rename, Cut, Copy and Paste are the window's, for folders.
        for (KeyStroke key : List.of(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK))) {
            keys.put(key, "none");
            focused.put(key, "none");
        }
        KeyStroke tab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        focused.put(tab, "amade.contents");
        focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "amade.contents");
        tree.setFocusTraversalKeysEnabled(false);
        tree.getActionMap().put("amade.contents", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (listener != null) listener.switchToContents();
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setDropListener(FileDropListener dropListener) {
        dropHandler.setListener(dropListener);
    }

    public void focusTree() {
        tree.requestFocusInWindow();
    }

    /** The top level; places that stay keep their opened folders. */
    public void setPlaces(List<Place> places) {
        Map<Place, FolderNode> existing = new HashMap<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            FolderNode node = (FolderNode) root.getChildAt(i);
            existing.put(node.place, node);
        }
        for (FolderNode node : existing.values()) {
            if (!places.contains(node.place)) model.removeNodeFromParent(node);
        }
        boolean first = root.getChildCount() == 0;
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            FolderNode node = existing.get(place);
            if (node == null) model.insertNodeInto(new FolderNode(place.path(), place), root, i);
        }
        if (first) {
            model.reload();
            // Home is open from the start, as in other file managers.
            for (int i = 0; i < root.getChildCount(); i++) {
                FolderNode node = (FolderNode) root.getChildAt(i);
                if (node.place.kind() == Place.Kind.HOME) tree.expandPath(new TreePath(node.getPath()));
            }
        }
        if (pendingReveal != null) reveal(pendingReveal);
    }

    /** The sub-folders of a folder, read for {@link Listener#expand}. */
    public void showFolders(Path dir, List<Path> folders) {
        for (FolderNode node : nodes()) {
            if (!node.path.equals(dir) || !(node.loading || node.loaded)) continue;
            node.loading = false;
            node.loaded = true;
            update(node, folders);
        }
        if (pendingReveal != null) reveal(pendingReveal);
    }

    // --8<-- [start:show-places]
    /**
     * The entries below a network entry (the servers below Network, the shares below a server); entries that
     * stay keep their opened folders. A share that gets connected is a new entry, with its folder as path.
     *
     * @param complete false while the list may grow (the shares of a server that was not asked yet): opening the
     *                 entry then still asks the listener
     */
    public void showPlaces(Path parent, List<Place> places, boolean complete) {
        for (FolderNode node : nodes()) {
            if (!node.path.equals(parent) || node.place == null) continue;
            node.loading = false;
            node.loaded = complete;
            Map<Place, FolderNode> existing = new HashMap<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof FolderNode child && child.place != null) existing.put(child.place, child);
            }
            List<FolderNode> wanted = new ArrayList<>();
            for (Place place : places) wanted.add(existing.getOrDefault(place, new FolderNode(place.path(), place)));
            arrange(node, wanted);
            // Still something to read: the handle stays.
            if (!complete && node.getChildCount() == 0) model.insertNodeInto(new DefaultMutableTreeNode(LOADING), node, 0);
        }
        if (pendingReveal != null) reveal(pendingReveal);
    }
    // --8<-- [end:show-places]

    // --8<-- [start:update]
    private void update(FolderNode node, List<Path> folders) {
        Map<Path, FolderNode> existing = new HashMap<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof FolderNode child) existing.put(child.path, child);
        }
        List<FolderNode> wanted = new ArrayList<>();
        for (Path folder : folders) wanted.add(existing.getOrDefault(folder, new FolderNode(folder, null)));
        arrange(node, wanted);
    }
    // --8<-- [end:update]

    /** Makes {@code wanted} the children of {@code node}, in this order. */
    private void arrange(FolderNode node, List<FolderNode> wanted) {
        // New folders first and the old ones after: a folder that has no children for a moment would close.
        for (FolderNode child : wanted) {
            if (child.getParent() == null) model.insertNodeInto(child, node, node.getChildCount());
        }
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            if (!wanted.contains(node.getChildAt(i))) model.removeNodeFromParent((DefaultMutableTreeNode) node.getChildAt(i));
        }
        for (int i = 0; i < wanted.size(); i++) {
            FolderNode child = wanted.get(i);
            if (node.getIndex(child) != i) {
                model.removeNodeFromParent(child);
                model.insertNodeInto(child, node, i);
            }
        }
    }

    /** Reads every folder again that was read before, for Refresh, Show Hidden Files and after changes. */
    public void refresh() {
        for (FolderNode node : nodes()) {
            // The network entries are read again by the presenter, without asking for passwords.
            if (node.loaded && (node.place == null || node.place.kind() != Place.Kind.TRASH && !node.place.isVirtual())
                    && listener != null) {
                node.loading = true;
                listener.expand(node.path);
            }
        }
    }

    /**
     * Chooses the folder in the tree and opens the folders above it, reading them as needed; without
     * telling the listener, as the folder is already shown.
     */
    public void reveal(Path dir) {
        pendingReveal = null;
        FolderNode node = placeFor(dir);
        if (node == null) {
            select(null);
            return;
        }
        while (!node.path.equals(dir)) {
            if (!node.loaded) {
                pendingReveal = dir;
                load(node);
                return;
            }
            Path next = childPath(node.path, dir);
            FolderNode child = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof FolderNode c && c.path.equals(next)) child = c;
            }
            if (child == null) {
                // A hidden folder, or one that is gone: the nearest folder the tree has.
                select(node);
                return;
            }
            node = child;
        }
        select(node);
    }

    /** The folder below {@code parent} on the way to {@code dir}. */
    private static Path childPath(Path parent, Path dir) {
        return parent.resolve(dir.getName(parent.getNameCount()).toString());
    }

    private void select(FolderNode node) {
        revealing = true;
        try {
            if (node == null) {
                tree.clearSelection();
                return;
            }
            TreePath path = new TreePath(node.getPath());
            if (path.getParentPath() != null && path.getParentPath().getPathCount() > 1) tree.expandPath(path.getParentPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        } finally {
            revealing = false;
        }
    }

    /**
     * The place that holds the folder most closely: the home folder before the file system, a connected network
     * share (below Network) before both.
     */
    private FolderNode placeFor(Path dir) {
        if (!dir.isAbsolute()) return null;
        FolderNode best = null;
        for (FolderNode node : nodes()) {
            if (node.place == null || node.place.isVirtual()) continue;
            boolean trash = node.place.kind() == Place.Kind.TRASH;
            if (trash ? dir.equals(node.path) : dir.startsWith(node.path)) {
                if (best == null || node.path.getNameCount() > best.path.getNameCount()) best = node;
            }
        }
        return best;
    }

    private void load(FolderNode node) {
        if (node.loaded || node.loading || listener == null) return;
        node.loading = true;
        listener.expand(node.path);
    }

    private FolderNode selectedNode() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof FolderNode node ? node : null;
    }

    private List<FolderNode> nodes() {
        List<FolderNode> nodes = new ArrayList<>();
        Enumeration<?> all = root.breadthFirstEnumeration();
        for (Object node : Collections.list(all)) if (node instanceof FolderNode folder) nodes.add(folder);
        return nodes;
    }

    /** Folder icons, the places in bold, the drop target highlighted. */
    private final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, false);
            DesktopTheme theme = AmadeUI.theme();
            JTree.DropLocation drop = t.getDropLocation();
            boolean dropTarget = drop != null && drop.getChildIndex() == -1 && t.getRowForPath(drop.getPath()) == row;
            // While dragging only the drop target is highlighted; without the focus the chosen folder is grey.
            boolean highlighted = dropTarget || (selected && drop == null);
            boolean strong = dropTarget || t == KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
            setOpaque(true);
            setBackground(highlighted ? (strong ? theme.selectionBackground() : theme.sunkenBg()) : theme.fieldBackground());
            setForeground(highlighted && strong ? theme.selectionText() : theme.fieldText());
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            if (value instanceof FolderNode node) {
                setIcon(node.place != null ? FileIcons.of(node.place.kind()) : FileIcons.folder());
                setText(node.place != null && node.place.kind() == Place.Kind.HOME
                        ? "Home (" + node.path.getFileName() + ")" : node.toString());
                setFont(t.getFont().deriveFont(style(node.place)));
            } else {
                setIcon(null);
                setFont(t.getFont());
            }
            return this;
        }
    }

    /**
     * The places at the top in bold; a drive that is plugged in but not mounted, a share that is not connected
     * and Connect to Server in italics until they are chosen; servers and shares below Network plain.
     */
    private static int style(Place place) {
        if (place == null) return Font.PLAIN;
        return switch (place.kind()) {
            case SERVER -> Font.PLAIN;
            case SHARE -> place.needsConnect() ? Font.ITALIC : Font.PLAIN;
            case CONNECT -> Font.ITALIC;
            default -> place.needsMount() ? Font.BOLD | Font.ITALIC : Font.BOLD;
        };
    }

    /** Drags a folder out of the tree; drops on the folder under the mouse. */
    private final class DropHandler extends FileDropHandler {
        private int hoverRow = -1;
        private final Timer spring = new Timer(SPRING_DELAY_MS, e -> {
            if (hoverRow >= 0 && tree.isCollapsed(hoverRow)) tree.expandRow(hoverRow);
        });

        DropHandler() {
            spring.setRepeats(false);
        }

        @Override
        List<Path> dragged() {
            FolderNode node = selectedNode();
            return node == null || node.place != null ? List.of() : List.of(node.path);
        }

        @Override
        Path dropTarget(TransferSupport support) {
            if (!(support.getDropLocation() instanceof JTree.DropLocation location) || location.getPath() == null) return null;
            // Nothing can be dropped on a server or a share that is not connected.
            return location.getPath().getLastPathComponent() instanceof FolderNode node && node.path.isAbsolute()
                    ? node.path : null;
        }

        @Override
        void hovering(TransferSupport support) {
            int row = support.getDropLocation() instanceof JTree.DropLocation location && location.getPath() != null
                    ? tree.getRowForPath(location.getPath()) : -1;
            if (row != hoverRow) {
                hoverRow = row;
                spring.restart();
            }
        }

        @Override
        void left() {
            hoverRow = -1;
            spring.stop();
        }
    }
}
