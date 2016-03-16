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
package org.moqui.context;

import java.util.List;

/** For user messages including general feedback, errors, and field-specific validation errors. */
public interface MessageFacade {
    /** A freely modifiable List of general (non-error) messages that will be shown to the user. */
    List<String> getMessages();
    /** Make a single String with all messages separated by the new-line character.
     * @return String with all messages.
     */
    String getMessagesString();
    /** Add a non-error message for the user to see.
     * @param message The message to add.
     */
    void addMessage(String message);

    /** A freely modifiable List of error messages that will be shown to the user. */
    List<String> getErrors();
    /** Add a error message for the user to see.
     * NOTE: system errors not meant for the user should be thrown as exceptions instead.
     * @param error The error message to add
     */
    void addError(String error);

    /** A freely modifiable List of ValidationError objects that will be shown to the user in the context of the
     * fields that triggered the error.
     */
    List<ValidationError> getValidationErrors();
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested);

    /** See if there is are any errors. Checks both error strings and validation errors. */
    boolean hasError();
    /** Make a single String with all error messages separated by the new-line character.
     * @return String with all error messages.
     */
    String getErrorsString();

    void clearErrors();
    void copyMessages(MessageFacade mf);

    /** Save current errors on a stack and clear them */
    void pushErrors();
    /** Remove last pushed errors from the stack and add them to current errors */
    void popErrors();
}
