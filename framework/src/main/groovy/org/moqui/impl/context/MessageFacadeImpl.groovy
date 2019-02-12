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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.moqui.context.MessageFacade
import org.moqui.context.MessageFacade.MessageInfo
import org.moqui.context.NotificationMessage.NotificationType
import org.moqui.context.ValidationError

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MessageFacadeImpl implements MessageFacade {
    protected final static Logger logger = LoggerFactory.getLogger(MessageFacadeImpl.class)

    private final static List<String> emptyStringList = Collections.unmodifiableList(new ArrayList<String>())
    private final static List<ValidationError> emptyValidationErrorList = Collections.unmodifiableList(new ArrayList<ValidationError>())
    private final static List<MessageInfo> emptyMessageInfoList = Collections.unmodifiableList(new ArrayList<MessageInfo>())

    private ArrayList<MessageInfo> messageList = null
    private ArrayList<String> errorList = null
    private ArrayList<ValidationError> validationErrorList = null
    private ArrayList<MessageInfo> publicMessageList = null
    private boolean hasErrors = false

    private LinkedList<SavedErrors> savedErrorsStack = null

    MessageFacadeImpl() { }

    @Override
    List<String> getMessages() {
        if (messageList == null) return emptyStringList
        ArrayList<String> strList = new ArrayList<>(messageList.size())
        for (int i = 0; i < messageList.size(); i++) strList.add(((MessageInfo) messageList.get(i)).getMessage())
        return strList
    }
    @Override
    List<MessageInfo> getMessageInfos() {
        if (messageList == null) return emptyMessageInfoList
        return Collections.unmodifiableList(messageList)
    }
    @Override
    String getMessagesString() {
        if (messageList == null) return ""
        StringBuilder messageBuilder = new StringBuilder()
        for (MessageInfo message in messageList) messageBuilder.append(message.getMessage()).append("\n")
        return messageBuilder.toString()
    }
    @Override void addMessage(String message) { addMessage(message, info) }
    @Override void addMessage(String message, NotificationType type) { addMessage(message, type?.toString()) }
    @Override
    void addMessage(String message, String type) {
        if (message == null || message.isEmpty()) return
        if (messageList == null) messageList = new ArrayList<>()
        MessageInfo mi = new MessageInfo(message, type)
        messageList.add(mi)
        logger.info(mi.toString())
    }

    @Override void addPublic(String message, NotificationType type) { addPublic(message, type?.toString()) }
    @Override
    void addPublic(String message, String type) {
        if (message == null || message.isEmpty()) return
        if (publicMessageList == null) publicMessageList = new ArrayList<>()
        if (messageList == null) messageList = new ArrayList<>()
        MessageInfo mi = new MessageInfo(message, type)
        publicMessageList.add(mi)
        messageList.add(mi)
        logger.info(mi.toString())
    }

    @Override
    List<String> getPublicMessages() {
        if (publicMessageList == null) return emptyStringList
        ArrayList<String> strList = new ArrayList<>(publicMessageList.size())
        for (int i = 0; i < publicMessageList.size(); i++) strList.add(((MessageInfo) publicMessageList.get(i)).getMessage())
        return strList
    }
    @Override
    List<MessageInfo> getPublicMessageInfos() {
        if (publicMessageList == null) return emptyMessageInfoList
        return Collections.unmodifiableList(publicMessageList)
    }

    @Override
    List<String> getErrors() {
        if (errorList == null) return emptyStringList
        return Collections.unmodifiableList(errorList)
    }
    @Override
    void addError(String error) {
        if (error == null || error.isEmpty()) return
        if (errorList == null) errorList = new ArrayList<>()
        errorList.add(error)
        logger.error(error)
        hasErrors = true
    }

    @Override
    List<ValidationError> getValidationErrors() {
        if (validationErrorList == null) return emptyValidationErrorList
        return Collections.unmodifiableList(validationErrorList)
    }
    @Override
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested) {
        if (message == null || message.isEmpty()) return
        if (validationErrorList == null) validationErrorList = new ArrayList<>()
        ValidationError ve = new ValidationError(form, field, serviceName, message, nested)
        validationErrorList.add(ve)
        logger.error(ve.getMap().toString())
        hasErrors = true
    }
    @Override void addError(ValidationError error) {
        if (error == null) return
        if (validationErrorList == null) validationErrorList = new ArrayList<>()
        validationErrorList.add(error)
        logger.error(error.getMap().toString())
        hasErrors = true
    }

    @Override boolean hasError() { return hasErrors }
    @Override
    String getErrorsString() {
        StringBuilder errorBuilder = new StringBuilder()
        if (errorList != null) for (String errorMessage in errorList) errorBuilder.append(errorMessage).append("\n")
        if (validationErrorList != null) for (ValidationError validationError in validationErrorList) {
            errorBuilder.append(validationError.toStringPretty()).append("\n")
        }
        return errorBuilder.toString()
    }

    @Override
    void clearAll() {
        if (messageList != null) messageList.clear()
        if (publicMessageList != null) publicMessageList.clear()
        clearErrors()
    }
    @Override
    void clearErrors() {
        if (errorList != null) errorList.clear()
        if (validationErrorList != null) validationErrorList.clear()
        hasErrors = false
    }

    void moveErrorsToDangerMessages() {
        if (errorList != null) {
            for (String errMsg : errorList) addMessage(errMsg, danger)
            errorList.clear()
        }
        if (validationErrorList != null) {
            for (ValidationError ve : validationErrorList) addMessage(ve.toStringPretty(), danger)
            validationErrorList.clear()
        }
        hasErrors = false
    }

    @Override
    void copyMessages(MessageFacade mf) {
        if (mf.getMessageInfos()) {
            if (messageList == null) messageList = new ArrayList<>()
            messageList.addAll(mf.getMessageInfos())
        }
        if (mf.getErrors()) {
            if (errorList == null) errorList = new ArrayList<>()
            errorList.addAll(mf.getErrors())
            hasErrors = true
        }
        if (mf.getValidationErrors()) {
            if (validationErrorList == null) validationErrorList = new ArrayList<>()
            validationErrorList.addAll(mf.getValidationErrors())
            hasErrors = true
        }
        if (mf.getPublicMessageInfos()) {
            if (publicMessageList == null) publicMessageList = new ArrayList<>()
            publicMessageList.addAll(mf.getPublicMessageInfos())
        }
    }

    @Override
    void pushErrors() {
        if (savedErrorsStack == null) savedErrorsStack = new LinkedList<SavedErrors>()
        savedErrorsStack.addFirst(new SavedErrors(errorList, validationErrorList))
        errorList = null
        validationErrorList = null
        hasErrors = false
    }
    @Override
    void popErrors() {
        if (savedErrorsStack == null || savedErrorsStack.size() == 0) return
        SavedErrors se = savedErrorsStack.removeFirst()
        if (se.errorList != null && se.errorList.size() > 0) {
            if (errorList == null) errorList = new ArrayList<>()
            errorList.addAll(se.errorList)
            hasErrors = true
        }
        if (se.validationErrorList != null && se.validationErrorList.size() > 0) {
            if (validationErrorList == null) validationErrorList = new ArrayList<>()
            validationErrorList.addAll(se.validationErrorList)
            hasErrors = true
        }
    }

    static class SavedErrors {
        List<String> errorList
        List<ValidationError> validationErrorList
        SavedErrors(List<String> errorList, List<ValidationError> validationErrorList) {
            this.errorList = errorList
            this.validationErrorList = validationErrorList
        }
    }
}
