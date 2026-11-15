package eu.amadeos.apps.files.ui.panel;

import eu.amadeos.apps.files.domain.Place;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The row of places above a panel in the two-panel view: Home, the file system, the trash, every USB stick,
 * disk and disc that is plugged in, and the network drives, one flat button each. A click shows the place in
 * this panel (mounting or connecting it first); the right mouse button opens its menu. Drives that are not
 * mounted and shares that are not connected are in italics, as in the folder tree. The button of the place
 * the panel is in is shown pressed.
 */
public final class PlacesBar extends JPanel {

    /** Events of the bar; called on the EDT. */
    public interface Listener {
        void chosen(Place place);

        void popup(Place place, Component component, int x, int y);
    }

    private List<Place> places = List.of();
    private final List<JToggleButton> buttons = new ArrayList<>();
    private Listener listener;
    private Path dir;

    public PlacesBar() {
        super(new FlowLayout(FlowLayout.LEADING, 2, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPlaces(List<Place> newPlaces) {
        if (newPlaces.equals(places)) return;
        places = List.copyOf(newPlaces);
        removeAll();
        buttons.clear();
        for (Place place : places) {
            JToggleButton button = new JToggleButton(place.name(), FileIcons.of(place.kind()));
            button.setName("place:" + place.name());
            button.setFocusable(false);
            button.setMargin(new Insets(1, 4, 1, 6));
            button.setToolTipText(tooltip(place));
            boolean waiting = place.needsMount() || place.needsConnect() || place.kind() == Place.Kind.CONNECT;
            if (waiting) button.setFont(button.getFont().deriveFont(Font.ITALIC));
            button.addActionListener(e -> {
                markCurrent();
                if (listener != null) listener.chosen(place);
            });
            button.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e) && listener != null) listener.popup(place, button, e.getX(), e.getY());
                }
            });
            buttons.add(button);
            add(button);
        }
        markCurrent();
        revalidate();
        repaint();
    }

    /** The folder the panel shows: the place that holds it is pressed. */
    public void setDir(Path dir) {
        this.dir = dir;
        markCurrent();
    }

    /** The deepest place that contains the folder, so that a stick wins over the file system. */
    private void markCurrent() {
        int best = -1;
        int depth = -1;
        for (int i = 0; i < places.size(); i++) {
            Path path = places.get(i).path();
            if (dir != null && path.isAbsolute() && !places.get(i).needsMount() && dir.startsWith(path)
                    && path.getNameCount() > depth) {
                best = i;
                depth = path.getNameCount();
            }
        }
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setSelected(i == best);
    }

    private static String tooltip(Place place) {
        if (place.drive() != null) {
            String what = place.drive().optical() ? "Disc" : "Drive";
            String model = place.drive().model().isEmpty() ? "" : " (" + place.drive().model() + ")";
            return place.needsMount() ? what + model + ", not mounted: click to mount and open"
                    : what + model + " at " + place.path();
        }
        return switch (place.kind()) {
            case SHARE -> place.needsConnect() ? "Network drive, not connected: click to connect" : "Network drive";
            case CONNECT -> "Connect to a server or network drive (Ctrl+K)";
            default -> place.path().toString();
        };
    }

    /** For tests: the names on the buttons. */
    List<String> names() {
        return places.stream().map(Place::name).toList();
    }
}
