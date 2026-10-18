package eu.amadeos.apps.files.ui.dialogs;

import eu.amadeos.apps.files.domain.Progress;
import eu.amadeos.apps.files.service.Formats;
import eu.amadeos.apps.files.ui.main.ProgressView;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Progress of a copy, move or delete, with Cancel. Appears only if the operation takes longer than
 * {@link #DELAY_MILLIS}, so quick operations do not flash a window.
 */
public final class ProgressDialog extends JDialog implements ProgressView {
    static final int DELAY_MILLIS = 400;

    private final JLabel file = new JLabel(" ");
    private final JLabel counts = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final Timer showTimer;
    private boolean closed;

    public ProgressDialog(Frame owner, String title, Runnable cancel) {
        super(owner, "File Manager - " + title, false);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel labels = new JPanel(new GridLayout(0, 1, 0, 4));
        labels.setOpaque(false);
        labels.add(new JLabel(title + " …"));
        labels.add(file);
        content.add(labels, BorderLayout.NORTH);
        bar.setPreferredSize(new Dimension(420, 18));
        content.add(bar, BorderLayout.CENTER);
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            cancelButton.setEnabled(false);
            file.setText("Cancelling …");
            cancel.run();
        });
        JPanel south = new JPanel(new BorderLayout(12, 0));
        south.setOpaque(false);
        south.add(counts, BorderLayout.CENTER);
        south.add(cancelButton, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelButton.doClick();
            }
        });
        pack();
        setLocationRelativeTo(owner);
        showTimer = new Timer(DELAY_MILLIS, e -> {
            if (!closed) setVisible(true);
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    @Override
    public void update(Progress progress) {
        if (closed) return;
        bar.setValue((int) Math.round(progress.fraction() * 1000));
        if (progress.current() != null) file.setText(progress.current().getFileName() == null
                ? progress.current().toString() : progress.current().getFileName().toString());
        String text = progress.filesDone() + " of " + progress.filesTotal();
        if (progress.bytesTotal() > 0) {
            text += " · " + Formats.size(progress.bytesDone()) + " of " + Formats.size(progress.bytesTotal());
        }
        counts.setText(text);
    }

    @Override
    public void close() {
        closed = true;
        showTimer.stop();
        dispose();
    }
}
