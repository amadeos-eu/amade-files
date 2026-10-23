package eu.amadeos.apps.files.ui.main;

import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.*;
import java.awt.*;

/**
 * The note next to the mouse while dragging files: what dropping does here, and the keys that change
 * it. It lies on the window's drag layer, below and to the right of the mouse, so it is never the
 * component under the mouse.
 */
final class DropHint extends JComponent {
    private static final int OFFSET_X = 18;
    private static final int OFFSET_Y = 22;
    private static final int PAD = 6;
    private String[] lines = new String[0];

    DropHint() {
        setVisible(false);
    }

    /** @param text the action, and after a line break the keys */
    void show(Point mouse, String text, Dimension area) {
        lines = text.split("\n");
        Dimension size = measure();
        int x = Math.min(mouse.x + OFFSET_X, Math.max(0, area.width - size.width - 2));
        int y = mouse.y + OFFSET_Y + size.height > area.height ? mouse.y - OFFSET_Y - size.height : mouse.y + OFFSET_Y;
        setBounds(x, Math.max(0, y), size.width, size.height);
        setVisible(true);
        repaint();
    }

    private Dimension measure() {
        Font bold = font().deriveFont(Font.BOLD);
        FontMetrics fb = getFontMetrics(bold);
        FontMetrics fp = getFontMetrics(font());
        int width = 0;
        for (int i = 0; i < lines.length; i++) width = Math.max(width, (i == 0 ? fb : fp).stringWidth(lines[i]));
        int height = fb.getHeight() + (lines.length - 1) * fp.getHeight();
        return new Dimension(width + 2 * PAD + 2, height + PAD + 2);
    }

    private Font font() {
        Font font = UIManager.getFont("ToolTip.font");
        return font != null ? font : UIManager.getFont("Label.font");
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color background = UIManager.getColor("ToolTip.background");
        g.setColor(background != null ? background : new Color(0xFFF8D0));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(AmadeUI.theme().fieldText());
        g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        Font bold = font().deriveFont(Font.BOLD);
        int y = PAD / 2 + 1;
        for (int i = 0; i < lines.length; i++) {
            Font font = i == 0 ? bold : font();
            FontMetrics fm = g.getFontMetrics(font);
            g.setFont(font);
            Color text = UIManager.getColor("ToolTip.foreground");
            g.setColor(text != null ? text : Color.BLACK);
            g.drawString(lines[i], PAD + 1, y + fm.getAscent());
            y += fm.getHeight();
        }
        g.dispose();
    }
}
