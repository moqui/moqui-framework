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
package org.moqui.impl.util

import groovy.transform.CompileStatic
import org.apache.commons.validator.routines.CalendarValidator
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/** A Simple SGML parser. Doesn't validate, doesn't support attributes, and has some hacks for easier OFX V1 parsing with
 * the colon separated header. */
@CompileStatic
class SimpleSgmlReader {
    protected final static Logger logger = LoggerFactory.getLogger(SimpleSgmlReader.class)

    final static CalendarValidator calendarValidator = new CalendarValidator()

    protected Map<String, String> headerMap = null
    protected Node rootNode = null

    private String sgml
    private int length = 0
    private int pos = 0

    SimpleSgmlReader(String sgml) {
        this.sgml = sgml
        if (!sgml) return
        this.length = sgml.length()

        // parse the header key/value pairs (one per line, colon separated)
        int firstLt = sgml.indexOf(lt)
        if (firstLt > 0) {
            String headerStr = sgml.substring(0, firstLt).trim()
            headerMap = [:]
            if (headerStr) {
                for (String line in headerStr.split('\\s')) {
                    if (!line) continue
                    int colonIndex = line.indexOf(':')
                    if (colonIndex > 0 && line.length() > (colonIndex + 1)) {
                        String key = line.substring(0, colonIndex).trim()
                        String value = line.substring(colonIndex + 1).trim()
                        headerMap.put(key, value)
                    }
                }
            }
        }

        pos = firstLt
        rootNode = startRoot()
    }

    Map<String, String> getHeader() { return headerMap }
    Node getRoot() { return rootNode }

    static final int slash = ('/' as char) as int
    static final int lt = ('<' as char) as int
    static final int gt = ('>' as char) as int
    protected Node startRoot() {
        // move pos to first char of element name
        pos++
        // find close of tag (>)
        int gtIndex = sgml.indexOf(gt, pos)
        if (gtIndex == -1) return null
        String nodeName = sgml.substring(pos, gtIndex).trim()
        Node rootNode = new Node(null, nodeName)
        pos = gtIndex + 1

        // assume root Node has child nodes (not value)
        int nextLtIndex = sgml.indexOf(lt, pos)
        if (nextLtIndex == -1) return null
        pos = nextLtIndex
        handleChildren(rootNode)

        return rootNode
    }
    protected void handleChildren(Node parent) {
        // child elements
        while (pos < length && sgml.charAt(pos) == lt && sgml.charAt(pos + 1) != slash) {
            startNode(parent)
        }
        // close tag
        if (pos < length && sgml.charAt(pos) == lt && sgml.charAt(pos + 1) == slash) {
            int closeGtIndex = sgml.indexOf(gt, pos + 2)
            if (closeGtIndex > pos) pos = closeGtIndex + 1

            // at this point pos is after the close of the tag, see if there is a next element, will be a sibling
            int nextLtIndex = sgml.indexOf(lt, pos)
            if (nextLtIndex == -1) pos = length else pos = nextLtIndex
        }
    }
    protected void startNode(Node parent) {
        // move pos to first char of element name
        pos++
        // find close of tag (>)
        // TODO: handle self-closing element
        int gtIndex = sgml.indexOf(gt, pos)
        if (gtIndex == -1) return
        String nodeName = sgml.substring(pos, gtIndex).trim()
        Node curNode = new Node(parent, nodeName)
        pos = gtIndex + 1
        skipWhitespace()
        if (pos == length) return

        // find next element or close element, look for element value text
        int ltIndex = sgml.indexOf(lt, pos)
        if (ltIndex == -1) ltIndex = length
        String value = null
        if (ltIndex > pos) {
            value = sgml.substring(pos, ltIndex).trim()
            if (value) {
                value = StringUtilities.decodeFromXml(value)
                curNode.setValue(value)
            }
            pos = ltIndex
        }
        if (pos == length) return
        // if no value element has children, otherwise we've got the value so just return
        if (!value) handleChildren(curNode)
    }

    protected void skipWhitespace() { while (pos < length && Character.isWhitespace(sgml.charAt(pos))) pos++ }

    /** possible formats include: yyyyMMdd (GMT), yyyyMMddHHmmss (GMT), yyyyMMddHHmmss.SSS (GMT), yyyyMMddHHmmss.SSS[-5:EST] */
    static Timestamp parseOfxDateTime(String ofxStr) {
        if (ofxStr.length() <= 18) {
            while (ofxStr.length() < 14) { ofxStr = ofxStr + "0" }
            if (ofxStr.length() < 15) { ofxStr = ofxStr + "." }
            while (ofxStr.length() < 18) { ofxStr = ofxStr + "0" }
            ofxStr = ofxStr + " +0000"
        } else {
            // has a time zone, strip it and create an RFC 822 time zone (like -0500)
            int openBraceIndex = ofxStr.indexOf('[')
            String tzStr = ofxStr.substring(openBraceIndex + 1, ofxStr.indexOf(':', openBraceIndex))
            if (Character.isDigit(tzStr.charAt(0))) tzStr = '+' + tzStr
            if (tzStr.length() == 2) tzStr = tzStr[0] + '0' + tzStr[1]
            String dtStr = ofxStr.substring(0, openBraceIndex)
            while (dtStr.length() < 14) { dtStr = dtStr + "0" }
            if (dtStr.length() < 15) { dtStr = dtStr + "." }
            while (dtStr.length() < 18) { dtStr = dtStr + "0" }
            ofxStr = "${dtStr} ${tzStr}00"
        }
        Calendar cal = calendarValidator.validate(ofxStr, 'yyyyMMddHHmmss.SSS Z', null, null)
        return cal ? new Timestamp(cal.getTimeInMillis()) : null
    }
}
