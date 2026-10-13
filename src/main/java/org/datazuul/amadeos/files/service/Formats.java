package org.datazuul.amadeos.files.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * Sizes and dates as the panels show them.
 */
public final class Formats {
    private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB"};

    private Formats() {
    }

    /** {@code 512 B}, {@code 2.4 KB}, {@code 18 GB} (powers of 1024). */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = value < 10 ? String.format(Locale.ROOT, "%.1f", value) : Long.toString(Math.round(value));
        return number + " " + UNITS[unit];
    }

    /** Time only for today, otherwise the short date and time of the locale. */
    public static String date(Instant instant, ZoneId zone, Locale locale) {
        if (instant == null) return "";
        ZonedDateTime time = instant.atZone(zone);
        if (time.toLocalDate().equals(LocalDate.now(zone))) {
            return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time);
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(time);
    }

    public static String date(Instant instant) {
        return date(instant, ZoneId.systemDefault(), Locale.getDefault());
    }

    /** {@code 1 item}, {@code 3 items}. */
    public static String count(int count, String singular) {
        return count + " " + singular + (count == 1 ? "" : "s");
    }
}
