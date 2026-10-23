package eu.amadeos.apps.files.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab in the path bar: completes the last folder name of a typed path, as a shell does.
 */
public final class PathCompletion {
    private PathCompletion() {
    }

    /**
     * @param text  what was typed; {@code ~} is the home folder, relative paths start at {@code base}
     * @return the text with the folder name completed (a single match gets a trailing slash), or the
     *         text unchanged if nothing matches
     */
    public static String complete(String text, Path base, Path home) {
        int slash = text.lastIndexOf('/');
        String folderPart = slash < 0 ? "" : text.substring(0, slash + 1);
        String prefix = text.substring(slash + 1);
        Path folder;
        try {
            if (folderPart.isEmpty()) folder = prefix.equals("~") ? null : base;
            else if (folderPart.equals("~/") || folderPart.startsWith("~/")) folder = home.resolve(folderPart.substring(2));
            else folder = Path.of(folderPart).isAbsolute() ? Path.of(folderPart) : base.resolve(folderPart);
        } catch (InvalidPathException e) {
            return text;
        }
        if (folder == null) return "~/";
        List<String> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                // Hidden folders only when a dot was typed.
                if (name.startsWith(prefix) && (prefix.startsWith(".") || !name.startsWith(".")) && Files.isDirectory(path)) {
                    matches.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return text;
        }
        if (matches.isEmpty()) return text;
        if (matches.size() == 1) return folderPart + matches.getFirst() + "/";
        String common = matches.getFirst();
        for (String match : matches) {
            int i = 0;
            while (i < common.length() && i < match.length() && common.charAt(i) == match.charAt(i)) i++;
            common = common.substring(0, i);
        }
        return folderPart + common;
    }
}
