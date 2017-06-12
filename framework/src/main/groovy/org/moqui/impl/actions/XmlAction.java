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
package org.moqui.impl.actions;

import freemarker.core.Environment;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.moqui.BaseArtifactException;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.MNode;
import org.moqui.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class XmlAction {
    private static final Logger logger = LoggerFactory.getLogger(XmlAction.class);
    private static final boolean isDebugEnabled = logger.isDebugEnabled();

    protected final ExecutionContextFactoryImpl ecfi;
    private final MNode xmlNode;
    protected final String location;
    /** The Groovy class compiled from the script transformed from the XML actions text using the FTL template. */
    private Class groovyClassInternal = null;

    public XmlAction(ExecutionContextFactoryImpl ecfi, MNode xmlNode, String location) {
        this.ecfi = ecfi;
        this.xmlNode = xmlNode;
        this.location = location;
    }

    public XmlAction(ExecutionContextFactoryImpl ecfi, String xmlText, String location) {
        this.ecfi = ecfi;
        this.location = location;
        if (xmlText != null && !xmlText.isEmpty()) {
            xmlNode = MNode.parseText(location, xmlText);
        } else {
            xmlNode = MNode.parseText(location, ecfi.resourceFacade.getLocationText(location, false));
        }
    }

    /** Run the XML actions in the current context of the ExecutionContext */
    public Object run(ExecutionContextImpl eci) {
        Class curClass = getGroovyClass();
        if (curClass == null) throw new IllegalStateException("No Groovy class in place for XML actions, look earlier in log for the error in init");
        if (isDebugEnabled) logger.debug("Running groovy script: \n" + writeGroovyWithLines() + "\n");

        Script script = InvokerHelper.createScript(curClass, eci.contextBindingInternal);
        try {
            return script.run();
        } catch (Throwable t) {
            // NOTE: not logging full stack trace, only needed when lots of threads are running to pin down error (always logged later)
            String tString = t.toString();
            if (!tString.contains("org.eclipse.jetty.io.EofException"))
                logger.error("Error running groovy script (" + t.toString() + "): \n" + writeGroovyWithLines() + "\n");
            throw t;
        }
    }

    public boolean checkCondition(ExecutionContextImpl eci) {
        Object result = run(eci);
        if (result == null) return false;
        return DefaultGroovyMethods.asType(run(eci), Boolean.class);
    }

    // used in tools screens, must be public
    public String writeGroovyWithLines() {
        String groovyString = getGroovyString();
        StringBuilder groovyWithLines = new StringBuilder();
        int lineNo = 1;
        for (String line : groovyString.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n");
        return groovyWithLines.toString();
    }

    public Class getGroovyClass() {
        if (groovyClassInternal != null) return groovyClassInternal;
        return makeGroovyClass();
    }
    protected synchronized Class makeGroovyClass() {
        if (groovyClassInternal != null) return groovyClassInternal;
        String curGroovy = getGroovyString();
        // if (logger.isTraceEnabled()) logger.trace("Xml Action [${location}] groovyString: ${curGroovy}")
        try {
            groovyClassInternal = ecfi.compileGroovy(curGroovy, StringUtilities.cleanStringForJavaName(location));
        } catch (Throwable t) {
            groovyClassInternal = null;
            logger.error("Error parsing groovy String at [" + location + "]:\n" + writeGroovyWithLines() + "\n");
            throw t;
        }
        return groovyClassInternal;
    }

    public String getGroovyString() {
        // transform XML to groovy
        String groovyString;
        try {
            Map<String, Object> root = new HashMap<>(1);
            root.put("xmlActionsRoot", xmlNode);

            Writer outWriter = new StringWriter();
            Environment env = ecfi.resourceFacade.getXmlActionsScriptRunner().getXmlActionsTemplate().createProcessingEnvironment(root, outWriter);
            env.process();

            groovyString = outWriter.toString();
        } catch (Exception e) {
            logger.error("Error reading XML actions from [" + location + "], text: " + xmlNode.toString());
            throw new BaseArtifactException("Error reading XML actions from [" + location + "]", e);
        }

        if (logger.isTraceEnabled()) logger.trace("XML actions at [" + location + "] produced groovy script:\n" + groovyString + "\nFrom xmlNode:" + xmlNode.toString());

        return groovyString;
    }
}
