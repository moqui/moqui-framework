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
package org.moqui.impl

import dtq.rockycube.query.SqlExecutor
import org.moqui.context.ElasticFacade
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityJavaUtil

import java.sql.Connection
import org.slf4j.Logger

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/** These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
class ViUtilities {
    static final HashMap extractPycalcArgs(HashMap argsMap)
    {
        def recPycalc = Pattern.compile("^(?:pycalc)_?(.*)")
        def cleaned = [:]
        argsMap.each {it->
            def m = recPycalc.matcher(it.key)
            if (!m.matches()) return
            cleaned[m.group(1)] = it.value
        }
        return cleaned
    }

    static final String removeNonumericCharacters(String inputValue) {
        return inputValue.replaceAll("[^\\d]", "")
    }

    static LocalDate stringToDate(Object input) {
        if (!input) return null

        switch (input.getClass().simpleName) {
            case "String":
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                return LocalDate.parse(input.toString(), formatter)
            default:
                throw new Exception("Unsupported date conversion from type ${input.getClass().simpleName}")
        }
    }

    static Long stringToUnix(String input) {
        if (input.isNumber()) return Long.parseLong(input)

        // is it date format?
        def date_1_rec = Pattern.compile("\\d{4}-\\d{2}-\\d{2}:\\d{2}:\\d{2}:\\d{2}")
        if (input ==~ date_1_rec) {
            def date_1 = new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss").parse(input)
            return date_1.getTime() / 1000
        }
        return null
    }

    public static String formattedTimestamp()
    {
        def date = new Date()
        return date.format("yyMMdd_HHmmss")
    }

    public static boolean isAlphaNumeric(String s){
        String pattern= '^[a-zA-Z0-9]*$'
        return s.matches(pattern);
    }

    public static boolean isAlphabetic(String s){
        String pattern= '^[a-zA-Z]*$'
        return s.matches(pattern);
    }

    public static String formattedString(String conversionType, String input)
    {
        // EntityJavaUtil - for the sake of converting strings
        EntityJavaUtil util = new EntityJavaUtil()

        switch (conversionType)
        {
            case 'underscoredToCamelCase-firstUpper':
                return util.underscoredToCamelCase(input, true)
                break
            case 'underscoredToCamelCase':
                return util.underscoredToCamelCase(input, false)
                break
            default:
                return null
        }
    }

    static final String removeCommas(String inputValue) {
        return inputValue.replaceAll(",", ".")
    }

    // we have a list, let's find a comma that is not within brackets
    // e.g. `OR(1,AND(5,6)),AND(3,4)`
    static final ArrayList splitWithBracketsCheck(String inputValue, String splitRegex = ",") {
        def lastIndex = 0
        def separatorsPos = inputValue.findAll(splitRegex){ match ->
            lastIndex = inputValue.indexOf(match, lastIndex+1)
            return lastIndex
        }

        Integer inputLen = inputValue.length()
        List<Integer> validSeparators = []

        // when, comma is identified, see left-right, whether there is anything that resembles a
        // brackets, so that we know we ara inside one
        separatorsPos.each {Integer sepPos->
            // move to left
            def isValid = any { {sepPos}
                // default flags
                def toLeftValid = false
                def toRightValid = false
                def stopLeftCheck = false
                def stopRightCheck = false

                for (Integer i = 1; (sepPos - i) >= 0 || (inputLen - i) >= 0; i++)
                {
                    def toLeft = sepPos - i
                    def toRight = sepPos + i

                    // if (toLeft >= 0) {System.out.println("To left: ${inputValue.charAt(toLeft)}")}
                    // if (toRight < inputLen) {System.out.println("To right: ${inputValue.charAt(toRight)}")}

                    // check to the left
                    if (toLeft >= 0 && !stopLeftCheck)
                    {
                        // OK if `right bracket` found - can stop checking
                        // BAD if `left bracket` found - can quit entire procedure
                        def toLeftChar = inputValue.charAt(toLeft)
                        if (toLeftChar == ')'.toCharacter())
                        {
                            toLeftValid = true
                            stopLeftCheck = true
                        } else if (toLeftChar == '('.toCharacter())
                        {
                            return false
                        } else {
                            toLeftValid = true
                        }
                    }

                    // check to the right
                    if (toRight < inputLen && !stopRightCheck)
                    {
                        // OK if `left bracket` found - can stop checking
                        // BAD if `right bracket` found - can quit entire procedure
                        def toRightChar = inputValue.charAt(toRight)
                        if (toRightChar == '('.toCharacter())
                        {
                            toRightValid = true
                            stopRightCheck = true
                        } else if (toRightChar == ')'.toCharacter())
                        {
                            return false
                        } else {
                            toRightValid = true
                        }
                    }
                    // shortcut
                    if (stopLeftCheck && toLeftValid && stopRightCheck && toRightValid) return true
                }
                return toLeftValid && toRightValid
            }
            if (isValid) validSeparators.add(sepPos)
        }

        // return single array if no separators found
        if (validSeparators.empty) return [inputValue]

        // try to separate using existing commas and evaluate brackets, to the left and to the right
        // count brackets to the left and right
        def sepsToRemove = []
        for (Integer sep in validSeparators)
        {
            def leftPart = inputValue.substring(0, sep)
            def rightPart = inputValue.substring(sep + 1, inputValue.length())
            def bracketsOnLeft1 = leftPart.count('(')
            def bracketsOnLeft2 = leftPart.count(')')
            def bracketsOnRight1 = rightPart.count('(')
            def bracketsOnRight2 = rightPart.count(')')
            if (bracketsOnLeft1 == bracketsOnLeft2 && bracketsOnRight1 == bracketsOnRight2) continue
            sepsToRemove.add(sep)
        }
        // remove separators that look suspicious
        for (Integer sep in sepsToRemove) {
            validSeparators.removeIf {it->return it == sep}
        }

        // add zero as first separator - to make the cycle beneath more usable
        validSeparators.add(inputLen)

        def res = []
        Integer start = 0
        for (Integer sep in validSeparators)
        {
            res.add(inputValue.substring(start, sep))
            // move start
            start = sep + 1
        }

        return res
    }

    public static Object parseId(Object val)
    {
        if (val.toString().isInteger())
        {
            return Integer.parseInt(val.toString())
        } else if (val.toString().isDouble())
        {
            return Double.parseDouble(val.toString())
        } else {
            return val
        }
    }

    /*QUERY HELPERS*/
    static String calcPagedQuery(String query, Integer pageIndex, Integer pageSize)
    {
        return SqlExecutor.calcPagedQuery(query, pageIndex, pageSize)
    }

    static String calcTotalQuery(String query)
    {
        return SqlExecutor.calcTotalQuery(query)
    }

    static HashMap<String, Integer> paginationInfo(clLoadTotal, String query, Integer pageIndex, Integer pageSize)
    {
        return SqlExecutor.paginationInfo(clLoadTotal, query, pageIndex, pageSize)
    }

    static HashMap<String, Object> executeQuery(
            Connection conn,
            Logger logger,
            String query,
            Integer pageIndex = 1,
            Integer pageSize = 20)
    {
        return SqlExecutor.executeQuery(conn, logger,query, pageIndex, pageSize)
    }
}