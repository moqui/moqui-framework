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

import java.io.Serializable;
import java.util.List;
import org.moqui.context.NotificationMessage.NotificationType;

/** For user messages including general feedback, errors, and field-specific validation errors. */
public interface MessageFacade {
    NotificationType info = NotificationType.info;
    NotificationType success = NotificationType.success;
    NotificationType warning = NotificationType.warning;
    NotificationType danger = NotificationType.danger;

    /** Immutable List of general (non-error) messages that will be shown to the user. */
    List<String> getMessages();
    /** Immutable List of general (non-error) messages that will be shown to the user. */
    List<MessageInfo> getMessageInfos();
    /** Make a single String with all messages separated by the new-line character.
     * @return String with all messages.
     */
    String getMessagesString();
    /** Add a non-error message for internal user to see, for messages not meant for display on public facing sites and portals.
     * @param message The message to add.
     */
    void addMessage(String message);

    /** Add a message not meant for display on public facing sites and portals. */
    void addMessage(String message, NotificationType type);
    /** A variation on addMessage() where the type is a String instead of NotificationType.
     * @param type String representing one of the NotificationType values: info, success, warning, danger. Defaults to info.
     */
    void addMessage(String message, String type);

    /** Add a message meant for display on public facing sites and portals leaving standard messages and errors for internal
     * applications. Also adds the message like a call to addMessage() for internal and other display so that does not also need to be called. */
    void addPublic(String message, NotificationType type);
    /** A variation on addPublic where the type is a String instead of NotificationType.
     * Also adds the message like a call to addMessage() for internal and other display so that does not also need to be called.
     * @param type String representing one of the NotificationType values: info, success, warning, danger. Defaults to info.
     */
    void addPublic(String message, String type);

    List<String> getPublicMessages();
    List<MessageInfo> getPublicMessageInfos();

    /** Immutable List of error messages that should be shown to internal users. */
    List<String> getErrors();
    /** Add a error message for the user to see.
     * NOTE: system errors not meant for the user should be thrown as exceptions instead.
     * @param error The error message to add
     */
    void addError(String error);

    /** Immutable List of ValidationError objects that should be shown to internal or public users in the context of the
     * fields that triggered the error.
     */
    List<ValidationError> getValidationErrors();
    void addValidationError(String form, String field, String serviceName, String message, Throwable nested);
    void addError(ValidationError error);

    /** See if there is are any errors. Checks both error strings and validation errors. */
    boolean hasError();
    /** Make a single String with all error messages separated by the new-line character.
     * @return String with all error messages.
     */
    String getErrorsString();

    void clearAll();
    void clearErrors();
    void copyMessages(MessageFacade mf);

    /** Save current errors on a stack and clear them */
    void pushErrors();
    /** Remove last pushed errors from the stack and add them to current errors */
    void popErrors();

    class MessageInfo implements Serializable {
        String message;
        NotificationType type;
        public MessageInfo(String message, NotificationType type) {
            this.message = message;
            this.type = type != null ? type : info;
        }
        public MessageInfo(String message, String type) {
            this.message = message;
            if (type != null && !type.isEmpty()) {
                switch (Character.toLowerCase(type.charAt(0))) {
                    case 's': this.type = success; break;
                    case 'w': this.type = warning; break;
                    case 'd': this.type = danger; break;
                    default: this.type = info;
                }
            } else {
                this.type = info;
            }
        }
        public String getMessage() { return message; }
        public NotificationType getType() { return type; }
        public String getTypeString() { return type.toString(); }
        public String toString() { return "[" + type.toString() + "] " + message; }
    }
}
