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
package org.moqui;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** BaseException - the base/root exception for all exception classes in Moqui Framework. */
public class BaseException extends RuntimeException {
    public BaseException(String message) { super(message); }
    public BaseException(String message, Throwable nested) { super(message, nested); }
    public BaseException(Throwable nested) { super(nested); }

    @Override public void printStackTrace() { filterStackTrace(this); super.printStackTrace(); }
    @Override public void printStackTrace(PrintStream printStream) { filterStackTrace(this); super.printStackTrace(printStream); }
    @Override public void printStackTrace(PrintWriter printWriter) { filterStackTrace(this); super.printStackTrace(printWriter); }
    @Override public StackTraceElement[] getStackTrace() {
        StackTraceElement[] filteredTrace = filterStackTrace(super.getStackTrace());
        setStackTrace(filteredTrace);
        return filteredTrace;
    }

    public static Throwable filterStackTrace(Throwable t) {
        t.setStackTrace(filterStackTrace(t.getStackTrace()));
        if (t.getCause() != null) filterStackTrace(t.getCause());
        return t;
    }
    public static StackTraceElement[] filterStackTrace(StackTraceElement[] orig) {
        List<StackTraceElement> newList = new ArrayList<>(orig.length);
        for (StackTraceElement ste: orig) {
            String cn = ste.getClassName();
            if (cn.startsWith("freemarker.core.") || cn.startsWith("freemarker.ext.beans.") || cn.startsWith("org.eclipse.jetty.") ||
                    cn.startsWith("java.lang.reflect.") || cn.startsWith("sun.reflect.") ||
                    cn.startsWith("org.codehaus.groovy.") ||  cn.startsWith("groovy.lang.")) {
                continue;
            }
            // if ("renderSingle".equals(ste.getMethodName()) && cn.startsWith("org.moqui.impl.screen.ScreenSection")) continue;
            // if (("internalRender".equals(ste.getMethodName()) || "doActualRender".equals(ste.getMethodName())) && cn.startsWith("org.moqui.impl.screen.ScreenRenderImpl")) continue;
            if (("call".equals(ste.getMethodName()) || "callCurrent".equals(ste.getMethodName())) && ste.getLineNumber() == -1) continue;
            //System.out.println("Adding className: " + cn + ", line: " + ste.getLineNumber());
            newList.add(ste);
        }
        //System.out.println("Called getFilteredStackTrace, orig.length=" + orig.length + ", newList.size()=" + newList.size());
        return newList.toArray(new StackTraceElement[newList.size()]);
    }
}
