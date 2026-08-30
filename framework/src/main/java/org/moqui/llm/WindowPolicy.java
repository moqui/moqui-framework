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

import java.util.LinkedHashMap;
import java.util.Map;

public final class WindowPolicy {
    public int maxMessages = 40;
    public int maxChars = 120_000;
    public boolean keepSystemFirst = true;
    public boolean keepToolPairs = true;
    public boolean includeContext = true;
    /** Applies only to input overflow (context_length_exceeded), not output finish_reason=length. */
    public ContextLimitPolicy onInputOverflow = ContextLimitPolicy.FAIL;

    public enum ContextLimitPolicy { FAIL, TRIM_AND_RETRY_ONCE }

    public WindowPolicy copy() {
        WindowPolicy c = new WindowPolicy();
        c.maxMessages = maxMessages;
        c.maxChars = maxChars;
        c.keepSystemFirst = keepSystemFirst;
        c.keepToolPairs = keepToolPairs;
        c.includeContext = includeContext;
        c.onInputOverflow = onInputOverflow;
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxMessages", maxMessages);
        m.put("maxChars", maxChars);
        m.put("keepSystemFirst", keepSystemFirst);
        m.put("keepToolPairs", keepToolPairs);
        m.put("includeContext", includeContext);
        m.put("onInputOverflow", onInputOverflow != null ? onInputOverflow.name() : ContextLimitPolicy.FAIL.name());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static WindowPolicy fromMap(Map<String, Object> map) {
        WindowPolicy p = new WindowPolicy();
        if (map == null) return p;
        Object v;
        if ((v = map.get("maxMessages")) instanceof Number) p.maxMessages = ((Number) v).intValue();
        if ((v = map.get("maxChars")) instanceof Number) p.maxChars = ((Number) v).intValue();
        if ((v = map.get("keepSystemFirst")) instanceof Boolean) p.keepSystemFirst = (Boolean) v;
        if ((v = map.get("keepToolPairs")) instanceof Boolean) p.keepToolPairs = (Boolean) v;
        if ((v = map.get("includeContext")) instanceof Boolean) p.includeContext = (Boolean) v;
        v = map.get("onInputOverflow");
        if (v != null) {
            try {
                p.onInputOverflow = ContextLimitPolicy.valueOf(v.toString());
            } catch (IllegalArgumentException ignored) {
                p.onInputOverflow = ContextLimitPolicy.FAIL;
            }
        }
        return p;
    }
}
