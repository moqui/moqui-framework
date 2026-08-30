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
package org.moqui.llm;

import org.moqui.BaseException;

public class LlmException extends BaseException {
    private final LlmFinishReason reason;
    private final int httpStatus;
    private final String profileName;
    private final String conversationId;

    public LlmException(String message) {
        this(message, null, LlmFinishReason.ERROR, 0, null, null);
    }
    public LlmException(String message, Throwable nested) {
        this(message, nested, LlmFinishReason.ERROR, 0, null, null);
    }
    public LlmException(String message, LlmFinishReason reason, int httpStatus, String profileName) {
        this(message, null, reason, httpStatus, profileName, null);
    }
    public LlmException(String message, Throwable nested, LlmFinishReason reason, int httpStatus, String profileName) {
        this(message, nested, reason, httpStatus, profileName, null);
    }
    public LlmException(String message, Throwable nested, LlmFinishReason reason, int httpStatus,
            String profileName, String conversationId) {
        super(message, nested);
        this.reason = reason != null ? reason : LlmFinishReason.ERROR;
        this.httpStatus = httpStatus;
        this.profileName = profileName;
        this.conversationId = conversationId;
    }

    public LlmFinishReason getReason() { return reason; }
    public int getHttpStatus() { return httpStatus; }
    public String getProfileName() { return profileName; }
    public String getConversationId() { return conversationId; }
}
