package eu.amadeos.apps.files.service;

import eu.amadeos.apps.files.domain.ClipboardFiles;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The text formats other programs use for files on the clipboard and in drag and drop:
 * {@code text/uri-list} (RFC 2483) and {@code x-special/gnome-copied-files}, which the GTK file
 * managers use to tell Cut from Copy.
 */
public final class FileUris {
    private FileUris() {
    }

    /** {@code file:///home/ralf/a%20b.txt}, without the slash that {@link Path#toUri()} adds to folders. */
    public static String uri(Path path) {
        String uri = path.toAbsolutePath().normalize().toUri().toASCIIString();
        return uri.endsWith("/") && !uri.equals("file:///") ? uri.substring(0, uri.length() - 1) : uri;
    }

    /** One URI per line, CRLF as RFC 2483 asks. */
    public static String uriList(List<Path> paths) {
        StringBuilder text = new StringBuilder();
        for (Path path : paths) text.append(uri(path)).append("\r\n");
        return text.toString();
    }

    /** {@code copy} or {@code cut}, then one URI per line. */
    public static String gnomeCopiedFiles(ClipboardFiles files) {
        StringBuilder text = new StringBuilder(files.cut() ? "cut" : "copy");
        for (Path path : files.paths()) text.append('\n').append(uri(path));
        return text.toString();
    }

    /** The local files of a URI list; comments, blank lines and other schemes are left out. */
    public static List<Path> parseUriList(String text) {
        List<Path> paths = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            Path path = localPath(line.strip());
            if (path != null) paths.add(path);
        }
        return paths;
    }

    /** Null if the text is not in the format. */
    public static ClipboardFiles parseGnomeCopiedFiles(String text) {
        String[] lines = text.split("\r?\n", 2);
        String action = lines[0].strip();
        if (!action.equals("copy") && !action.equals("cut")) return null;
        return new ClipboardFiles(lines.length > 1 ? parseUriList(lines[1]) : List.of(), action.equals("cut"));
    }

    private static Path localPath(String line) {
        if (line.isEmpty() || line.startsWith("#") || !line.startsWith("file:")) return null;
        try {
            URI uri = new URI(line);
            // file://localhost/… is local as well; another host is not.
            if (uri.getHost() != null && !uri.getHost().isEmpty() && !uri.getHost().equals("localhost")) return null;
            return Path.of(uri.getPath());
        } catch (Exception e) {
            return null;
        }
    }
}
