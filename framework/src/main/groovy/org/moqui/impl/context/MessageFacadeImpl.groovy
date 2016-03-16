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
public class MessageFacadeImpl implements MessageFacade {
    protected final static Logger logger = LoggerFactory.getLogger(MessageFacadeImpl.class)

    protected ArrayList<String> messageList = null
    protected ArrayList<String> errorList = null
    protected ArrayList<ValidationError> validationErrorList = null

    protected LinkedList<SavedErrors> savedErrorsStack = null

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
    void addMessage(String message) { if (message) getMessages().add(message) }

    @Override
    List<String> getErrors() {
        if (errorList == null) errorList = new ArrayList<>()
        return errorList
    }
    @Override
    void addError(String error) { if (error) getErrors().add(error) }

    @Override
    List<ValidationError> getValidationErrors() {
        if (validationErrorList == null) validationErrorList = new ArrayList<>()
        return validationErrorList
    }
    @Override
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested) {
        getValidationErrors().add(new ValidationError(form, field, serviceName, message, nested))
    }

    @Override
    boolean hasError() { return (errorList != null && errorList.size() > 0) || (validationErrorList != null && validationErrorList.size() > 0) }
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
    }

    @Override
    void copyMessages(MessageFacade mf) {
        if (mf.getMessages()) getMessages().addAll(mf.getMessages())
        if (mf.getErrors()) getErrors().addAll(mf.getErrors())
        if (mf.getValidationErrors()) getValidationErrors().addAll(mf.getValidationErrors())
    }

    @Override
    void pushErrors() {
        if (savedErrorsStack == null) savedErrorsStack = new LinkedList<SavedErrors>()
        savedErrorsStack.addFirst(new SavedErrors(errorList, validationErrorList))
        errorList = null
        validationErrorList = null
    }
    @Override
    void popErrors() {
        if (!savedErrorsStack) return
        SavedErrors se = savedErrorsStack.removeFirst()
        if (se.errorList) getErrors().addAll(se.errorList)
        if (se.validationErrorList) getValidationErrors().addAll(se.validationErrorList)
    }

    static class SavedErrors {
        List<String> errorList
        List<ValidationError> validationErrorList
        SavedErrors(List<String> errorList, List<ValidationError> validationErrorList) {
            this.errorList = errorList; this.validationErrorList = validationErrorList
        }
    }
}
