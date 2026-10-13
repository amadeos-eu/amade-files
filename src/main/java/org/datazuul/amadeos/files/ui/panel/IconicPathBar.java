package org.datazuul.amadeos.files.ui.panel;

import org.datazuul.amadeos.commons.swing.AmadeUI;
import org.datazuul.amadeos.commons.theme.DesktopTheme;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The CDE File Manager's iconic path: one button per folder of the current path, as a title bar of
 * the panel. The bar of the active panel has the active title colours. When the path is wider than
 * the panel, the leading folders collapse into a "…" button, so the current folder stays visible.
 */
final class IconicPathBar extends JPanel {
    private static final int GAP = 2;

    private final List<JButton> buttons = new ArrayList<>();
    private final List<Path> paths = new ArrayList<>();
    private final JButton ellipsis = new JButton("…");
    private Consumer<Path> navigate = p -> {
    };
    private boolean active;
    private int hidden;

    IconicPathBar() {
        super(null);
        setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));
        configure(ellipsis);
        ellipsis.addActionListener(e -> {
            if (hidden > 0) navigate.accept(paths.get(hidden - 1));
        });
        add(ellipsis);
        AmadeUI.style(this, (bar, theme) -> bar.applyColors(theme));
    }

    void setNavigate(Consumer<Path> navigate) {
        this.navigate = navigate;
    }

    void setPath(Path dir) {
        buttons.forEach(this::remove);
        buttons.clear();
        paths.clear();
        if (dir != null) {
            for (Path p = dir; p != null; p = p.getParent()) paths.addFirst(p);
        }
        for (Path p : paths) {
            JButton button = new JButton(p.getFileName() == null ? "/" : p.getFileName().toString());
            configure(button);
            button.setToolTipText(p.toString());
            button.addActionListener(e -> navigate.accept(p));
            buttons.add(button);
            add(button);
        }
        applyColors(AmadeUI.theme());
        revalidate();
        repaint();
    }

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
