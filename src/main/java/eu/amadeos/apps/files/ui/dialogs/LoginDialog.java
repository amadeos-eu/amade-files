package eu.amadeos.apps.files.ui.dialogs;

import eu.amadeos.apps.files.domain.Credentials;
import eu.amadeos.apps.files.ui.main.LoginChoice;
import eu.amadeos.apps.files.ui.main.LoginRequest;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * Asks for the user name, workgroup and password of a server or share, or lets the user connect as a guest.
 */
public final class LoginDialog {
    private LoginDialog() {
    }

    // --8<-- [start:ask]
    /** Null if cancelled. */
    public static LoginChoice ask(Component parent, LoginRequest request) {
        JRadioButton registered = new JRadioButton("Registered user", !request.guest());
        JRadioButton guest = new JRadioButton("Guest (no password)", request.guest());
        ButtonGroup group = new ButtonGroup();
        group.add(registered);
        group.add(guest);
        JTextField user = new JTextField(request.user(), 20);
        JTextField domain = new JTextField(request.domain().isEmpty() ? "WORKGROUP" : request.domain(), 20);
        JPasswordField password = new JPasswordField(20);
        JCheckBox remember = new JCheckBox(request.keyring() ? "Remember password in the keyring"
                : "Remember password while Files runs", request.remember());
        JCheckBox autoConnect = new JCheckBox("Connect again at login",
                request.autoConnect() != null && request.autoConnect());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, 10, 0);
        panel.add(new JLabel("<html><div style='width:320px'>" + escape(request.message()) + "</div></html>"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 4, 0);
        JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        kinds.setOpaque(false);
        kinds.add(registered);
        kinds.add(Box.createHorizontalStrut(16));
        kinds.add(guest);
        panel.add(kinds, c);
        c.gridwidth = 1;
        JLabel domainLabel = new JLabel("Workgroup or domain:");
        row(panel, c, new JLabel("User name:"), user);
        if (request.smb()) row(panel, c, domainLabel, domain);
        row(panel, c, new JLabel("Password:"), password);
        c.gridx = 1;
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        panel.add(remember, c);
        if (request.autoConnect() != null) {
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            panel.add(autoConnect, c);
        }
        Runnable update = () -> {
            boolean on = registered.isSelected();
            for (JComponent field : new JComponent[]{user, domain, password, remember}) field.setEnabled(on);
        };
        registered.addActionListener(e -> update.run());
        guest.addActionListener(e -> update.run());
        update.run();

        Object[] options = {"Connect", "Cancel"};
        JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                options, options[0]) {
            @Override
            public void selectInitialValue() {
                if (guest.isSelected()) return;
                (user.getText().isBlank() ? user : password).requestFocusInWindow();
            }
        };
        password.addActionListener(e -> pane.setValue(options[0]));
        JDialog dialog = pane.createDialog(parent, "File Manager - " + request.title());
        dialog.setVisible(true);
        dialog.dispose();
        if (!options[0].equals(pane.getValue())) return null;
        char[] secret = password.getPassword();
        String text = new String(secret);
        Arrays.fill(secret, '\0');
        Credentials credentials = guest.isSelected() ? Credentials.GUEST
                : Credentials.of(user.getText(), request.smb() ? domain.getText() : "", text);
        if (!credentials.guest() && credentials.user().isEmpty()) return null;
        return new LoginChoice(credentials, !credentials.guest() && remember.isSelected(),
                request.autoConnect() != null && autoConnect.isSelected());
    }
    // --8<-- [end:ask]

    static void row(JPanel panel, GridBagConstraints c, JLabel label, JComponent field) {
        c.gridy++;
        c.gridx = 0;
        c.insets = new Insets(3, 0, 3, 8);
        c.fill = GridBagConstraints.NONE;
        label.setLabelFor(field);
        panel.add(label, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
        c.fill = GridBagConstraints.NONE;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
