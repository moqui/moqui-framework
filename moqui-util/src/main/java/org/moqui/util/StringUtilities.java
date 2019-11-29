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
package org.moqui.util;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.swing.text.MaskFormatter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.*;

/**
 * These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
@SuppressWarnings("unused")
public class StringUtilities {
    protected static final Logger logger = LoggerFactory.getLogger(StringUtilities.class);

    public static final Map<String, String> xmlEntityMap;
    static {
        HashMap<String, String> map = new HashMap<>(5);
        map.put("apos", "\'"); map.put("quot", "\""); map.put("amp", "&"); map.put("lt", "<"); map.put("gt", ">");
        xmlEntityMap = map;
    }

    private static final String[] SCALES = new String[]{"", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion", "sextillion"};
    private static final String[] SUBTWENTY = new String[]{"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] DECADES = new String[]{"", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String NEG_NAME = "negative";

    public static String elementValue(Element element) {
        if (element == null) return null;
        element.normalize();
        org.w3c.dom.Node textNode = element.getFirstChild();
        if (textNode == null) return null;

        StringBuilder value = new StringBuilder();
        if (textNode.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE || textNode.getNodeType() == org.w3c.dom.Node.TEXT_NODE)
            value.append(textNode.getNodeValue());
        while ((textNode = textNode.getNextSibling()) != null) {
            if (textNode.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE || textNode.getNodeType() == org.w3c.dom.Node.TEXT_NODE)
                value.append(textNode.getNodeValue());
        }

        return value.toString();
    }

    public static String encodeForXmlAttribute(String original) { return encodeForXmlAttribute(original, false); }

    public static String encodeForXmlAttribute(String original, boolean addZeroWidthSpaces) {
        if (original == null) return "";
        StringBuilder newValue = new StringBuilder(original);
        for (int i = 0; i < newValue.length(); i++) {
            char curChar = newValue.charAt(i);
            switch (curChar) {
                case '\'': newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case '"': newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case '&': newValue.replace(i, i + 1, "&amp;"); i += 4; break;
                case '<': newValue.replace(i, i + 1, "&lt;"); i += 3; break;
                case '>': newValue.replace(i, i + 1, "&gt;"); i += 3; break;
                case 0x5: newValue.replace(i, i + 1, "..."); i += 2; break;
                case 0x12: newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case 0x13: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x14: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x16: newValue.replace(i, i + 1, "-"); break;
                case 0x17: newValue.replace(i, i + 1, "-"); break;
                case 0x19: newValue.replace(i, i + 1, "tm"); i++; break;
                default:
                    if (DefaultGroovyMethods.compareTo(curChar, 0x20) < 0 && curChar != 0x9 && curChar != 0xA && curChar != 0xD) {
                        // the only valid values < 0x20 are 0x9 (tab), 0xA (newline), 0xD (carriage return)
                        newValue.deleteCharAt(i);
                        i--;
                    } else if (DefaultGroovyMethods.compareTo(curChar, 0x7F) > 0) {
                        // Replace each char which is out of the ASCII range with a XML entity
                        String s = "&#" + ((int) curChar) + ";";
                        newValue.replace(i, i + 1, s);
                        i += s.length() - 1;
                    } else if (addZeroWidthSpaces) {
                        newValue.insert(i, "&#8203;");
                        i += 7;
                    }
            }
        }
        return newValue.toString();
    }

    /** See if contains only characters allowed by URLDecoder, if so doesn't need to be encoded or is already encoded */
    public static boolean isUrlDecoderSafe(String text) {
        // see https://docs.oracle.com/javase/8/docs/api/index.html?java/net/URLEncoder.html
        // letters, digits, and: "-", "_", ".", and "*"
        // allow '%' for strings already encoded
        // '+' is treated as space, so allow but means we can't detect if already encoded vs doesn't need to be encoded
        if (text == null) return true;
        // NOTE: expect mostly shorter strings to charAt() faster than text.toCharArray() and chars[i]; more memory efficient too
        int textLen = text.length();
        for (int i = 0; i < textLen; i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) continue;
            if (ch == '.' || ch == '_' || ch == '-' || ch == '*' || ch == '+') continue;
            if (ch == '%') {
                if (i + 2 < textLen) {
                    char ch1 = text.charAt(i + 1);
                    char ch2 = text.charAt(i + 2);
                    if (isHexChar(ch1) && isHexChar(ch2)) {
                        i += 2;
                        continue;
                    }
                }
                return false;
            }
            return false;
        }
        return true;
    }
    public static String urlEncodeIfNeeded(String text) {
        if (isUrlDecoderSafe(text)) return text;
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // should never happen with hard coded encoding
            return text;
        }
    }
    public static boolean isUrlSafeRfc3986(String text) {
        if (text == null) return true;
        // RFC 3986 URL path chars: a-z A-Z 0-9 . _ - + ~ ! $ & ' ( ) * , ; = : @
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            if (Character.isLetterOrDigit(ch)) continue;
            if (ch == '.' || ch == '_' || ch == '-' || ch == '+' || ch == '~' || ch == '!' || ch == '$' || ch == '&' || ch == '\'' ||
                    ch == '(' || ch == ')' || ch == '*' || ch == ',' || ch == ';' || ch == '=' || ch == ':' || ch == '@') continue;
            return false;
        }
        return true;
    }

    public static String camelCaseToPretty(String camelCase) {
        if (camelCase == null || camelCase.length() == 0) return "";
        StringBuilder prettyName = new StringBuilder();
        String lastPart = null;
        for (String part : camelCase.split("(?=[A-Z0-9\\.#])")) {
            if (part.length() == 0) continue;
            char firstChar = part.charAt(0);
            if (firstChar == '.' || firstChar == '#') {
                if (part.length() == 1) continue;
                part = part.substring(1);
                firstChar = part.charAt(0);
            }
            if (Character.isLowerCase(firstChar)) part = Character.toUpperCase(firstChar) + part.substring(1);
            if (part.equalsIgnoreCase("id")) part = "ID";

            if (part.equals(lastPart)) continue;
            lastPart = part;
            if (prettyName.length() > 0) prettyName.append(" ");
            prettyName.append(part);
        }
        return prettyName.toString();
    }
    public static String prettyToCamelCase(String pretty, boolean firstUpper) {
        if (pretty == null || pretty.length() == 0) return "";
        StringBuilder camelCase = new StringBuilder();
        char[] prettyChars = pretty.toCharArray();
        boolean upperNext = firstUpper;
        for (int i = 0; i < prettyChars.length; i++) {
            char curChar = prettyChars[i];
            if (Character.isLetterOrDigit(curChar)) {
                curChar = upperNext ? Character.toUpperCase(curChar) : Character.toLowerCase(curChar);
                camelCase.append(curChar);
                upperNext = false;
            } else {
                upperNext = true;
            }
        }
        return camelCase.toString();
    }

    public static String removeNonAlphaNumeric(String origString) {
        if (origString == null || origString.isEmpty()) return origString;
        int origLength = origString.length();
        char[] orig = origString.toCharArray();
        StringBuilder remBuffer = new StringBuilder();
        int replIdx = 0;
        for (int i = 0; i < origLength; i++) {
            char ochr = orig[i];
            if (Character.isLetterOrDigit(ochr)) { remBuffer.append(ochr); }
        }
        return remBuffer.toString();
    }
    public static String replaceNonAlphaNumeric(String origString, char chr) {
        if (origString == null || origString.isEmpty()) return origString;
        int origLength = origString.length();
        char[] orig = origString.toCharArray();
        char[] repl = new char[origLength];
        int replIdx = 0;
        for (int i = 0; i < origLength; i++) {
            char ochr = orig[i];
            if (Character.isLetterOrDigit(ochr)) { repl[replIdx++] = ochr; }
            else { if (replIdx == 0 || repl[replIdx-1] != chr) { repl[replIdx++] = chr; } }
        }
        return new String(repl, 0, replIdx);
    }
    public static boolean isAlphaNumeric(String str, String allowedChars) {
        if (str == null) return true;
        char[] strChars = str.toCharArray();
        for (int i = 0; i < strChars.length; i++) {
            char c = strChars[i];
            if (!Character.isLetterOrDigit(c) && (allowedChars == null || allowedChars.indexOf(c) == -1)) return false;
        }
        return true;
    }
    public static String findFirstNumber(String orig) {
        if (orig == null || orig.isEmpty()) return orig;
        int origLength = orig.length();
        StringBuilder numBuffer = new StringBuilder();
        for (int i = 0; i < origLength; i++) {
            char curChar = orig.charAt(i);
            if (Character.isDigit(curChar)) {
                numBuffer.append(curChar);
            } else if (numBuffer.length() > 0 && (curChar == '.' || curChar == ',')) {
                numBuffer.append(curChar);
            } else if (numBuffer.length() > 0) {
                // if we have any numbers and find something else we're done
                break;
            }
        }
        if (numBuffer.length() == 0) return null;
        return numBuffer.toString();
    }

    public static String decodeFromXml(String original) {
        if (original == null || original.isEmpty()) return original;
        int pos = original.indexOf("&");
        if (pos == -1) return original;

        StringBuilder newValue = new StringBuilder(original);
        while (pos < newValue.length() && pos >= 0) {
            int scIndex = newValue.indexOf(";", pos + 1);
            if (scIndex == -1) break;
            String entityName = newValue.substring(pos + 1, scIndex);
            String replaceChar;
            if (entityName.charAt(0) == '#') {
                String decStr = entityName.substring(1);
                int decInt = Integer.valueOf(decStr);
                replaceChar = new String(Character.toChars(decInt));
            } else {
                replaceChar = xmlEntityMap.get(entityName);
            }
            // logger.warn("========= pos=${pos}, entityName=${entityName}, replaceChar=${replaceChar}")
            if (replaceChar != null) newValue.replace(pos, scIndex + 1, replaceChar);
            pos = newValue.indexOf("&", pos + 1);
        }
        return newValue.toString();
    }

    public static String cleanStringForJavaName(String original) {
        if (original == null || original.isEmpty()) return original;
        char[] origChars = original.toCharArray();
        char[] cleanChars = new char[origChars.length];
        boolean isIdentifierStart = true;
        for (int i = 0; i < origChars.length; i++) {
            char curChar = origChars[i];
            // remove dots too, get down to simple class name to work best with Groovy class compiling and loading
            // if (curChar == '.') { cleanChars[i] = '.'; isIdentifierStart = true; continue; }
            // also don't allow $ as groovy blows up on it with class compile/load
            if (curChar != '$' && (isIdentifierStart ? Character.isJavaIdentifierStart(curChar) : Character.isJavaIdentifierPart(curChar))) {
                cleanChars[i] = curChar;
            } else {
                cleanChars[i] = '_';
            }
            isIdentifierStart = false;
        }
        // logger.warn("cleaned " + original + " to " + new String(cleanChars));
        return new String(cleanChars);
    }
    public static String getExpressionClassName(String expression) {
        String hashCode = Integer.toHexString(expression.hashCode());
        int hashLength = hashCode.length();
        int exprLength = expression.length();
        int copyChars = exprLength < 30 ? exprLength : 30;
        int length = hashLength + copyChars + 1;
        char[] cnChars = new char[length];
        cnChars[0] = 'S';
        for (int i = 0; i < hashLength; i++) cnChars[i + 1] = hashCode.charAt(i);
        for (int i = 0; i < copyChars; i++) {
            char exprChar = expression.charAt(i);
            if (exprChar == '$' || !Character.isJavaIdentifierPart(exprChar)) exprChar = '_';
            cnChars[i + hashLength + 1] = exprChar;
        }
        return new String(cnChars);
    }

    public static String encodeAsciiFilename(String filename) {
        try {
            URI uri = new URI(null, null, filename, null);
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            logger.warn("Error encoding ASCII filename: " + e.toString());
            return filename;
        }
    }

    public static String toStringCleanBom(byte[] bytes) {
        // NOTE: this only supports UTF-8 for now!
        if (bytes == null || bytes.length == 0) return "";
        try {
            // UTF-8 BOM = 239, 187, 191
            if (bytes[0] == (byte) 239) {
                return new String(bytes, 3, bytes.length - 3, "UTF-8");
            } else {
                return new String(bytes, "UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            throw new BaseException("Error converting bytes to String", e);
        }
    }

    public static String escapeElasticQueryString(CharSequence queryString) {
        int length = queryString.length();
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            char c = queryString.charAt(i);
            if ("+-=&|><!(){}[]^\"~*?:\\/".indexOf(c) != -1) sb.append("\\");
            sb.append(c);
        }
        return sb.toString();
    }

    public static String paddedNumber(long number, Integer desiredLength) {
        StringBuilder outStrBfr = new StringBuilder(Long.toString(number));
        if (desiredLength == null) return outStrBfr.toString();
        while (desiredLength > outStrBfr.length()) outStrBfr.insert(0, "0");
        return outStrBfr.toString();
    }

    public static String paddedString(String input, Integer desiredLength, Character padChar, boolean rightPad) {
        if (!DefaultGroovyMethods.asBoolean(padChar)) padChar = ' ';
        if (input == null) input = "";
        StringBuilder outStrBfr = new StringBuilder(input);
        if (desiredLength == null) return outStrBfr.toString();
        while (desiredLength > outStrBfr.length()) if (rightPad) outStrBfr.append(padChar);
        else outStrBfr.insert(0, padChar);
        return outStrBfr.toString();
    }

    public static String paddedString(String input, Integer desiredLength, boolean rightPad) {
        return paddedString(input, desiredLength, ' ', rightPad);
    }

    public static MaskFormatter masker(String mask, String placeholder) throws ParseException {
        if (mask == null || mask.isEmpty()) return null;
        MaskFormatter formatter = new MaskFormatter(mask);
        formatter.setValueContainsLiteralCharacters(false);
        if (placeholder != null && !placeholder.isEmpty()) {
            if (placeholder.length() == 1) formatter.setPlaceholderCharacter(placeholder.charAt(0));
            else formatter.setPlaceholder(placeholder);
        }
        return formatter;
    }

    public static String getRandomString(int length) {
        SecureRandom sr = new SecureRandom();
        byte[] randomBytes = new byte[length];
        sr.nextBytes(randomBytes);
        String randomStr = Base64.getUrlEncoder().encodeToString(randomBytes);
        if (randomStr.length() > length) randomStr = randomStr.substring(0, length);
        return randomStr;
    }

    public static ArrayList<String> getYearList(int years) {
        ArrayList<String> yearList = new ArrayList<>(years);
        int startYear = Calendar.getInstance().get(Calendar.YEAR);
        for (int i = 0; i < years; i++) yearList.add(Integer.toString(startYear + i));
        return yearList;
    }

    /** Convert any value from 0 to 999 inclusive, to a string. */
    private static String tripleAsWords(int value, boolean useAnd) {
        if (value < 0 || value >= 1000) throw new IllegalArgumentException("Illegal triple-value " + value);
        if (value < SUBTWENTY.length) return SUBTWENTY[value];

        int subhun = value % 100;
        int hun = value / 100;
        StringBuilder sb = new StringBuilder(50);
        if (hun > 0) sb.append(SUBTWENTY[hun]).append(" hundred");
        if (subhun > 0) {
            if (hun > 0) sb.append(useAnd ? " and " : " ");
            if (subhun < SUBTWENTY.length) {
                sb.append(" ").append(SUBTWENTY[subhun]);
            } else {
                int tens = subhun / 10;
                int units = subhun % 10;
                if (tens > 0) sb.append(DECADES[tens]);
                if (units > 0) sb.append(" ").append(SUBTWENTY[units]);
            }
        }
        return sb.toString();
    }

    /** Convert any long input value to a text representation
     * @param value  The value to convert
     * @param useAnd true if you want to use the word 'and' in the text (eleven thousand and thirteen)
     */
    public static String numberToWords(long value, boolean useAnd) {
        if (value == 0L) return SUBTWENTY[0];

        // break the value down in to sets of three digits (thousands)
        Integer[] thous = new Integer[SCALES.length];
        boolean neg = value < 0;
        // do not make negative numbers positive, to handle Long.MIN_VALUE
        int scale = 0;
        while (value != 0) {
            // use abs to convert thousand-groups to positive, if needed.
            thous[scale] = Math.abs((int) (value % 1000));
            value = value / 1000;
            scale++;
        }

        StringBuilder sb = new StringBuilder(scale * 40);
        if (neg) sb.append(NEG_NAME).append(" ");
        boolean first = true;
        while ((scale = --scale) > 0) {
            if (!first) sb.append(", ");
            first = false;
            if (thous[scale] > 0) sb.append(tripleAsWords(thous[scale], useAnd)).append(" ").append(SCALES[scale]);
        }

        if (!first && thous[0] != 0) {
            if (useAnd) sb.append(" and ");
            else sb.append(" ");
        }

        sb.append(tripleAsWords(thous[0], useAnd));

        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    public static String numberToWordsWithDecimal(BigDecimal value) {
        final String integerText = numberToWords(value.longValue(), false);
        String decimalText = value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        decimalText = decimalText.substring(decimalText.indexOf(".") + 1);
        return integerText + " and " + decimalText + "/100";
    }

    public static String removeChar(String orig, char ch) {
        if (orig == null) return null;
        char[] origChars = orig.toCharArray();
        int origLength = origChars.length;
        // NOTE: this seems to run pretty slow, plain replace might be faster, but avoiding its use anyway (in ServiceFacadeImpl for SECA rules)
        char[] newChars = new char[origLength];
        int lastPos = 0;
        for (int i = 0; i < origLength; i++) {
            char curChar = origChars[i];
            if (curChar != ch) {
                newChars[lastPos] = curChar;
                lastPos++;
            }
        }
        if (lastPos == origLength) return orig;
        return new String(newChars, 0, lastPos);
    }

    // Lookup table for CRC16 based on irreducible polynomial: 1 + x^2 + x^15 + x^16
    private static final int[] crc16Table = {
            0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
            0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
            0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
            0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
            0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
            0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
            0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
            0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
            0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
            0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
            0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
            0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
            0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
            0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
            0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
            0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
            0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
            0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
            0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
            0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
            0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
            0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
            0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
            0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
            0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
            0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
            0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
            0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
            0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
            0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
            0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
            0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
    };
    public static int calculateCrc16(String input) {
        byte[] bytes = input.getBytes();
        int crc = 0x0000;
        for (byte b : bytes) crc = (crc >>> 8) ^ crc16Table[(crc ^ b) & 0xff];
        return crc;
    }

    public static boolean isHexChar(char c) {
        switch (c) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
            case 'a':
            case 'b':
            case 'c':
            case 'd':
            case 'e':
            case 'f':
            case 'A':
            case 'B':
            case 'C':
            case 'D':
            case 'E':
            case 'F':
                return true;
            default:
                return false;
        }
    }
}
