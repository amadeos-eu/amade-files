package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The CDE File Manager's iconic path: one button per folder of the current path, as a title bar of
 * the panel. The bar of the active panel has the active title colours. When the path is wider than
 * the panel, the leading folders collapse into a "…" button, so the current folder stays visible.
 *
 * <p>A click on the free space of the bar, or Ctrl+L, turns it into a text field with the whole path
 * selected: Enter goes there, Escape goes back, Tab completes a folder name. The context menu copies
 * the path of the folder under the mouse.
 */
final class IconicPathBar extends JPanel {
    private static final int GAP = 2;

    private final List<JButton> buttons = new ArrayList<>();
    private final List<Path> paths = new ArrayList<>();
    private final JButton ellipsis = new JButton("…");
    private final JTextField field = new JTextField();
    private Consumer<Path> navigate = p -> {
    };
    private Consumer<String> typed = t -> {
    };
    private Consumer<Path> copy = p -> {
    };
    private UnaryOperator<String> complete = t -> t;
    /** Gives the focus back, to the list of the panel the bar belongs to. */
    private Runnable done = () -> {
    };
    private Path current;
    /** Folders shown under a name of their own (connected network shares) instead of their full path. */
    private Map<Path, String> names = Map.of();
    private boolean active;
    private boolean editing;
    private int hidden;

    IconicPathBar() {
        super(null);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));
        configure(ellipsis);
        ellipsis.addActionListener(e -> {
            if (hidden > 0) navigate.accept(paths.get(hidden - 1));
        });
        add(ellipsis);
        configureField();
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) showMenu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getSource() == IconicPathBar.this) edit();
            }
        };
        addMouseListener(mouse);
        ellipsis.addMouseListener(mouse);
        AmadeUI.style(this, (bar, theme) -> bar.applyColors(theme));
    }

    private void configureField() {
        field.setVisible(false);
        // Tab completes instead of moving the focus.
        field.setFocusTraversalKeysEnabled(false);
        field.addActionListener(e -> {
            String text = field.getText();
            stopEditing();
            typed.accept(text);
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "amade.cancel", this::stopEditing);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "amade.complete", () -> {
            field.setText(complete.apply(field.getText()));
            field.setCaretPosition(field.getText().length());
        });
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) stopEditing();
            }
        });
        add(field);
    }

    private void bindKey(KeyStroke key, String name, Runnable action) {
        field.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    void setTyped(Consumer<String> typed) {
        this.typed = typed;
    }

    void setCopy(Consumer<Path> copy) {
        this.copy = copy;
    }

    void setDone(Runnable done) {
        this.done = done;
    }

    void setComplete(UnaryOperator<String> complete) {
        this.complete = complete;
    }

    /** Ctrl+L or a click on the free space: the path as text, selected. */
    void edit() {
        if (current == null) return;
        editing = true;
        field.setText(current.toString());
        field.selectAll();
        field.setVisible(true);
        revalidate();
        doLayout();
        repaint();
        field.requestFocusInWindow();
    }

    boolean isEditing() {
        return editing;
    }

    private void stopEditing() {
        if (!editing) return;
        editing = false;
        boolean hadFocus = field.isFocusOwner();
        field.setVisible(false);
        revalidate();
        repaint();
        if (hadFocus) done.run();
    }

    private void showMenu(MouseEvent e) {
        if (current == null) return;
        Path path = current;
        int index = buttons.indexOf(e.getSource());
        if (index >= 0) path = paths.get(index);
        else if (e.getSource() == ellipsis && hidden > 0) path = paths.get(hidden - 1);
        Path target = path;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy Path");
        copyItem.addActionListener(a -> copy.accept(target));
        JMenuItem editItem = new JMenuItem("Edit Path");
        editItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        editItem.addActionListener(a -> edit());
        menu.add(copyItem);
        menu.add(editItem);
        menu.show((Component) e.getSource(), e.getX(), e.getY());
    }

    void setNavigate(Consumer<Path> navigate) {
        this.navigate = navigate;
    }

    void setPath(Path dir) {
        current = dir;
        stopEditing();
        buttons.forEach(this::remove);
        buttons.clear();
        paths.clear();
        Path named = dir == null ? null : namedRoot(dir);
        if (dir != null) {
            // Below a named folder the path starts with its name: MyCloudEX2Ultra › Public, Photos.
            for (Path p = dir; p != null; p = p.getParent()) {
                paths.addFirst(p);
                if (p.equals(named)) break;
            }
        }
        for (Path p : paths) {
            String label = p.equals(named) ? names.get(p) : p.getFileName() == null ? "/" : p.getFileName().toString();
            JButton button = new JButton(label);
            configure(button);
            button.setToolTipText(p.toString());
            button.addActionListener(e -> navigate.accept(p));
            button.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) showMenu(e);
                }
            });
            buttons.add(button);
            add(button);
        }
        applyColors(AmadeUI.theme());
        revalidate();
        repaint();
    }

    // --8<-- [start:names]
    /** Names for folders such as {@code /run/user/1000/gvfs/smb-share:server=nas,share=public}. */
    void setNames(Map<Path, String> newNames) {
        names = Map.copyOf(newNames);
        if (current != null && !editing) setPath(current);
    }

    /** The named folder that holds {@code dir} most closely, or null. */
    private Path namedRoot(Path dir) {
        Path best = null;
        for (Path root : names.keySet()) {
            if (dir.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) best = root;
        }
        return best;
    }
    // --8<-- [end:names]

    void setActive(boolean active) {
        this.active = active;
        applyColors(AmadeUI.theme());
    }

    private static void configure(JButton button) {
        button.setFocusable(false);
        button.setMargin(new Insets(0, 4, 0, 4));
    }

    private void applyColors(DesktopTheme theme) {
        setBackground(active ? theme.activeTitleBg() : theme.inactiveTitleBg());
        Color text = active ? theme.textActive() : theme.textInactive();
        ellipsis.setContentAreaFilled(false);
        ellipsis.setBorderPainted(false);
        ellipsis.setForeground(text);
        for (int i = 0; i < buttons.size(); i++) {
            JButton button = buttons.get(i);
            // The current folder stands out; its ancestors are flat.
            boolean last = i == buttons.size() - 1;
            button.setContentAreaFilled(last);
            button.setBorderPainted(last);
            button.setForeground(last ? theme.fieldText() : text);
            button.setFont(button.getFont().deriveFont(last ? Font.BOLD : Font.PLAIN));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Insets insets = getInsets();
        int width = insets.left + insets.right;
        int height = ellipsis.getPreferredSize().height;
        for (JButton button : buttons) {
            Dimension size = button.getPreferredSize();
            width += size.width + GAP;
            height = Math.max(height, size.height);
        }
        return new Dimension(width, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(ellipsis.getPreferredSize().width * 2, getPreferredSize().height);
    }

    /** Lays out from the right: the current folder always gets its place, leading folders give way. */
    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int available = getWidth() - insets.left - insets.right;
        int height = getHeight() - insets.top - insets.bottom;
        if (editing) {
            ellipsis.setVisible(false);
            buttons.forEach(b -> b.setVisible(false));
            field.setBounds(insets.left, insets.top, available, height);
            return;
        }
        int ellipsisWidth = ellipsis.getPreferredSize().width + GAP;
        int used = 0;
        int first = buttons.size();
        while (first > 0) {
            int width = buttons.get(first - 1).getPreferredSize().width + GAP;
            boolean needsEllipsis = first - 1 > 0;
            if (first < buttons.size() && used + width + (needsEllipsis ? ellipsisWidth : 0) > available) break;
            used += width;
            first--;
        }
        hidden = first;
        int x = insets.left;
        ellipsis.setVisible(hidden > 0);
        if (hidden > 0) {
            ellipsis.setBounds(x, insets.top, ellipsisWidth - GAP, height);
            ellipsis.setToolTipText(paths.get(hidden - 1).toString());
            x += ellipsisWidth;
        }
        for (int i = 0; i < buttons.size(); i++) {
            JButton button = buttons.get(i);
            button.setVisible(i >= hidden);
            if (i < hidden) continue;
            int width = Math.min(button.getPreferredSize().width, Math.max(0, getWidth() - insets.right - x));
            button.setBounds(x, insets.top, width, height);
            x += width + GAP;
        }
    }
}
