package org.datazuul.amadeos.files.ui.panel;

import org.datazuul.amadeos.files.domain.FileEntry;

import javax.swing.*;
import java.awt.*;

/**
 * Small vector icons for the panel lines, drawn in the Motif manner with a dark outline.
 */
final class FileIcons {
    static final int SIZE = 16;

    private static final Icon FOLDER = new Painted((g, link) -> folder(g), false);
    private static final Icon FOLDER_LINK = new Painted((g, link) -> folder(g), true);
    private static final Icon PARENT = new Painted((g, link) -> parent(g), false);
    private static final Icon FILE = new Painted((g, link) -> page(g, new Color(0xF4F4EE)), false);
    private static final Icon FILE_LINK = new Painted((g, link) -> page(g, new Color(0xF4F4EE)), true);
    private static final Icon PROGRAM = new Painted((g, link) -> program(g), false);
    private static final Icon OTHER = new Painted((g, link) -> page(g, new Color(0xB8B8B8)), false);

    private FileIcons() {
    }

    static Icon of(FileEntry entry) {
        return switch (entry.kind()) {
            case PARENT -> PARENT;
            case DIRECTORY -> entry.symlink() ? FOLDER_LINK : FOLDER;
            case FILE -> entry.executable() ? PROGRAM : entry.symlink() ? FILE_LINK : FILE;
            case OTHER -> OTHER;
        };
    }

    private interface Painter {
        void paint(Graphics2D g, boolean link);
    }

    private record Painted(Painter painter, boolean link) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            painter.paint(g2, link);
            if (link) linkArrow(g2);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    private static void folder(Graphics2D g) {
        g.setColor(new Color(0xD9B45A));
        g.fillRect(1, 3, 6, 3);
        g.fillRect(1, 5, 14, 9);
        g.setColor(new Color(0xEFD28A));
        g.fillRect(2, 6, 12, 2);
        g.setColor(new Color(0x6B5320));
        g.drawRect(1, 5, 14, 9);
        g.drawLine(1, 3, 6, 3);
        g.drawLine(1, 3, 1, 5);
        g.drawLine(6, 3, 7, 5);
    }

    private static void parent(Graphics2D g) {
        folder(g);
        g.setColor(new Color(0x30425A));
        g.fillPolygon(new int[]{8, 4, 12}, new int[]{6, 10, 10}, 3);
        g.fillRect(7, 10, 3, 3);
    }

    private static void page(Graphics2D g, Color paper) {
        g.setColor(paper);
        g.fillPolygon(new int[]{3, 10, 13, 13, 3}, new int[]{1, 1, 4, 15, 15}, 5);
        g.setColor(new Color(0x505050));
        g.drawPolygon(new int[]{3, 10, 13, 13, 3}, new int[]{1, 1, 4, 15, 15}, 5);
        g.drawLine(10, 1, 10, 4);
        g.drawLine(10, 4, 13, 4);
        g.setColor(new Color(0xA0A0A0));
        for (int y = 7; y <= 12; y += 2) g.drawLine(5, y, 11, y);
    }

    private static void program(Graphics2D g) {
        g.setColor(new Color(0xE8E8E0));
        g.fillRect(1, 2, 14, 12);
        g.setColor(new Color(0x4A6B82));
        g.fillRect(1, 2, 14, 3);
        g.setColor(new Color(0x404040));
        g.drawRect(1, 2, 14, 12);
        g.setColor(new Color(0x2E7D32));
        g.drawLine(4, 8, 6, 10);
        g.drawLine(6, 10, 4, 12);
        g.drawLine(8, 12, 11, 12);
    }

    private static void linkArrow(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 9, 7, 7);
        g.setColor(new Color(0x303030));
        g.drawRect(0, 9, 6, 6);
        g.drawLine(2, 13, 4, 11);
        g.drawLine(3, 11, 4, 11);
        g.drawLine(4, 11, 4, 12);
    }
}
