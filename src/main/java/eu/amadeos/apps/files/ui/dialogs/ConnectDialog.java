package eu.amadeos.apps.files.ui.dialogs;

import eu.amadeos.apps.files.domain.NetworkAddress;
import eu.amadeos.apps.files.ui.main.ConnectChoice;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Connect to Server: the kind of server, its name and the share or folder, or a recent address. Shows the
 * address it makes, which can also be typed into the path bar.
 */
public final class ConnectDialog {
    private ConnectDialog() {
    }

    /** Null if cancelled or nothing usable was entered. */
    public static ConnectChoice ask(Component parent, List<NetworkAddress> recent, NetworkAddress initial) {
        JComboBox<NetworkAddress.Type> type = new JComboBox<>(NetworkAddress.Type.values());
        JTextField server = new JTextField(22);
        JTextField folder = new JTextField(22);
        JLabel address = new JLabel(" ");
        address.setFont(address.getFont().deriveFont(Font.PLAIN, address.getFont().getSize2D() - 1));
        JCheckBox keep = new JCheckBox("Keep in the folder tree under Network", true);
        if (initial != null) fill(initial, type, server, folder);

        Runnable update = () -> {
            NetworkAddress a = address(type, server, folder);
            address.setText(a == null ? " " : "Address:  " + a.plainUri());
        };
        DocumentListener changes = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                update.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update.run();
            }
        };
        server.getDocument().addDocumentListener(changes);
        folder.getDocument().addDocumentListener(changes);
        type.addActionListener(e -> update.run());
        update.run();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.gridy = -1;
        LoginDialog.row(panel, c, new JLabel("Type:"), type);
        LoginDialog.row(panel, c, new JLabel("Server:"), server);
        LoginDialog.row(panel, c, new JLabel("Share and folder:"), folder);
        c.gridx = 1;
        c.gridy++;
        panel.add(address, c);

        JList<NetworkAddress> list = new JList<>(recent.toArray(NetworkAddress[]::new));
        list.setVisibleRowCount(Math.max(3, Math.min(6, recent.size())));
        list.addListSelectionListener(e -> {
            NetworkAddress chosen = list.getSelectedValue();
            if (chosen != null) fill(chosen, type, server, folder);
        });
        if (!recent.isEmpty()) {
            JPanel box = new JPanel(new BorderLayout());
            box.setOpaque(false);
            box.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Recent servers",
                    TitledBorder.LEADING, TitledBorder.TOP));
            box.add(new JScrollPane(list));
            c.gridx = 0;
            c.gridy++;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(12, 0, 0, 0);
            panel.add(box, c);
        }
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.insets = new Insets(8, 0, 0, 0);
        panel.add(keep, c);

        Object[] options = {"Connect", "Cancel"};
        JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                options, options[0]) {
            @Override
            public void selectInitialValue() {
                server.requestFocusInWindow();
            }
        };
        server.addActionListener(e -> pane.setValue(options[0]));
        folder.addActionListener(e -> pane.setValue(options[0]));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) pane.setValue(options[0]);
            }
        });
        JDialog dialog = pane.createDialog(parent, "File Manager - Connect to Server");
        dialog.setVisible(true);
        dialog.dispose();
        if (!options[0].equals(pane.getValue())) return null;
        NetworkAddress chosen = address(type, server, folder);
        return chosen == null ? null : new ConnectChoice(chosen, keep.isSelected());
    }

    /** The address of the fields; a full address typed into Server wins over Type. */
    static NetworkAddress address(JComboBox<NetworkAddress.Type> type, JTextField server, JTextField folder) {
        String host = server.getText().strip();
        if (host.isEmpty()) return null;
        try {
            if (host.contains("://") || host.startsWith("\\\\")) {
                NetworkAddress typed = NetworkAddress.parse(host);
                String more = folder.getText().strip();
                return more.isEmpty() ? typed : typed.withPath(typed.path().isEmpty() ? more : typed.path() + "/" + more);
            }
            NetworkAddress.Type t = (NetworkAddress.Type) type.getSelectedItem();
            return NetworkAddress.parse(t.scheme() + "://" + host + "/" + folder.getText().strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void fill(NetworkAddress a, JComboBox<NetworkAddress.Type> type, JTextField server, JTextField folder) {
        NetworkAddress.Type t = NetworkAddress.Type.of(a.scheme());
        if (t != null) type.setSelectedItem(t);
        server.setText(a.host() + (a.port() != 0 ? ":" + a.port() : ""));
        folder.setText(a.path());
    }
}
