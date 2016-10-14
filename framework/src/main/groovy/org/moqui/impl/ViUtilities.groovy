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

import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode

import java.nio.charset.Charset
import java.sql.Time
import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalUnit
import java.util.regex.Pattern

import org.w3c.dom.Element

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
@CompileStatic
class ViUtilities {
    static final String removeNonumericCharacters(String inputValue) {
        return inputValue.replaceAll("[^\\d]", "")
    }

    static final String removeCommas(String inputValue) {
        return inputValue.replaceAll(",", ".")
    }
}