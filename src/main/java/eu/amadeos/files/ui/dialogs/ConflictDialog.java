package eu.amadeos.files.ui.dialogs;

import eu.amadeos.files.domain.FileEntry;
import eu.amadeos.files.domain.Resolution;
import eu.amadeos.files.service.Formats;
import eu.amadeos.files.ui.main.ConflictChoice;

import javax.swing.*;
import java.awt.*;

/**
 * Asks what to do when a file already exists in the target folder, showing both versions.
 */
public final class ConflictDialog {
    private static final String[] OPTIONS = {"Overwrite", "Overwrite All", "Skip", "Skip All", "Keep Both", "Cancel"};
    private static final ConflictChoice[] CHOICES = {
            new ConflictChoice(Resolution.OVERWRITE, false),
            new ConflictChoice(Resolution.OVERWRITE, true),
            new ConflictChoice(Resolution.SKIP, false),
            new ConflictChoice(Resolution.SKIP, true),
            new ConflictChoice(Resolution.KEEP_BOTH, false),
            ConflictChoice.CANCEL};

    private ConflictDialog() {
    }

    public static ConflictChoice ask(Component parent, FileEntry source, FileEntry target) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(new JLabel("<html><b>" + escape(target.name()) + "</b> already exists in "
                + escape(folder(target)) + ".</html>"), c);
        c.gridwidth = 1;
        c.insets = new Insets(2, 0, 2, 12);
        row(panel, c, 1, "Existing:", target);
        row(panel, c, 2, "New:", source);
        int answer = JOptionPane.showOptionDialog(parent, panel, "File Manager - File Exists",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, OPTIONS, OPTIONS[2]);
        return answer >= 0 && answer < CHOICES.length ? CHOICES[answer] : ConflictChoice.CANCEL;
    }

    private static void row(JPanel panel, GridBagConstraints c, int y, String label, FileEntry entry) {
        c.gridy = y;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        String size = entry.isDirectory() ? "folder" : Formats.size(entry.size());
        panel.add(new JLabel(size + ", modified " + Formats.date(entry.modified())), c);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String folder(FileEntry entry) {
        return entry.path().getParent() == null ? "/" : entry.path().getParent().toString();
    }
}
