package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.commons.swing.ToolIcon;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** The pictures of the Back, Forward, Up and Home buttons in front of the path bar. */
public enum NavigationGlyph implements ToolIcon.Glyph {
    BACK, FORWARD, UP, HOME;

    @Override
    public void paint(ToolIcon.Pen pen) {
        switch (this) {
            case BACK -> {
                pen.mirror();
                arrow(pen);
            }
            case FORWARD -> arrow(pen);
            case UP -> {
                pen.bold(2f);
                pen.line(8, 13.5, 8, 7);
                pen.thin();
                pen.triangle(3.5, 8, 12.5, 8, 8, 2.5);
            }
            case HOME -> {
                Path2D roof = new Path2D.Double();
                roof.moveTo(1.5, 8.5);
                roof.lineTo(8, 2);
                roof.lineTo(14.5, 8.5);
                pen.fillAndDraw(new Rectangle2D.Double(3.5, 7.5, 9, 7), pen.paper());
                pen.fill(new Rectangle2D.Double(7, 10, 2.5, 4.5), pen.light());
                pen.draw(new Rectangle2D.Double(6.5, 10.5, 3, 4));
                pen.bold(1.6f);
                pen.draw(roof);
                pen.thin();
            }
        }
    }

    private static void arrow(ToolIcon.Pen pen) {
        pen.bold(2f);
        pen.line(2.5, 8, 9, 8);
        pen.thin();
        pen.triangle(8, 3.5, 8, 12.5, 13.5, 8);
    }
}
