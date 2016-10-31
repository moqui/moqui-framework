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
import org.moqui.context.ExecutionContext
import org.moqui.util.CollectionUtilities
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Pattern

@CompileStatic
class EdiHandler {
    protected final static Logger logger = LoggerFactory.getLogger(EdiHandler.class)

    protected ExecutionContext ec

    Character segmentTerminator = null
    Character elementSeparator = null
    Character componentDelimiter = null
    char escapeCharacter = '?'
    Character segmentSuffix = '\n'

    protected List<Map<String, Object>> envelope = null
    protected List<Map<String, Object>> body = null
    protected String bodyRootId = null
    protected Set<String> knownSegmentIds = new HashSet<>()
    // FUTURE: load Bots record defs to validate input/output messages: Map<String, List> recordDefs

    protected List<SegmentError> segmentErrors = null

    EdiHandler(ExecutionContext ec) { this.ec = ec }

    EdiHandler setChars(Character segmentTerminator, Character elementSeparator, Character componentDelimiter, Character escapeCharacter) {
        this.segmentTerminator = segmentTerminator ?: ('~' as Character)
        this.elementSeparator = elementSeparator ?: ('*' as Character)
        this.componentDelimiter = componentDelimiter ?: (':' as Character)
        this.escapeCharacter = (escapeCharacter ?: '?') as char
        return this
    }

    // NOTE: common X12 componentDelimiter seems to include ':', '^', '<', '@', etc...
    EdiHandler setX12DefaultChars() { setChars('~' as char, '*' as char, ':' as char, '?' as char); return this }
    EdiHandler setITradeDefaultChars() { setChars('~' as char, '*' as char, '@' as char, '?' as char); return this }
    EdiHandler setEdifactDefaultChars() { setChars('\'' as char, '+' as char, ':' as char, '?' as char); return this }

    /** Run a Groovy script at location to get the nested List/Map file envelope structure (for X12: ISA, GS, and ST
     * segments). The QUERIES and SUBTRANSLATION entries can be removed, will be ignored.
     *
     * These are based on Bots Grammars (http://sourceforge.net/projects/bots/files/grammars/), converted from Python to
     * Groovy List/Map syntax (search/replace '{' to '[' and '}' to ']'), only include the structure List (script should
     * evaluate to or return just the structure List).
     */
    EdiHandler loadEnvelope(String location) {
        envelope = (List<Map<String, Object>>) ec.resource.script(location, null)
        extractSegmentIds(envelope)
        return this
    }

    /** Run a Groovy script at location to get the nested List/Map file structure. The segment(s) in the top-level List
     * should be referenced in the envelope structure, ie this structure will be used under the envelope structure.
     *
     * These are based on Bots Grammars (http://sourceforge.net/projects/bots/files/grammars/), converted from Python to
     * Groovy List/Map syntax (search/replace '{' to '[' and '}' to ']'), only include the structure List (script should
     * evaluate to or return just the structure List).
     */
    EdiHandler loadBody(String location) {
        body = (List<Map<String, Object>>) ec.resource.script(location, null)
        extractSegmentIds(body)
        bodyRootId = body[0].ID
        return this
    }

    protected void extractSegmentIds(List<Map<String, Object>> defList) {
        for (Map<String, Object> defMap in defList) {
            knownSegmentIds.add((String) defMap.ID)
            if (defMap.LEVEL) extractSegmentIds((List<Map<String, Object>>) defMap.LEVEL)
        }
    }

    /** Parse EDI text and return a Map containing a "elements" entry with a List of element values (each may be a String
     * or List<String>) and an entry for each child segment where the key is the segment ID (generally 2 or 3 characters)
     * and the value is a List<Map> where each Map has this same Map structure.
     *
     * If no definition is found for a segment, all text the segment (in original form) is put in the "originalList"
     * (of type List<String>) entry. This is used for partial parsing (envelope only) and then completing the parse with
     * the body structure loaded.
     */
    Map<String, List<Object>> parseText(String ediText) {
        if (envelope == null) throw new IllegalArgumentException("Cannot parse EDI text, envelope must be loaded")
        if (!ediText) throw new IllegalArgumentException("No EDI text passed")

        segmentErrors = []
        determineSeparators(ediText)

        List<String> allSegmentStringList = Arrays.asList(ediText.split(getSegmentRegex()))
        if (allSegmentStringList.size() < 2) throw new IllegalArgumentException("No segments found in EDI text, using segment terminator [${segmentTerminator}]")

        Map<String, List<Object>> rootMap = [:]
        parseSegments(allSegmentStringList, 0, rootMap, envelope)
        return rootMap
    }
    List<SegmentError> getSegmentErrors() { return segmentErrors }

    /** Generate EDI text from the same Map/List structure created from the parse. */
    String generateText(Map<String, List<Object>> rootMap) {
        if (segmentTerminator == null) throw new IllegalArgumentException("No segment terminator specified")
        if (elementSeparator == null) throw new IllegalArgumentException("No element separator specified")
        if (componentDelimiter == null) throw new IllegalArgumentException("No component delimiter specified")

        StringBuilder sb = new StringBuilder()
        generateSegment(rootMap, sb)
        return sb.toString()
    }


    // X12 ISA segment is fixed width, pad fields to width of each element
    Map<String, List<Integer>> segmentElementSizes = [ISA:[3, 2, 10, 2, 10, 2, 15, 2, 15, 6, 4, 1, 5, 9, 1, 1, 1]]
    char paddingChar = '\u00a0'
    Set<String> noEscapeSegments = new HashSet<>(['ISA', 'UNA'])
    protected void generateSegment(Map<String, List<Object>> segmentMap, StringBuilder sb) {
        if (segmentMap.elements) {
            List<Object> elements = segmentMap.elements
            String segmentId = elements[0]
            List<Integer> elementSizes = segmentElementSizes.get(segmentId)
            boolean noEscape = noEscapeSegments.contains(segmentId)

            // all segments should have elements, but root Map will not
            for (int i = 0; i < elements.size(); i++) {
                Object element = elements[i]
                Integer elementSize = elementSizes ? elementSizes[i] : null
                if (element instanceof List) {
                    // composite element, add each component with component delimiter
                    Iterator compIter = element.iterator()
                    while (compIter.hasNext()) {
                        Object curComp = compIter.next()
                        if (curComp != null) sb.append(escape(ObjectUtilities.toPlainString(curComp)))
                        if (compIter.hasNext()) sb.append(componentDelimiter)
                    }
                } else {
                    String elementString = ObjectUtilities.toPlainString(element)
                    if (!noEscape) elementString = escape(elementString)
                    sb.append(elementString)
                    if (elementSize != null) {
                        int curSize = elementString.size()
                        while (curSize < elementSize) { sb.append(paddingChar); curSize++ }
                    }
                }
                // append the element separator, if there is another element
                if (i < (elements.size() - 1)) sb.append(elementSeparator)
            }
            // append segment terminator
            sb.append(segmentTerminator)
            // if there is a segment suffix append that
            if (segmentSuffix) sb.append(segmentSuffix)
        }

        // generate child segments
        for (Map.Entry<String, List<Object>> entry in segmentMap.entrySet()) {
            if (!(entry.value instanceof List)) throw new IllegalArgumentException("Entry value is not a list: ${entry}")
            if (entry.key == "elements") continue
            if (entry.key == "originalList") {
                // also support output of literal child segments from originalList (full segment string except terminator)
                for (Object original in entry.value) sb.append(original).append(segmentTerminator)
            } else {
                // is a child segment
                for (Object childObj in entry.value) {
                    if (childObj instanceof Map) {
                        generateSegment((Map<String, List<Object>>) childObj, sb)
                    } else {
                        // should ALWAYS be a Map at this level, if not blow up
                        throw new Exception("Expected Map for segment, got: ${childObj}")
                    }
                }
            }
        }
    }

    protected void determineSeparators(String ediText) {
        // auto-detect segment/element/component chars (only if not set)
        // useful reference, see: https://mohsinkalam.wordpress.com/delimiters/
        if (ediText.startsWith("ISA")) {
            // X12 message
            if (segmentTerminator == null) segmentTerminator = ediText.charAt(105) as Character
            if (elementSeparator == null) elementSeparator = ediText.charAt(3) as Character
            if (componentDelimiter == null) componentDelimiter = ediText.charAt(104) as Character
        } else if (ediText.startsWith("UNA")) {
            // EDIFACT message
            if (segmentTerminator == null) segmentTerminator = ediText.charAt(8) as Character
            if (elementSeparator == null) elementSeparator = ediText.charAt(4) as Character
            if (componentDelimiter == null) componentDelimiter = ediText.charAt(3) as Character
        }

        if (segmentTerminator == null) throw new IllegalArgumentException("No segment terminator specified or automatically determined")
        if (elementSeparator == null) throw new IllegalArgumentException("No element separator specified or automatically determined")
        if (componentDelimiter == null) throw new IllegalArgumentException("No component delimiter specified or automatically determined")
    }

    /** Internal recursive method for parsing segments */
    protected int parseSegments(List<String> allSegmentStringList, int segmentIndex, Map<String, List<Object>> currentSegment,
                                List<Map<String, Object>> levelDefList) {
        while (segmentIndex < allSegmentStringList.size()) {
            String segmentString = allSegmentStringList.get(segmentIndex).trim()
            String segmentId = getSegmentId(segmentString)
            if (segmentId == null) {
                // this shouldn't generally happen, but may if there is a terminating character at the end of the message (after the last segment separator)
                logger.info("No ID found for segment: ${segmentString}")
                segmentIndex++
                continue
            }
            Map<String, Object> curDefMap = levelDefList.find({ it.ID == segmentId })
            if (curDefMap != null) {
                // NOTE: incremented in parseSegment, returns next segment to process
                segmentIndex = parseSegment(allSegmentStringList, segmentIndex, currentSegment, curDefMap)
            } else if (!knownSegmentIds.contains(segmentId)) {
                if (body) {
                    // TODO: improve this to handle multiple positions, somehow keep track of last tx set start segment (in X12 is ST; is first segment in body)
                    int positionInTxSet = segmentIndex - 2
                    segmentErrors.add(new SegmentError(SegmentErrorType.NOT_DEFINED_IN_TX_SET, segmentIndex,
                            positionInTxSet, segmentId, segmentString))
                    segmentIndex++
                } else {
                    // skip the segment; this is necessary to support partial parsing with envelope only
                    segmentIndex++
                    // save the string in originalList
                    List<Object> originalList = currentSegment.originalList
                    if (originalList == null) {
                        originalList = new ArrayList<>()
                        currentSegment.originalList = originalList
                    }
                    originalList.add(segmentString)
                }
            } else {
                // if segmentId is not in the current levelDefList, return to check against parent
                return segmentIndex
            }
        }
        // this will only happen for the root segment, the final child (trailer segment)
        return segmentIndex
    }

    protected int parseSegment(List<String> allSegmentStringList, int segmentIndex, Map<String, List<Object>> currentSegment,
                               Map<String, Object> curDefMap) {
        String segmentString = allSegmentStringList.get(segmentIndex).trim()
        ArrayList<Object> elements = getSegmentElements(segmentString)

        String segmentId = elements[0]
        // if segmentId is in the current levelDefList add as child to current segment, increment index, recurse
        Map<String, List<Object>> newSegment = [elements:elements] as Map<String, List>
        CollectionUtilities.addToListInMap(segmentId, newSegment, currentSegment)

        int nextSegmentIndex = segmentIndex + 1
        // current segment has children (ie LEVEL entry)? then recurse otherwise just return to handle siblings/parents
        List<Map<String, Object>> curDefLevel = (List<Map<String, Object>>) curDefMap.LEVEL
        if (!curDefLevel && body && curDefMap.ID == bodyRootId) {
            // switch from envelope to body
            curDefLevel = (List<Map<String, Object>>) body[0].LEVEL
        }
        if (curDefLevel) {
            return parseSegments(allSegmentStringList, nextSegmentIndex, newSegment, curDefLevel)
        } else {
            return nextSegmentIndex
        }
    }

    protected String getSegmentId(String segmentString) {
        int separatorIndex = segmentString.indexOf(elementSeparator as String)
        if (separatorIndex > 0) {
            return segmentString.substring(0, separatorIndex)
        } else if (segmentString.size() <= 3) {
            return segmentString
        } else {
            return null
        }
    }
    protected ArrayList<Object> getSegmentElements(String segmentString) {
        List<String> originalElementList = Arrays.asList(segmentString.split(getElementRegex()))
        // split composite elements to components List, unescape elements
        ArrayList<Object> elements = new ArrayList<>(originalElementList.size())
        for (String originalElement in originalElementList) {
            // change non-breaking white space to regular space before trim
            originalElement = originalElement.replaceAll("\\u00a0", " ")
            originalElement = originalElement.trim()
            if (originalElement.length() >= 3 && originalElement.contains(componentDelimiter as String)) {
                String[] componentArray = originalElement.split(getComponentRegex())
                if (componentArray.length == 1) {
                    elements.add(unescape(componentArray[0]))
                } else {
                    ArrayList<String> components = new ArrayList<>(componentArray.length)
                    for (String component in componentArray) components.add(unescape(component.trim()))
                    elements.add(components)
                }
            } else {
                elements.add(unescape(originalElement))
            }
        }

        return elements
    }

    // regex strings have a non-capturing lookahead for the escape character (ie only separate if not escaped)
    protected String getSegmentRegex() { return "(?<!${Pattern.quote(escapeCharacter as String)})${Pattern.quote(segmentTerminator as String)}".toString() }
    protected String getElementRegex() { return "(?<!${Pattern.quote(escapeCharacter as String)})${Pattern.quote(elementSeparator as String)}".toString() }
    protected String getComponentRegex() { return "(?<!${Pattern.quote(escapeCharacter as String)})${Pattern.quote(componentDelimiter as String)}".toString() }

    List<String> splitMessage(String rootHeaderId, String rootTrailerId, String ediText) {
        determineSeparators(ediText)

        List<String> splitStringList = []
        List<String> allSegmentStringList = Arrays.asList(ediText.split(getSegmentRegex()))

        ArrayList<String> curSplitList = null
        for (int i = 0; i < allSegmentStringList.size(); i++) {
            String segmentString = allSegmentStringList.get(i).trim()
            String segId = getSegmentId(segmentString)

            if (rootHeaderId && segId == rootHeaderId && curSplitList) {
                // hit a header without a footer, save what we have so far and start a new split
                splitStringList.add(combineSegments(curSplitList))
                curSplitList = new ArrayList<>()
                curSplitList.add(segmentString)
            } else if (rootTrailerId && segId == rootTrailerId) {
                // hit a trailer, add it to the current split, save the split, clear the current split
                if (curSplitList == null) curSplitList = new ArrayList<>()
                curSplitList.add(segmentString)
                splitStringList.add(combineSegments(curSplitList))
                curSplitList = null
            } else {
                if (curSplitList == null) curSplitList = new ArrayList<>()
                curSplitList.add(segmentString)
            }
        }

        return splitStringList
    }
    String combineSegments(ArrayList<String> segmentStringList) {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < segmentStringList.size(); i++) {
            sb.append(segmentStringList.get(i)).append(segmentTerminator)
            if (segmentSuffix) sb.append(segmentSuffix)
        }
        return sb.toString()
    }

    int countSegments(Map<String, List<Object>> ediMap) {
        int count = 0
        if (ediMap.size() <= 1) return 0
        for (Map.Entry<String, List<Object>> entry in ediMap) {
            if (entry.key == 'elements') continue
            for (Object itemObj in entry.value) {
                if (itemObj instanceof Map) {
                    Map<String, List<Object>> itemMap = (Map<String, List<Object>>) itemObj
                    if (itemMap.size() > 0) count++
                    if (itemMap.size() > 1) count += countSegments(itemMap)
                }
            }
        }
        return count
    }

    protected String escape(String original) {
        if (!original) return ""
        StringBuilder builder = new StringBuilder()
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i)
            if (needsEscape(c)) builder.append(escapeCharacter)
            builder.append(c)
        }
        return builder.toString()
    }
    protected boolean needsEscape(char c) {
        return (c == componentDelimiter || c == elementSeparator || c == escapeCharacter || c == segmentTerminator)
    }
    protected String unescape(String original) {
        StringBuilder builder = new StringBuilder()
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i)
            if (c == escapeCharacter) {
                // skip it and append the next character (next char might be escape character to don't just skip)
                i++
                builder.append(original.charAt(i))
            } else {
                builder.append(c)
            }
        }
        return builder.toString()
    }

    static enum SegmentErrorType { UNRECOGNIZED_SEGMENT_ID, UNEXPECTED, MANDATORY_MISSING, LOOP_OVER_MAX, EXCEEDS_MAXIMUM_USE,
            NOT_DEFINED_IN_TX_SET, NOT_IN_SEQUENCE, ELEMENT_ERRORS }
    /* X12 AK304 Element Error Codes
        1 Unrecognized segment ID
        2 Unexpected segment
        3 Mandatory segment missing
        4 Loop Occurs Over Maximum Times
        5 Segment Exceeds Maximum Use
        6 Segment Not in Defined Transaction Set
        7 Segment Not in Proper Sequence
        8 Segment Has Data Element Errors
     */
    static Map<SegmentErrorType, String> segmentErrorX12Codes = [
            (SegmentErrorType.UNRECOGNIZED_SEGMENT_ID):'1', (SegmentErrorType.UNEXPECTED):'2',
            (SegmentErrorType.MANDATORY_MISSING):'3', (SegmentErrorType.LOOP_OVER_MAX):'4',
            (SegmentErrorType.EXCEEDS_MAXIMUM_USE):'5', (SegmentErrorType.NOT_DEFINED_IN_TX_SET):'6',
            (SegmentErrorType.NOT_IN_SEQUENCE):'7',(SegmentErrorType.ELEMENT_ERRORS):'8']

    static enum ElementErrorType { MANDATORY_MISSING, CONDITIONAL_REQUIRED_MISSING, TOO_MANY, TOO_SHORT, TOO_LONG,
            INVALID_CHAR, INVALID_CODE, INVALID_DATE, INVALID_TIME, EXCLUSION_VIOLATED }
    /* X12 AK403 Element Error Codes
        1 Mandatory data element missing
        2 Conditional required data element missing.
        3 Too many data elements.
        4 Data element too short.
        5 Data element too long.
        6 Invalid character in data element.
        7 Invalid code value.
        8 Invalid Date
        9 Invalid Time
        10 Exclusion Condition Violated
     */
    static Map<ElementErrorType, String> elementErrorX12Codes = [
            (ElementErrorType.MANDATORY_MISSING):'1', (ElementErrorType.CONDITIONAL_REQUIRED_MISSING):'2',
            (ElementErrorType.TOO_MANY):'3', (ElementErrorType.TOO_SHORT):'4',
            (ElementErrorType.TOO_LONG):'5', (ElementErrorType.INVALID_CHAR):'6',
            (ElementErrorType.INVALID_CODE):'7', (ElementErrorType.INVALID_DATE):'8',
            (ElementErrorType.INVALID_TIME):'9', (ElementErrorType.EXCLUSION_VIOLATED):'10']

    static class SegmentError {
        SegmentErrorType errorType
        int segmentIndex
        int positionInTxSet
        String segmentId
        String segmentText
        List<ElementError> elementErrors = []
        SegmentError(SegmentErrorType errorType, int segmentIndex, int positionInTxSet, String segmentId, String segmentText) {
            this.errorType=errorType; this.segmentIndex = segmentIndex; this.positionInTxSet = positionInTxSet
            this.segmentId = segmentId; this.segmentText = segmentText
        }
        /** NOTE: used in mantle EdiServices.produce#X12FunctionalAck */
        Map<String, List> makeAk3() {
            Map<String, List> AK3 = [:]
            AK3.elements = ['AK3', segmentId, positionInTxSet as String, '', segmentErrorX12Codes.get(errorType)]
            if (elementErrors) {
                List<Object> ak4List = []
                AK3.AK4 = ak4List
                for (ElementError elementError in elementErrors) ak4List.add(elementError.makeAk4())
            }
            return AK3
        }
    }
    static class ElementError {
        ElementErrorType errorType
        int elementPosition
        Integer compositePosition
        String elementText
        ElementError(ElementErrorType errorType, int elementPosition, Integer compositePosition, String elementText) {
            this.errorType = errorType; this.elementPosition = elementPosition
            this.compositePosition = compositePosition; this.elementText = elementText
        }
        Map<String, List<Object>> makeAk4() {
            Object position = elementPosition as String
            if (compositePosition) position = [position, compositePosition as String]
            return [elements:['AK4', position, elementErrorX12Codes.get(errorType), elementText]]
        }
    }
}
