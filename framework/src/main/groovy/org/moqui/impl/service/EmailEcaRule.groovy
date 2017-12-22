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
package org.moqui.impl.service

import groovy.transform.CompileStatic
import org.apache.commons.io.IOUtils
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.mail.*
import javax.mail.internet.MimeMessage
import java.sql.Timestamp

@CompileStatic
class EmailEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(EmailEcaRule.class)

    protected MNode emecaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null

    EmailEcaRule(ExecutionContextFactoryImpl ecfi, MNode emecaNode, String location) {
        this.emecaNode = emecaNode
        this.location = location

        // prep condition
        if (emecaNode.hasChild("condition") && emecaNode.first("condition").children) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, emecaNode.first("condition").children.get(0), location + ".condition")
        }
        // prep actions
        if (emecaNode.hasChild("actions")) {
            actions = new XmlAction(ecfi, emecaNode.first("actions"), location + ".actions")
        }
    }

    // Node getEmecaNode() { return emecaNode }

    void runIfMatches(MimeMessage message, String emailServerId, ExecutionContextImpl ec) {

        try {
            ec.context.push()

            ec.context.put("emailServerId", emailServerId)
            ec.context.put("message", message)

            Map<String, Object> fields = [:]
            ec.context.put("fields", fields)

            List<String> toList = []
            for (Address addr in message.getRecipients(MimeMessage.RecipientType.TO)) toList.add(addr.toString())
            fields.put("toList", toList)

            List<String> ccList = []
            for (Address addr in message.getRecipients(MimeMessage.RecipientType.CC)) ccList.add(addr.toString())
            fields.put("ccList", ccList)

            List<String> bccList = []
            for (Address addr in message.getRecipients(MimeMessage.RecipientType.BCC)) bccList.add(addr.toString())
            fields.put("bccList", bccList)

            fields.put("from", message.getFrom() ? message.getFrom()[0] : null)
            fields.put("subject", message.getSubject())
            fields.put("sentDate", message.getSentDate() ? new Timestamp(message.getSentDate().getTime()) : null)
            fields.put("receivedDate", message.getReceivedDate() ? new Timestamp(message.getReceivedDate().getTime()) : null)

            ec.context.put("bodyPartList", makeBodyPartList(message))

            Map<String, Object> headers = [:]
            ec.context.put("headers", headers)
            Enumeration<Header> allHeaders = message.getAllHeaders()
            while (allHeaders.hasMoreElements()) {
                Header header = allHeaders.nextElement()
                String headerName = header.name.toLowerCase()
                if (headers.get(headerName)) {
                    Object hi = headers.get(headerName)
                    if (hi instanceof List) { hi.add(header.value) }
                    else { headers.put(headerName, [hi, header.value]) }
                } else {
                    headers.put(headerName, header.value)
                }
            }

            Map<String, Boolean> flags = [:]
            ec.context.put("flags", flags)
            flags.answered = message.isSet(Flags.Flag.ANSWERED)
            flags.deleted = message.isSet(Flags.Flag.DELETED)
            flags.draft = message.isSet(Flags.Flag.DRAFT)
            flags.flagged = message.isSet(Flags.Flag.FLAGGED)
            flags.recent = message.isSet(Flags.Flag.RECENT)
            flags.seen = message.isSet(Flags.Flag.SEEN)

            // run the condition and if passes run the actions
            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            // logger.info("======== EMECA ${emecaNode.attribute("rule-name")} conditionPassed? ${conditionPassed} fields:\n${fields}\nflags: ${flags}\nheaders: ${headers}")
            if (conditionPassed) {
                if (actions) actions.run(ec)
            }
        } finally {
            ec.context.pop()
        }
    }

    static List<Map> makeBodyPartList(Part part) {
        List<Map> bodyPartList = []
        Object content = part.getContent()
        Map bpMap = [contentType:part.getContentType(), filename:part.getFileName(), disposition:part.getDisposition()?.toLowerCase()]
        if (content instanceof CharSequence) {
            bpMap.contentText = content.toString()
            bodyPartList.add(bpMap)
        } else if (content instanceof Multipart) {
            Multipart mpContent = (Multipart) content
            int count = mpContent.getCount()
            for (int i = 0; i < count; i++) {
                BodyPart bp = mpContent.getBodyPart(i)
                bodyPartList.addAll(makeBodyPartList(bp))
            }
        } else if (content instanceof InputStream) {
            InputStream is = (InputStream) content
            bpMap.contentBytes = IOUtils.toByteArray(is)
            bodyPartList.add(bpMap)
        }
        return bodyPartList
    }
}
