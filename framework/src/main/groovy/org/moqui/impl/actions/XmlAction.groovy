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
package org.moqui.impl.actions

import freemarker.core.Environment
import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper

import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.util.FtlNodeWrapper
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ContextBinding
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class XmlAction {
    protected final static Logger logger = LoggerFactory.getLogger(XmlAction.class)

    /** The Groovy class compiled from the script transformed from the XML actions text using the FTL template. */
    protected final Class groovyClass
    protected final String groovyString
    protected final String location

    XmlAction(ExecutionContextFactoryImpl ecfi, MNode xmlNode, String location) {
        this.location = location
        FtlNodeWrapper ftlNode = FtlNodeWrapper.wrapNode(xmlNode)
        groovyString = makeGroovyString(ecfi, ftlNode, location)
        // if (logger.isTraceEnabled()) logger.trace("Xml Action [${location}] groovyString: ${groovyString}")
        try {
            groovyClass = new GroovyClassLoader(Thread.currentThread().getContextClassLoader())
                    .parseClass(groovyString, StupidUtilities.cleanStringForJavaName(location))
        } catch (Throwable t) {
            groovyClass = null
            logger.error("Error parsing groovy String at [${location}]:\n${writeGroovyWithLines()}\n")
            throw t
        }
    }

    XmlAction(ExecutionContextFactoryImpl ecfi, String xmlText, String location) {
        this.location = location
        FtlNodeWrapper ftlNode
        if (xmlText) {
            ftlNode = FtlNodeWrapper.makeFromText(xmlText)
        } else {
            ftlNode = FtlNodeWrapper.makeFromText(ecfi.resourceFacade.getLocationText(location, false))
        }
        groovyString = makeGroovyString(ecfi, ftlNode, location)
        try {
            groovyClass = new GroovyClassLoader().parseClass(groovyString, StupidUtilities.cleanStringForJavaName(location))
        } catch (Throwable t) {
            groovyClass = null
            logger.error("Error parsing groovy String at [${location}]:\n${writeGroovyWithLines()}\n")
            throw t
        }
    }

    protected static String makeGroovyString(ExecutionContextFactoryImpl ecfi, FtlNodeWrapper ftlNode, String location) {
        // transform XML to groovy
        String groovyText
        try {
            Map root = ["xmlActionsRoot":ftlNode]

            Writer outWriter = new StringWriter()
            Environment env = ecfi.resourceFacade.xmlActionsScriptRunner.getXmlActionsTemplate()
                    .createProcessingEnvironment(root, (Writer) outWriter)
            env.process()

            groovyText = outWriter.toString()
        } catch (Exception e) {
            logger.error("Error reading XML actions from [${location}], text: ${ftlNode.toString()}")
            throw new BaseException("Error reading XML actions from [${location}]", e)
        }

        if (logger.traceEnabled) logger.trace("xml-actions at [${location}] produced groovy script:\n${groovyText}\nFrom ftlNode:${ftlNode}")

        return groovyText
    }

    /** Run the XML actions in the current context of the ExecutionContext */
    Object run(ExecutionContext ec) {
        if (!groovyClass) throw new IllegalStateException("No Groovy class in place for XML actions, look earlier in log for the error in init")

        if (logger.isDebugEnabled()) logger.debug("Running groovy script: \n${writeGroovyWithLines()}\n")

        Script script = InvokerHelper.createScript(groovyClass, new ContextBinding(ec.context))
        try {
            Object result = script.run()
            return result
        } catch (Throwable t) {
            logger.error("Error running groovy script (${t.toString()}): \n${writeGroovyWithLines()}\n")
            throw t
        }
    }

    String writeGroovyWithLines() {
        StringBuilder groovyWithLines = new StringBuilder()
        int lineNo = 1
        for (String line in groovyString.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n")
        return groovyWithLines.toString()
    }

    boolean checkCondition(ExecutionContext ec) { return run(ec) as boolean }
}
