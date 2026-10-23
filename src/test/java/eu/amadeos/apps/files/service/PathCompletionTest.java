package eu.amadeos.apps.files.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathCompletionTest {
    @TempDir
    Path home;

    @Test
    void completesFoldersLikeAShell() throws IOException {
        Files.createDirectories(home.resolve("Dokumente/Projekte"));
        Files.createDirectories(home.resolve("Downloads"));
        Files.createDirectories(home.resolve(".config"));
        Files.writeString(home.resolve("Dokumente/Projektplan.txt"), "x");

        assertEquals("Do", PathCompletion.complete("D", home, home), "common start of Dokumente and Downloads");
        assertEquals("Dokumente/", PathCompletion.complete("Dok", home, home));
        assertEquals("~/Dokumente/Projekte/", PathCompletion.complete("~/Dokumente/Pro", home, home), "files do not count");
        assertEquals(home + "/Downloads/", PathCompletion.complete(home + "/Dow", home, home));
        assertEquals("~/", PathCompletion.complete("~", home, home));
        assertEquals("~/.config/", PathCompletion.complete("~/.c", home, home));
        assertEquals("~/Dokumente/Projekte/", PathCompletion.complete("~/Dokumente/", home, home));
        assertEquals("Nichts", PathCompletion.complete("Nichts", home, home));
        assertEquals("/no/such/x", PathCompletion.complete("/no/such/x", home, home));
    }
}
