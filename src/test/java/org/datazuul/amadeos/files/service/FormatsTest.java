package org.datazuul.amadeos.files.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Formats and Opener's PATH lookup. */
class FormatsTest {
    @Test
    void sizes() {
        assertEquals("0 B", Formats.size(0));
        assertEquals("1023 B", Formats.size(1023));
        assertEquals("1.0 KB", Formats.size(1024));
        assertEquals("2.4 KB", Formats.size(2458));
        assertEquals("18 GB", Formats.size(18L * 1024 * 1024 * 1024));
    }

    @Test
    void datesShowTheTimeOnlyForToday() {
        String old = Formats.date(Instant.parse("2020-01-02T10:15:00Z"), ZoneOffset.UTC, Locale.GERMANY);
        assertTrue(old.contains("02.01.20"), old);
        String today = Formats.date(Instant.now(), ZoneOffset.UTC, Locale.GERMANY);
        assertFalse(today.contains("."), today);
        assertEquals("", Formats.date(null, ZoneOffset.UTC, Locale.GERMANY));
    }

    @Test
    void counts() {
        assertEquals("1 item", Formats.count(1, "item"));
        assertEquals("3 items", Formats.count(3, "item"));
    }

    @Test
    void findsCommandsOnPath() {
        assertEquals(Optional.of(Path.of("/bin/sh")), Opener.findOnPath("sh", "/nonexistent::/bin"));
        assertTrue(Opener.findOnPath("surely-not-installed-xyz", "/bin:/usr/bin").isEmpty());
        assertTrue(Opener.findOnPath("sh", null).isEmpty());
    }
}
