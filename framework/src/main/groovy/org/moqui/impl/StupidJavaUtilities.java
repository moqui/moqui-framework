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
package org.moqui.impl;

import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.screen.ScreenRenderImpl;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Methods that work better in Java than Groovy, helps with performance and for syntax and language feature reasons */
public class StupidJavaUtilities {
    private final static Logger logger = LoggerFactory.getLogger(StupidJavaUtilities.class);

    /** the Groovy JsonBuilder doesn't handle various Moqui objects very well, ends up trying to access all
     * properties and results in infinite recursion, so need to unwrap and exclude some */
    public static Map<String, Object> unwrapMap(Map<String, Object> sourceMap) {
        Map<String, Object> targetMap = new HashMap<>();
        for (Map.Entry<String, Object> entry: sourceMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            // logger.warn("======== actionsResult - ${entry.key} (${entry.value?.getClass()?.getName()}): ${entry.value}")
            Object unwrapped = unwrap(key, value);
            if (unwrapped != null) targetMap.put(key, unwrapped);
        }
        return targetMap;
    }
    @SuppressWarnings("unchecked")
    public static Object unwrap(String key, Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence || value instanceof Number || value instanceof Date) {
            return value;
        } else if (value instanceof EntityFind || value instanceof ExecutionContextImpl ||
                value instanceof ScreenRenderImpl || value instanceof ContextStack) {
            // intentionally skip, commonly left in context by entity-find XML action
            return null;
        } else if (value instanceof EntityValue) {
            EntityValue ev = (EntityValue) value;
            return ev.getPlainValueMap(0);
        } else if (value instanceof EntityList) {
            EntityList el = (EntityList) value;
            ArrayList<Map> newList = new ArrayList<>();
            int elSize = el.size();
            for (int i = 0; i < elSize; i++) {
                EntityValue ev = el.get(i);
                newList.add(ev.getPlainValueMap(0));
            }
            return newList;
        } else if (value instanceof Collection) {
            Collection valCol = (Collection) value;
            ArrayList<Object> newList = new ArrayList<>(valCol.size());
            for (Object entry: valCol) newList.add(unwrap(key, entry));
            return newList;
        } else if (value instanceof Map) {
            Map<Object, Object> valMap = (Map) value;
            Map<Object, Object> newMap = new HashMap<>(valMap.size());
            for (Map.Entry entry: valMap.entrySet()) newMap.put(entry.getKey(), unwrap(key, entry.getValue()));
            return newMap;
        } else {
            logger.info("In screen actions skipping value from actions block that is not supported; key=" + key + ", type=" + value.getClass().getName() + ", value=" + value);
            return null;
        }
    }
}
