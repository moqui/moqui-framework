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
package org.moqui.impl.context;

import org.moqui.BaseArtifactException;
import org.moqui.context.L10nFacade;
import org.moqui.entity.EntityValue;
import org.moqui.entity.EntityFind;

import javax.xml.bind.DatatypeConverter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;

import org.apache.commons.validator.routines.BigDecimalValidator;
import org.apache.commons.validator.routines.CalendarValidator;

import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class L10nFacadeImpl implements L10nFacade {
    protected final static Logger logger = LoggerFactory.getLogger(L10nFacadeImpl.class);

    final static BigDecimalValidator bigDecimalValidator = new BigDecimalValidator(false);
    final static CalendarValidator calendarValidator = new CalendarValidator();

    protected final ExecutionContextImpl eci;

    public L10nFacadeImpl(ExecutionContextImpl eci) { this.eci = eci; }

    protected Locale getLocale() { return eci.userFacade.getLocale(); }
    protected TimeZone getTimeZone() { return eci.userFacade.getTimeZone(); }

    @Override
    public String localize(String original) { return localize(original, getLocale()); }
    @Override
    public String localize(String original, Locale locale) {
        if (original == null) return "";
        int originalLength = original.length();
        if (originalLength == 0) return "";
        if (originalLength > 255) {
            throw new BaseArtifactException("Original String cannot be more than 255 characters long, passed in string was " + originalLength + " characters long");
        }

        if (locale == null) locale = getLocale();
        String localeString = locale.toString();

        String cacheKey = original.concat("::").concat(localeString);
        String lmsg = eci.getL10nMessageCache().get(cacheKey);
        if (lmsg != null) return lmsg;

        String defaultValue = original;
        int localeUnderscoreIndex = localeString.indexOf('_');

        EntityFind find = eci.getEntity().find("moqui.basic.LocalizedMessage")
                .condition("original", original).condition("locale", localeString).useCache(true);
        EntityValue localizedMessage = find.one();
        if (localizedMessage == null && localeUnderscoreIndex > 0)
            localizedMessage = find.condition("locale", localeString.substring(0, localeUnderscoreIndex)).one();
        if (localizedMessage == null)
            localizedMessage = find.condition("locale", "default").one();

        // if original has a hash and we still don't have a localizedMessage then use what precedes the hash and try again
        if (localizedMessage == null) {
            int indexOfCloseCurly = original.lastIndexOf('}');
            int indexOfHash = original.lastIndexOf("##");
            if (indexOfHash > 0 && indexOfHash > indexOfCloseCurly) {
                defaultValue = original.substring(0, indexOfHash);
                EntityFind findHash = eci.getEntity().find("moqui.basic.LocalizedMessage")
                        .condition("original", defaultValue).condition("locale", localeString).useCache(true);
                localizedMessage = findHash.one();
                if (localizedMessage == null && localeUnderscoreIndex > 0)
                    localizedMessage = findHash.condition("locale", localeString.substring(0, localeUnderscoreIndex)).one();
                if (localizedMessage == null)
                    localizedMessage = findHash.condition("locale", "default").one();
            }
        }

        String result = localizedMessage != null ? localizedMessage.getString("localized") : defaultValue;
        eci.getL10nMessageCache().put(cacheKey, result);
        return result;
    }

    @Override
    public String formatCurrency(Object amount, String uomId) { return formatCurrency(amount, uomId, null, getLocale()); }
    @Override
    public String formatCurrency(Object amount, String uomId, Integer fractionDigits) {
        return formatCurrency(amount, uomId, fractionDigits, getLocale());
    }
    @Override
    public String formatCurrency(Object amount, String uomId, Integer fractionDigits, Locale locale) {
        if (amount == null) return "";
        if (amount instanceof CharSequence) {
            if (((CharSequence) amount).length() == 0) {
                return "";
            } else {
                amount = parseNumber((String) amount, null);
            }
        }

        Currency currency = uomId != null && uomId.length() > 0 ? Currency.getInstance(uomId) : null;
        if (locale == null) locale = getLocale();
        if (currency != null) {
            NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
            nf.setCurrency(currency);
            if (fractionDigits == null) fractionDigits = currency.getDefaultFractionDigits();
            nf.setMaximumFractionDigits(fractionDigits);
            nf.setMinimumFractionDigits(fractionDigits);
            return nf.format(amount);
        } else {
            NumberFormat nf = NumberFormat.getInstance();
            if (fractionDigits == null) fractionDigits = 2;
            nf.setMaximumFractionDigits(fractionDigits);
            nf.setMinimumFractionDigits(fractionDigits);
            return nf.format(amount);
        }
    }

    @Override
    public BigDecimal roundCurrency(BigDecimal amount, String uomId) { return roundCurrency(amount, uomId, false); }
    @Override
    public BigDecimal roundCurrency(BigDecimal amount, String uomId, boolean precise) { return roundCurrency(amount, uomId, false, RoundingMode.HALF_UP); }
    @Override
    public BigDecimal roundCurrency(BigDecimal amount, String uomId, boolean precise, int roundingMethod) {
        Currency currency = Currency.getInstance(uomId);
        int nDigits = currency.getDefaultFractionDigits();
        if (precise) nDigits++;
        return amount.setScale(nDigits, roundingMethod);
    }
    @Override
    public BigDecimal roundCurrency(BigDecimal amount, String uomId, boolean precise, RoundingMode mode) {
        Currency currency = Currency.getInstance(uomId);
        int nDigits = currency.getDefaultFractionDigits();
        if (precise) nDigits++;
        return amount.setScale(nDigits, mode);
    }

    @Override
    public Time parseTime(String input, String format) {
        Locale curLocale = getLocale();
        TimeZone curTz = getTimeZone();
        if (format == null || format.isEmpty()) format = "HH:mm:ss.SSS";
        Calendar cal = calendarValidator.validate(input, format, curLocale, curTz);
        if (cal == null) cal = calendarValidator.validate(input, "HH:mm:ss", curLocale, curTz);
        if (cal == null) cal = calendarValidator.validate(input, "HH:mm", curLocale, curTz);
        if (cal == null) cal = calendarValidator.validate(input, "h:mm a", curLocale, curTz);
        if (cal == null) cal = calendarValidator.validate(input, "h:mm:ss a", curLocale, curTz);
        // also try the full ISO-8601, times may come in that way (even if funny with a date of 1970-01-01)
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale, curTz);
        if (cal != null) {
            Time time = new Time(cal.getTimeInMillis());
            // logger.warn("============== parseTime input=${input} cal=${cal} long=${cal.getTimeInMillis()} time=${time} time long=${time.getTime()} util date=${new java.util.Date(cal.getTimeInMillis())} timestamp=${new java.sql.Timestamp(cal.getTimeInMillis())}")
            return time;
        }

        // try interpreting the String as a long
        try {
            Long lng = Long.valueOf(input);
            return new Time(lng);
        } catch (NumberFormatException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Time parse: " + e.toString());
        }

        return null;
    }
    public String formatTime(Time input, String format, Locale locale, TimeZone tz) {
        if (locale == null) locale = getLocale();
        if (tz == null) tz = getTimeZone();
        if (format == null || format.isEmpty()) format = "HH:mm:ss";
        String timeStr = calendarValidator.format(input, format, locale, tz);
        // logger.warn("============= formatTime input=${input} timeStr=${timeStr} long=${input.getTime()}")
        return timeStr;
    }

    @Override
    public java.sql.Date parseDate(String input, String format) {
        if (format == null || format.isEmpty()) format = "yyyy-MM-dd";
        Locale curLocale = getLocale();

        // NOTE DEJ 20150317 Date parsing in terms of time zone causes funny issues because the time part of the long
        //   since epoch representation is lost going to/from the DB, especially since the time portion is set to 0 and
        //   with time zone conversion when the system date is in an earlier time zone than the user date it pushes the
        //   Date to the previous day; what seems like the best solution is to parse and save the Date in the
        //   system/default time zone, and format it that way as well.
        // The BIG dilemma is there is no way to represent a Date (yyyy-MM-dd) in an object that does not use the long
        //   since epoch but rather is an absolute year, month, and day... which is really what we want.
        /*
        TimeZone curTz = getTimeZone()
        Calendar cal = calendarValidator.validate(input, format, curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "MM/dd/yyyy", curLocale, curTz)
        // also try the full ISO-8601, dates may come in that way
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale, curTz)
        */

        Calendar cal = calendarValidator.validate(input, format, curLocale);
        if (cal == null) cal = calendarValidator.validate(input, "MM/dd/yyyy", curLocale);
        // also try the full ISO-8601, dates may come in that way
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale);
        if (cal != null) {
            java.sql.Date date = new java.sql.Date(cal.getTimeInMillis());
            // logger.warn("============== parseDate input=${input} cal=${cal} long=${cal.getTimeInMillis()} date=${date} date long=${date.getTime()} util date=${new java.util.Date(cal.getTimeInMillis())} timestamp=${new java.sql.Timestamp(cal.getTimeInMillis())}")
            return date;
        }

        // try interpreting the String as a long
        try {
            Long lng = Long.valueOf(input);
            return new java.sql.Date(lng);
        } catch (NumberFormatException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Date parse: " + e.toString());
        }

        return null;
    }
    public String formatDate(java.util.Date input, String format, Locale locale, TimeZone tz) {
        if (locale == null) locale = getLocale();
        // if (tz == null) tz = getTimeZone();
        if (format == null || format.isEmpty()) format = "yyyy-MM-dd";
        // See comment in parseDate for why we are ignoring the time zone
        // String dateStr = calendarValidator.format(input, format, getLocale(), getTimeZone())
        String dateStr = calendarValidator.format(input, format, locale);
        // logger.warn("============= formatDate input=${input} dateStr=${dateStr} long=${input.getTime()}")
        return dateStr;
    }

    static final ArrayList<String> timestampFormats;
    static {
        timestampFormats = new ArrayList<>();
        timestampFormats.add("yyyy-MM-dd HH:mm"); timestampFormats.add("yyyy-MM-dd HH:mm:ss.SSS");
        timestampFormats.add("yyyy-MM-dd'T'HH:mm:ss"); timestampFormats.add("yyyy-MM-dd'T'HH:mm:ssZ");
        timestampFormats.add("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        timestampFormats.add("yyyy-MM-dd HH:mm:ss"); timestampFormats.add("yyyy-MM-dd");
        timestampFormats.add("yyyy-MM-dd HH:mm:ss.SSS z");
    }

    @Override
    public Timestamp parseTimestamp(String input, String format) {
        if (input == null || input.isEmpty()) return null;
        return parseTimestamp(input, format, null, null);
    }
    @Override
    public Timestamp parseTimestamp(final String input, final String format, final Locale locale, final TimeZone timeZone) {
        if (input == null || input.isEmpty()) return null;
        Locale curLocale = locale != null ? locale : getLocale();
        TimeZone curTz = timeZone != null ? timeZone : getTimeZone();
        Calendar cal = null;
        if (format != null && !format.isEmpty()) cal = calendarValidator.validate(input, format, curLocale, curTz);

        // long values are pretty common, so if there are no special characters try that first (fast to check)
        if (cal == null) {
            int nonDigits = ObjectUtilities.countChars(input, false, true, true);
            if (nonDigits == 0) {
                try {
                    Long lng = Long.valueOf(input);
                    return new Timestamp(lng);
                } catch (NumberFormatException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Timestamp parse: " + e.toString());
                }
            }
        }

        // try a bunch of other format strings
        if (cal == null) {
            int timestampFormatsSize = timestampFormats.size();
            for (int i = 0; cal == null && i < timestampFormatsSize; i++) {
                String tf = timestampFormats.get(i);
                cal = calendarValidator.validate(input, tf, curLocale, curTz);
            }
        }

        // logger.warn("=========== input=${input}, cal=${cal}, long=${cal?.getTimeInMillis()}, locale=${curLocale}, timeZone=${curTz}, System=${System.currentTimeMillis()}")
        if (cal != null) return new Timestamp(cal.getTimeInMillis());

        try {
            // NOTE: do this AFTER the long parse because long numbers are interpreted really weird by this
            // ISO 8601 parsing using JAXB DatatypeConverter.parseDateTime(); on Java 7 can use "X" instead of "Z" in format string, but not in Java 6
            cal = DatatypeConverter.parseDateTime(input);
            if (cal != null) return new Timestamp(cal.getTimeInMillis());
        } catch (Exception e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring Exception for DatatypeConverter Timestamp parse: " + e.toString());
        }

        return null;
    }
    public static String formatTimestamp(java.util.Date input, String format, Locale locale, TimeZone tz) {
        if (format == null || format.isEmpty()) format = "yyyy-MM-dd HH:mm";
        return calendarValidator.format(input, format, locale, tz);
    }

    @Override public Calendar parseDateTime(String input, String format) {
        return calendarValidator.validate(input, format, getLocale(), getTimeZone()); }
    public String formatDateTime(Calendar input, String format, Locale locale, TimeZone tz) {
        if (locale == null) locale = getLocale();
        if (tz == null) tz = getTimeZone();
        return calendarValidator.format(input, format, locale, tz);
    }

    @Override public BigDecimal parseNumber(String input, String format) {
        return bigDecimalValidator.validate(input, format, getLocale()); }
    public String formatNumber(Number input, String format, Locale locale) {
        if (locale == null) locale = getLocale();
        return bigDecimalValidator.format(input, format, locale);
    }

    @Override
    public String format(Object value, String format) {
        return this.format(value, format, getLocale(), getTimeZone());
    }
    @Override
    public String format(Object value, String format, Locale locale, TimeZone tz) {
        if (value == null) return "";
        if (locale == null) locale = getLocale();
        if (tz == null) tz = getTimeZone();
        Class valueClass = value.getClass();
        if (valueClass == String.class) return (String) value;
        if (valueClass == Timestamp.class) return formatTimestamp((Timestamp) value, format, locale, tz);
        if (valueClass == java.util.Date.class) return formatTimestamp((java.util.Date) value, format, locale, tz);
        if (valueClass == java.sql.Date.class) return formatDate((Date) value, format, locale, tz);
        if (valueClass == Time.class) return formatTime((Time) value, format, locale, tz);
        // this one needs to be instanceof to include the many sub-classes of Number
        if (value instanceof Number) return formatNumber((Number) value, format, locale);
        // Calendar is an abstract class, so must use instanceof here as well
        if (value instanceof Calendar) return formatDateTime((Calendar) value, format, locale, tz);
        return value.toString();
    }
}
