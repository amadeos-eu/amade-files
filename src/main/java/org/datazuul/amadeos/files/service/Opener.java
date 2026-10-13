package org.datazuul.amadeos.files.service;

import com.google.inject.Inject;
import org.datazuul.amadeos.commons.desktop.DesktopLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Opens files with the user's applications. Plain text (logs, configuration and dot files, files
 * without extension) opens in the amadeOS Text Editor, as the CDE File Manager opened text in its
 * editor; everything else with the default application for its type ({@code xdg-open}).
 */
public class Opener {
    private static final Logger LOG = LoggerFactory.getLogger(Opener.class);
    static final String EDITOR = "amade-pad";
    static final String XDG_OPEN = "xdg-open";
    /** Bytes read to decide whether a file is text. */
    static final int SAMPLE = 8192;

    private final DesktopLauncher desktop;
    private final String searchPath;

    @Inject
    public Opener(DesktopLauncher desktop) {
        this(desktop, System.getenv("PATH"));
    }

    Opener(DesktopLauncher desktop, String searchPath) {
        this.desktop = desktop;
        this.searchPath = searchPath;
    }

    /** Plain text in the Text Editor, other files with their default application. */
    public void open(Path file) {
        Optional<Path> editor = findOnPath(EDITOR, searchPath);
        if (editor.isPresent() && isPlainText(file)) {
            start(editor.get(), file);
            return;
        }
        openWithDefault(file);
    }

    /** F4: in the Text Editor whatever the type; without it, with the default application. */
    public void edit(Path file) {
        Optional<Path> editor = findOnPath(EDITOR, searchPath);
        if (editor.isPresent()) start(editor.get(), file);
        else openWithDefault(file);
    }

    private void openWithDefault(Path file) {
        try {
            desktop.open(file);
        } catch (IOException e) {
            String hint = findOnPath(XDG_OPEN, searchPath).isEmpty()
                    ? "no application is set up to open it (xdg-open from the package xdg-utils is missing)"
                    : e.getMessage();
            throw new FileOperationException("Cannot open " + file.getFileName() + ": " + hint + ".", e);
        }
    }

    private static void start(Path editor, Path file) {
        try {
            new ProcessBuilder(editor.toString(), file.toString())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new FileOperationException("Cannot start the Text Editor: " + e.getMessage(), e);
        }
    }

    /**
     * True for text without a more specific type: the type from the file name is {@code text/plain} or
     * unknown, and the content has no NUL bytes and hardly any control characters. HTML, SVG, CSV and
     * other typed text keep their own application.
     */
    static boolean isPlainText(Path file) {
        String type = null;
        try {
            type = Files.probeContentType(file);
        } catch (IOException e) {
            LOG.debug("No type for {}", file, e);
        }
        if (type != null && !type.equals("text/plain") && !type.equals("application/octet-stream")) return false;
        byte[] sample;
        try (InputStream in = Files.newInputStream(file)) {
            sample = in.readNBytes(SAMPLE);
        } catch (IOException e) {
            return false;
        }
        int control = 0;
        for (byte b : sample) {
            int c = b & 0xff;
            if (c == 0) return false;
            // Tab, line feed, form feed, carriage return and escape (colour codes in logs) are normal in text.
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\f' && c != '\r' && c != 0x1b) control++;
        }
        return control * 100 <= sample.length * 2;
    }

    static Optional<Path> findOnPath(String command, String path) {
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path candidate = Path.of(dir, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }
}
