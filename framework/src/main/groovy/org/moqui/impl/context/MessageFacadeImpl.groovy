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
import org.moqui.context.ValidationError

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MessageFacadeImpl implements MessageFacade {
    protected final static Logger logger = LoggerFactory.getLogger(MessageFacadeImpl.class)

    private final static List<String> emptyStringList = Collections.unmodifiableList(new ArrayList<String>())
    private final static List<ValidationError> emptyValidationErrorList = Collections.unmodifiableList(new ArrayList<ValidationError>())

    private ArrayList<String> messageList = null
    private ArrayList<String> errorList = null
    private ArrayList<ValidationError> validationErrorList = null
    private boolean hasErrors = false

    private LinkedList<SavedErrors> savedErrorsStack = null

    MessageFacadeImpl() { }

    @Override
    List<String> getMessages() {
        if (messageList == null) messageList = new ArrayList<>()
        return messageList
    }
    String getMessagesString() {
        if (messageList == null) return ""
        StringBuilder messageBuilder = new StringBuilder()
        for (String message in messageList) messageBuilder.append(message).append("\n")
        return messageBuilder.toString()
    }
    void addMessage(String message) {
        if (message) {
            getMessages().add(message)
            logger.info(message)
        }
    }

    @Override
    List<String> getErrors() {
        if (errorList == null) return emptyStringList
        return Collections.unmodifiableList(errorList)
    }
    @Override
    void addError(String error) {
        if (error) {
            if (errorList == null) errorList = new ArrayList<>()
            errorList.add(error)
            logger.error(error)
            hasErrors = true
        }
    }

    @Override
    List<ValidationError> getValidationErrors() {
        if (validationErrorList == null) return emptyValidationErrorList
        return Collections.unmodifiableList(validationErrorList)
    }
    @Override
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested) {
        if (validationErrorList == null) validationErrorList = new ArrayList<>()
        ValidationError ve = new ValidationError(form, field, serviceName, message, nested)
        validationErrorList.add(ve)
        logger.error(ve.getMap().toString())
        hasErrors = true
    }

    @Override
    boolean hasError() { return hasErrors }
    @Override
    String getErrorsString() {
        StringBuilder errorBuilder = new StringBuilder()
        if (errorList != null) for (String errorMessage in errorList) errorBuilder.append(errorMessage).append("\n")
        if (validationErrorList != null) for (ValidationError validationError in validationErrorList)
            errorBuilder.append("${validationError.message} (for field ${validationError.field}${validationError.form ? ' on form ' + validationError.form : ''}${validationError.serviceName ? ' of service ' + validationError.serviceName : ''})").append("\n")
        return errorBuilder.toString()
    }

    @Override
    void clearErrors() {
        if (errorList != null) errorList.clear()
        if (validationErrorList != null) validationErrorList.clear()
        hasErrors = false
    }

    @Override
    void copyMessages(MessageFacade mf) {
        if (mf.getMessages()) getMessages().addAll(mf.getMessages())
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
        if (se.errorList) {
            if (errorList == null) errorList = new ArrayList<>()
            errorList.addAll(se.errorList)
            hasErrors = true
        }
        if (se.validationErrorList) {
            if (validationErrorList == null) validationErrorList = new ArrayList<>()
            validationErrorList.addAll(se.validationErrorList)
            hasErrors = true
        }
    }

    static class SavedErrors {
        List<String> errorList
        List<ValidationError> validationErrorList
        SavedErrors(List<String> errorList, List<ValidationError> validationErrorList) {
            this.errorList = errorList; this.validationErrorList = validationErrorList
        }
    }
}
