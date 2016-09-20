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
package org.moqui.impl.screen

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.ContextStack
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenSection {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenSection.class)

    protected MNode sectionNode
    protected String location

    protected Class conditionClass = null
    protected XmlAction condition = null
    protected XmlAction actions = null
    protected ScreenWidgets widgets = null
    protected ScreenWidgets failWidgets = null

    ScreenSection(ExecutionContextFactoryImpl ecfi, MNode sectionNode, String location) {
        this.sectionNode = sectionNode
        this.location = location

        // prep condition attribute
        String conditionAttr = sectionNode.attribute("condition")
        if (conditionAttr) conditionClass = ecfi.getGroovyClassLoader().parseClass(conditionAttr)

        // prep condition element
        if (sectionNode.first("condition")?.first() != null) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, sectionNode.first("condition").first(), location + ".condition")
        }
        // prep actions
        if (sectionNode.hasChild("actions")) {
            actions = new XmlAction(ecfi, sectionNode.first("actions"), location + ".actions")
            // if (location.contains("FOO")) logger.warn("====== Actions for ${location}: ${actions.writeGroovyWithLines()}")
        }
        // prep widgets
        if (sectionNode.hasChild("widgets")) {
            if (sectionNode.getName() == "screen") {
                MNode widgetsNode = sectionNode.first("widgets")
                MNode screenNode = new MNode("screen", null, null, [widgetsNode], null)
                widgets = new ScreenWidgets(screenNode, location + ".widgets")
            } else {
                widgets = new ScreenWidgets(sectionNode.first("widgets"), location + ".widgets")
            }
        }
        // prep fail-widgets
        if (sectionNode.hasChild("fail-widgets"))
            failWidgets = new ScreenWidgets(sectionNode.first("fail-widgets"), location + ".fail-widgets")
    }

    @CompileStatic
    void render(ScreenRenderImpl sri) {
        ContextStack cs = sri.ec.contextStack
        if (sectionNode.name == "section-iterate") {
            // if nothing to iterate over, all done
            Object list = sri.ec.resourceFacade.expression(sectionNode.attribute("list"), null)
            if (!list) {
                if (logger.traceEnabled) logger.trace("Target list [${list}] is empty, not rendering section-iterate at [${location}]")
                return
            }
            Iterator listIterator = null
            if (list instanceof Iterator) listIterator = (Iterator) list
            else if (list instanceof Map) listIterator = ((Map) list).entrySet().iterator()
            else if (list instanceof Iterable) listIterator = ((Iterable) list).iterator()

            String sectionEntry = (String) sectionNode.attribute("entry")
            String sectionKey = (String) sectionNode.attribute("key")

            // TODO: handle paginate, paginate-size (lower priority...)
            int index = 0
            while (listIterator != null && listIterator.hasNext()) {
                Object entry = listIterator.next()
                cs.push()
                try {
                    cs.put(sectionEntry, (entry instanceof Map.Entry ? entry.getValue() : entry))
                    if (sectionKey && entry instanceof Map.Entry) cs.put(sectionKey, entry.getKey())

                    cs.put("sectionEntryIndex", index)
                    cs.put(sectionEntry + "_index", index)
                    cs.put(sectionEntry + "_has_next", listIterator.hasNext())

                    renderSingle(sri)
                } finally {
                    cs.pop()
                }
                index++
            }
        } else {
            // NOTE: don't push/pop context for normal sections, for root section want to be able to share-scope when it
            // is included by another screen so that fields set will be in context of other screen
            renderSingle(sri)
        }
    }

    @CompileStatic
    protected void renderSingle(ScreenRenderImpl sri) {
        if (logger.traceEnabled) logger.trace("Begin rendering screen section at [${location}]")
        ExecutionContextImpl ec = sri.ec
        boolean conditionPassed = true
        boolean skipActions = sri.sfi.isRenderModeSkipActions(sri.renderMode)
        if (!skipActions) {
            if (condition != null) conditionPassed = condition.checkCondition(ec)
            if (conditionPassed && conditionClass != null) {
                Script script = InvokerHelper.createScript(conditionClass, ec.getContextBinding())
                Object result = script.run()
                conditionPassed = result as boolean
            }
        }

        if (conditionPassed) {
            if (!skipActions && actions != null) actions.run(ec)
            if (widgets != null) {
                // was there an error in the actions? don't try to render the widgets, likely to be more and more errors
                if (ec.message.hasError()) {
                    sri.writer.append(ec.message.getErrorsString())
                } else {
                    // render the widgets
                    widgets.render(sri)
                }
            }
        } else {
            if (failWidgets != null) failWidgets.render(sri)
        }
        if (logger.traceEnabled) logger.trace("End rendering screen section at [${location}]")
    }
}
