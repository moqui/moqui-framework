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
import javax.mail.internet.MimeUtility
import java.sql.Timestamp

@CompileStatic
class EmailEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(EmailEcaRule.class)

    protected MNode emecaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null
    protected boolean storeAttachment = false

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

        //store attachment attribute
        this.storeAttachment = emecaNode.attribute("store-attachment").toBoolean()
    }

    // Node getEmecaNode() { return emecaNode }

    void runIfMatches(MimeMessage message, String emailServerId, ExecutionContextImpl ec) {

        try {
            // run the condition and if passes run the actions
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

            List<Map> bodyPartList = makeBodyPartList(message)
            ec.context.put("bodyPartList", bodyPartList)

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

            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            // logger.info("======== EMECA ${emecaNode.attribute("rule-name")} conditionPassed? ${conditionPassed} fields:\n${fields}\nflags: ${flags}\nheaders: ${headers}")

            //create message & attachments
            if (conditionPassed) {
                ec.logger.info("[TASK] create#EmailMessage")
                ec.logger.info("fields:\\n${fields}\\nflags: ${flags}\\nheaders: ${headers}")
                Map outMap = ec.serviceFacade.sync().name("create#moqui.basic.email.EmailMessage")
                        .parameters(
                            [
                                sentDate:fields.sentDate,
                                receivedDate:fields.receivedDate,
                                statusId:'ES_RECEIVED',
                                subject:fields.subject,
                                body:bodyPartList[0].contentText,
                                fromAddress:MimeUtility.decodeText(fields.from.toString()),
                                toAddresses:MimeUtility.decodeText(fields.toList?.toString()),
                                ccAddresses:fields.ccList?.toString(),
                                bccAddresses:fields.bccList?.toString(),
                                messageId:message.getMessageID(),
                                emailServerId:emailServerId
                            ]
                        )
                        .disableAuthz().call()

                //push email message id to context
                ec.context.put("emailMessageId", outMap.emailMessageId.toString())

                if (storeAttachment) {
                    //extract content
                    extractAttachment(message, ec, outMap.emailMessageId.toString())
                }

                //run actions
                if (actions) actions.run(ec)
            }
        } finally {
            ec.context.pop()
        }
    }

    static List<Map> makeBodyPartList(Part part) {
        List<Map> bodyPartList = []
        Object content = part.getContent()

        String extractedFileName = null

        try {
            extractedFileName = part.getFileName()
        } catch (Exception ex) {
            return  bodyPartList
        }

        Map bpMap = [
                contentType:part.getContentType(),
                filename:extractedFileName,
                disposition:part.getDisposition()?.toLowerCase()
        ]
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

    void extractAttachment(Part part, ExecutionContextImpl ec, String emailMessageId) {
        Object content = part.getContent()
        if (content instanceof Multipart) {
            Multipart mpContent = (Multipart) content
            int count = mpContent.getCount()
            for (int i = 0; i < count; i++) {
                BodyPart bp = mpContent.getBodyPart(i)
                extractAttachment(bp, ec, emailMessageId)
            }
        } else if (content instanceof InputStream) {
            InputStream is = (InputStream) content
            byte[] result = IOUtils.toByteArray(is)

            //only PDF and JPG and PNG
            def contentTypeSpec = part.getContentType().toLowerCase();
            Boolean doRunExtraction = false;
            String newFileName = null;
            String newFileExtension = null;
            String displayName = null;

            FileNameGenerator fng = new FileNameGenerator(16)

            try {
                /*calculate filename*/
                newFileName = fng.nextString()

                /*decode from possible utf-8*/
                String decodedFileName = MimeUtility.decodeText(part.getFileName())

                /*assign filename a value*/
                List<String> fileNameArr = decodedFileName.tokenize('.')

                if (fileNameArr.size() >= 2) {
                    if (contentTypeSpec.startsWith('application/pdf')) {
                        doRunExtraction = true
                        newFileExtension = 'pdf'
                    } else if (contentTypeSpec.startsWith('image/jpeg')) {
                        doRunExtraction = true
                        newFileExtension = 'jpg'
                    } else if (contentTypeSpec.startsWith('application/zip')) {
                        doRunExtraction = true
                        newFileExtension = 'zip'
                    } else if (contentTypeSpec.startsWith('application/octet-stream')) {
                        doRunExtraction = true
                        newFileExtension = fileNameArr[1]
                    } else if (contentTypeSpec.startsWith('image/png')) {
                        doRunExtraction = true
                        newFileExtension = 'png'
                    } else if (contentTypeSpec.startsWith('application/vnd.ms-excel')) {
                        doRunExtraction = true
                        newFileExtension = 'xls'
                    } else if (contentTypeSpec.startsWith('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')) {
                        doRunExtraction = true
                        newFileExtension = 'xlsx'
                    } else if (contentTypeSpec.startsWith('application/msword')) {
                        doRunExtraction = true
                        newFileExtension = 'doc'
                    } else if (contentTypeSpec.startsWith('application/vnd.openxmlformats-officedocument.wordprocessingml.document')) {
                        doRunExtraction = true
                        newFileExtension = 'docx'
                    }

                    /*calculate display name correctly*/
                    displayName = MimeUtility.decodeText(fileNameArr[0]).replaceAll(' ', '_').replaceAll("[^a-zA-Z0-9_]+","")

                    logger.debug("Display name: ${displayName}, file name: ${newFileName}, extension: ${newFileExtension}, type: ${contentTypeSpec}, do extraction: ${doRunExtraction}")
                } else {
                    logger.warn("Unexpected result of processing file name, proceeding without it.")
                }

            } catch (Exception ex) {
                logger.error("Cannot extract file name, proceeding without it. ${ex.message}")
            }

            if (doRunExtraction) {
                ec.serviceFacade.sync().name("org.moqui.EmailContentServices.create#ContentFromByte")
                        .parameters(
                            [
                                    emailMessageId: emailMessageId,
                                    contentFileByte: result,
                                    filename: newFileName + '.' + newFileExtension,
                                    displayName: displayName + "." + newFileExtension
                            ]
                ).disableAuthz().call()
            }
        }
    }
}
