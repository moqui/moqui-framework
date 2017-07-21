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
package org.moqui.util;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simple class for evaluating expressions to get System properties and environment variables by string expansion */
public class SystemBinding extends Binding {
    private final static Logger logger = LoggerFactory.getLogger(SystemBinding.class);
    private final static boolean isTraceEnabled = logger.isTraceEnabled();

    private SystemBinding() { super(); }

    public static String getPropOrEnv(String name) {
        // start with System properties
        String value = System.getProperty(name);
        if (value != null && !value.isEmpty()) return value;
        //  try environment variables
        value = System.getenv(name);
        if (value != null && !value.isEmpty()) return value;

        // no luck? try replacing underscores with dots (dots used for map access in Groovy so need workaround)
        String dotName = null;
        if (name.contains("_")) {
            dotName = name.replace('_', '.');
            value = System.getProperty(dotName);
            if (value != null && !value.isEmpty()) return value;
            value = System.getenv(dotName);
            if (value != null && !value.isEmpty()) return value;
        }
        if (isTraceEnabled) logger.trace("No '" + name + (dotName != null ? "' (or '" + dotName + "')" : "'") +
                " system property or environment variable found, using empty string");
        return "";
    }

    @Override
    public Object getVariable(String name) {
        // NOTE: this code is part of the original Groovy groovy.lang.Binding.getVariable() method and leaving it out
        //     is the reason to override this method:
        //if (result == null && !variables.containsKey(name)) {
        //    throw new MissingPropertyException(name, this.getClass());
        //}
        return getPropOrEnv(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        throw new UnsupportedOperationException("Cannot set a variable with SystemBinding, use System.setProperty()");
        // super.setVariable(name, value);
    }

    @Override
    public boolean hasVariable(String name) {
        // always treat it like the variable exists and is null to change the behavior for variable scope and
        //     declaration, easier in simple scripts
        return true;
    }


    private static SystemBinding defaultBinding = new SystemBinding();
    public static String expand(String value) {
        if (value == null || value.length() == 0) return "";
        if (!value.contains("${")) return value;
        String expression = "\"\"\"" + value + "\"\"\"";
        Class groovyClass = new GroovyClassLoader().parseClass(expression);
        Script script = InvokerHelper.createScript(groovyClass, defaultBinding);
        Object result = script.run();
        if (result == null) return ""; // should never happen, always at least empty String
        return result.toString();
    }
}
