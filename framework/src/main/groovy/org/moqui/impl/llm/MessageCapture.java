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
package org.moqui.impl.llm;

import org.moqui.context.ExecutionContext;
import org.moqui.context.MessageFacade;
import org.moqui.context.ValidationError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copy the message facade and clear it so a tool result can carry the messages
 * from that call without leaving them on the execution context.
 */
public final class MessageCapture {
    private MessageCapture() { }

    public static final class Snap {
        final List<String> errors;
        final List<Map<String, String>> validationErrors;
        final List<Map<String, String>> messages;
        final List<Map<String, String>> publicMessages;

        Snap(List<String> errors, List<Map<String, String>> validationErrors,
                List<Map<String, String>> messages, List<Map<String, String>> publicMessages) {
            this.errors = errors;
            this.validationErrors = validationErrors;
            this.messages = messages;
            this.publicMessages = publicMessages;
        }

        boolean isEmpty() {
            return errors.isEmpty() && validationErrors.isEmpty() && messages.isEmpty() && publicMessages.isEmpty();
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!errors.isEmpty()) m.put("errors", errors);
            if (!validationErrors.isEmpty()) m.put("validationErrors", validationErrors);
            if (!messages.isEmpty()) m.put("messages", messages);
            if (!publicMessages.isEmpty()) m.put("publicMessages", publicMessages);
            return m;
        }
    }

    static Snap empty() {
        return new Snap(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    /** Copy current messages, then clear the facade. */
    public static Snap take(ExecutionContext ec) {
        if (ec == null || ec.getMessage() == null) return empty();
        MessageFacade mf = ec.getMessage();
        List<String> errors = new ArrayList<>();
        if (mf.getErrors() != null) errors.addAll(mf.getErrors());
        List<Map<String, String>> validation = new ArrayList<>();
        if (mf.getValidationErrors() != null) {
            for (ValidationError ve : mf.getValidationErrors()) {
                if (ve == null) continue;
                Map<String, String> row = new LinkedHashMap<>();
                if (ve.getField() != null) row.put("field", ve.getField());
                if (ve.getForm() != null) row.put("form", ve.getForm());
                if (ve.getServiceName() != null) row.put("serviceName", ve.getServiceName());
                if (ve.getMessage() != null) row.put("message", ve.getMessage());
                validation.add(row);
            }
        }
        List<Map<String, String>> messages = infos(mf.getMessageInfos());
        List<Map<String, String>> publics = infos(mf.getPublicMessageInfos());
        mf.clearAll();
        return new Snap(errors, validation, messages, publics);
    }

    public static void restore(ExecutionContext ec, Snap snap) {
        if (ec == null || ec.getMessage() == null || snap == null || snap.isEmpty()) return;
        MessageFacade mf = ec.getMessage();
        for (Map<String, String> row : snap.messages) {
            if (row.get("message") != null) mf.addMessage(row.get("message"), row.get("type"));
        }
        for (Map<String, String> row : snap.publicMessages) {
            String message = row.get("message");
            if (message == null) continue;
            boolean already = false;
            for (Map<String, String> inner : snap.messages) {
                if (message.equals(inner.get("message"))) { already = true; break; }
            }
            if (!already) mf.addPublic(message, row.get("type"));
        }
        for (String error : snap.errors) {
            if (error != null) mf.addError(error);
        }
        for (Map<String, String> row : snap.validationErrors) {
            mf.addValidationError(row.get("form"), row.get("field"), row.get("serviceName"), row.get("message"), null);
        }
    }

    static void attach(Map<String, Object> result, Snap snap) {
        if (result == null || snap == null || snap.isEmpty()) return;
        result.put("messages", snap.toMap());
    }

    private static List<Map<String, String>> infos(List<MessageFacade.MessageInfo> infos) {
        List<Map<String, String>> out = new ArrayList<>();
        if (infos == null) return out;
        for (MessageFacade.MessageInfo info : infos) {
            if (info == null || info.getMessage() == null) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("type", info.getTypeString());
            row.put("message", info.getMessage());
            out.add(row);
        }
        return out;
    }
}
