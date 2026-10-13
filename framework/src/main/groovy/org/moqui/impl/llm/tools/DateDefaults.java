/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.llm.tools;

import org.moqui.context.ExecutionContext;
import org.moqui.impl.context.L10nFacadeImpl;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.TimeZone;

/** Turns a model-written date default (epoch, ISO text, now) into the text that m-date-time displays. */
public final class DateDefaults {
    private DateDefaults() { }

    /**
     * m-date-time shows YYYY-MM-DD HH:mm (or a date/time slice). Epoch millis, epoch seconds,
     * ISO strings, and the literal now become that pattern in the given time zone.
     * Values that are not instants are returned unchanged.
     */
    public static Object normalize(Object dv, String widgetType, long nowMillis, Locale locale, TimeZone tz) {
        if (dv == null) return null;
        if (locale == null) locale = Locale.ROOT;
        if (tz == null) tz = TimeZone.getTimeZone("UTC");
        Long epoch = epochMillis(dv, nowMillis);
        if (epoch == null && dv instanceof CharSequence)
            epoch = parseDateText(dv.toString().trim(), locale, tz);
        if (epoch == null) return dv;
        String formatted = L10nFacadeImpl.formatTimestamp(new java.util.Date(epoch), datePattern(widgetType), locale, tz);
        return formatted != null ? formatted : dv;
    }
    static String datePattern(String widgetType) {
        if ("date".equals(widgetType)) return "yyyy-MM-dd";
        if ("time".equals(widgetType)) return "HH:mm";
        return "yyyy-MM-dd HH:mm";
    }
    static Locale dateLocale(ExecutionContext ec) {
        try {
            if (ec != null && ec.getUser() != null && ec.getUser().getLocale() != null)
                return ec.getUser().getLocale();
        } catch (Throwable ignored) { }
        return Locale.ROOT;
    }
    static TimeZone dateZone(ExecutionContext ec) {
        try {
            if (ec != null && ec.getUser() != null && ec.getUser().getTimeZone() != null)
                return ec.getUser().getTimeZone();
        } catch (Throwable ignored) { }
        return TimeZone.getTimeZone("UTC");
    }
    static Long epochMillis(Object dv, long nowMillis) {
        if (dv instanceof CharSequence) {
            String s = dv.toString().trim();
            if (s.equalsIgnoreCase("now")) return nowMillis;
            if (!s.isEmpty() && digitSpan(s)) {
                try { return scaleEpoch(Long.parseLong(s)); }
                catch (NumberFormatException ignored) { return null; }
            }
            return null;
        }
        if (dv instanceof Number) return scaleEpoch(((Number) dv).longValue());
        return null;
    }
    static boolean digitSpan(String s) {
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start >= s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
    /** Millis when abs ≥ 1e11, seconds when abs ≥ 1e9, otherwise not an epoch. */
    static Long scaleEpoch(long n) {
        long abs = n == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(n);
        if (abs >= 100_000_000_000L) return n;
        if (abs >= 1_000_000_000L) return n * 1000L;
        return null;
    }
    static Long parseDateText(String s, Locale locale, TimeZone tz) {
        if (s == null || s.isEmpty()) return null;
        ZoneId zone;
        try { zone = tz.toZoneId(); }
        catch (DateTimeException e) { zone = ZoneId.of("UTC"); }
        try { return Instant.parse(s).toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm" };
        for (String pattern : patterns) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, locale));
                return ldt.atZone(zone).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) { }
        }
        try {
            LocalDate ld = LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd", locale));
            return ld.atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) { }
        try {
            LocalTime lt = LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm:ss", locale));
            return lt.atDate(LocalDate.now(zone)).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) { }
        try {
            LocalTime lt = LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm", locale));
            return lt.atDate(LocalDate.now(zone)).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) { }
        return null;
    }
}
