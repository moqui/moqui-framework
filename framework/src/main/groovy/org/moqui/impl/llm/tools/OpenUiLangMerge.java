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
package org.moqui.impl.llm.tools;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.impl.llm.LlmConversationImpl;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies a patch of OpenUI Lang statements to the lang already on a canvas, statement by statement by id, so the model can
 * send only what changed. Splits on statement boundaries (not inside strings or brackets), lets a null assignment delete a
 * statement, and strips code fences the model wrapped around the program.
 */
final class OpenUiLangMerge {
    private OpenUiLangMerge() { }

    /** Merge patch statements onto existing OpenUI Lang by statement id (left of first =). */
    static String mergeLang(String existing, String patch) {
        String ex = OpenUiLangMerge.stripLangFences(existing);
        String pa = OpenUiLangMerge.stripLangFences(patch);
        if (pa == null || pa.isBlank()) return ex;
        if (ex == null || ex.isBlank()) return pa;
        LinkedHashMap<String, String> byId = new LinkedHashMap<>();
        for (String stmt : OpenUiLangMerge.splitLangStatements(ex)) {
            String id = OpenUiLangMerge.langStatementId(stmt);
            if (id != null) byId.put(id, stmt);
        }
        for (String stmt : OpenUiLangMerge.splitLangStatements(pa)) {
            String id = OpenUiLangMerge.langStatementId(stmt);
            if (id == null) continue;
            if (OpenUiLangMerge.isLangNullAssign(stmt)) byId.remove(id);
            else byId.put(id, stmt);
        }
        return String.join("\n", byId.values());
    }

    static List<String> splitLangStatements(String input) {
        List<String> stmts = new ArrayList<>();
        if (input == null) return stmts;
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        boolean esc = false;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (inStr) {
                if (c == quote) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; quote = c; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (c == '\n' && depth <= 0) {
                String stmt = input.substring(start, i).trim();
                if (!stmt.isEmpty()) stmts.add(stmt);
                start = i + 1;
            }
        }
        String tail = input.substring(start).trim();
        if (!tail.isEmpty()) stmts.add(tail);
        return stmts;
    }

    static String langStatementId(String stmt) {
        if (stmt == null) return null;
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        boolean esc = false;
        for (int i = 0; i < stmt.length(); i++) {
            char c = stmt.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (inStr) {
                if (c == quote) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; quote = c; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (c == '=' && depth == 0) return stmt.substring(0, i).trim();
        }
        return null;
    }

    static boolean isLangNullAssign(String stmt) {
        if (stmt == null) return false;
        int eq = stmt.indexOf('=');
        if (eq < 0) return false;
        return "null".equals(stmt.substring(eq + 1).trim());
    }

    static String stripLangFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }
}
