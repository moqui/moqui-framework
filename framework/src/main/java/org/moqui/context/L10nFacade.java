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
package org.moqui.context;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.TimeZone;

/** For localization (l10n) functionality, like localizing messages. */
public interface L10nFacade {

    /** Use the current locale (see ec.user.getLocale() method) to localize the message based on data in the
     * moqui.basic.LocalizedMessage entity. The localized message may have variables inserted using the ${} syntax that
     * when this is called through ec.resource.expand().
     *
     * The approach here is that original messages are actual messages in the primary language of the application. This
     * reduces issues with duplicated messages compared to the approach of explicit/artificial property keys. Longer
     * messages (over 255 characters) should use an artificial message key with the actual value always coming
     * from the database.
     */
    String localize(String original);
    /** Localize a String using the given Locale instead of the current user's. */
    String localize(String original, Locale locale);

    /** Format currency amount for user to view.
     * @param amount An object representing the amount, should be a subclass of Number.
     * @param uomId The uomId (ISO currency code), required.
     * @param fractionDigits Number of digits after the decimal point to display. If null defaults to number defined
     *                       by java.util.Currency.defaultFractionDigits() for the specified currency in uomId.
     * @return The formatted currency amount.
     */
    String formatCurrency(Object amount, String uomId, Integer fractionDigits);
    String formatCurrency(Object amount, String uomId);
    String formatCurrency(Object amount, String uomId, Integer fractionDigits, Locale locale);

    /** Round currency according to the currency's specified amount of digits and rounding method.
     * @param amount The amount in BigDecimal to be rounded.
     * @param uomId The currency uomId (ISO currency code), required
     * @param precise A boolean indicating whether the currency should be treated with an additional digit
     * @param roundingMode Rounding method to use (e.g. RoundingMode.HALF_UP)
     * @return The rounded currency amount.
     */
    BigDecimal roundCurrency(BigDecimal amount, String uomId, boolean precise, RoundingMode roundingMode);
    BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId, boolean precise, int roundingMethod);
    BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId, boolean precise);
    BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId);

    /** Format a Number, Timestamp, Date, Time, or Calendar object using the given format string. If no format string
     * is specified the default for the user's locale and time zone will be used.
     *
     * @param value The value to format. Must be a Number, Timestamp, Date, Time, or Calendar object.
     * @param format The format string used to specify how to format the value.
     * @return The value as a String formatted according to the format string.
     */
    String format(Object value, String format);
    String format(Object value, String format, Locale locale, TimeZone tz);

    java.sql.Time parseTime(String input, String format);
    java.sql.Date parseDate(String input, String format);
    Timestamp parseTimestamp(String input, String format);
    Timestamp parseTimestamp(String input, String format, Locale locale, TimeZone timeZone);
    java.util.Calendar parseDateTime(String input, String format);

    java.math.BigDecimal parseNumber(String input, String format);
}
