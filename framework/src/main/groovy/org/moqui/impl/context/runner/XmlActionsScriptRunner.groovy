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
package org.moqui.impl.context.runner

import freemarker.template.Template
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ScriptRunner
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.actions.XmlAction
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

@CompileStatic
class XmlActionsScriptRunner implements ScriptRunner {
    protected final static Logger logger = LoggerFactory.getLogger(XmlActionsScriptRunner.class)

    protected ExecutionContextFactoryImpl ecfi
    protected Cache<String, XmlAction> scriptXmlActionLocationCache
    protected Template xmlActionsTemplate = null

    XmlActionsScriptRunner() { }

    ScriptRunner init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.scriptXmlActionLocationCache = ecfi.cacheFacade.getCache("resource.xml-actions.location", String.class, XmlAction.class)
        return this
    }

    Object run(String location, String method, ExecutionContext ec) {
        XmlAction xa = getXmlActionByLocation(location)
        return xa.run((ExecutionContextImpl) ec)
    }

    void destroy() { }

    XmlAction getXmlActionByLocation(String location) {
        XmlAction xa = (XmlAction) scriptXmlActionLocationCache.get(location)
        if (xa == null) xa = loadXmlAction(location)
        return xa
    }
    protected synchronized XmlAction loadXmlAction(String location) {
        XmlAction xa = (XmlAction) scriptXmlActionLocationCache.get(location)
        if (xa == null) {
            xa = new XmlAction(ecfi, ecfi.resourceFacade.getLocationText(location, false), location)
            scriptXmlActionLocationCache.put(location, xa)
        }
        return xa
    }

    Template getXmlActionsTemplate() {
        if (xmlActionsTemplate == null) makeXmlActionsTemplate()
        return xmlActionsTemplate
    }
    protected synchronized void makeXmlActionsTemplate() {
        if (xmlActionsTemplate != null) return

        String templateLocation = ecfi.confXmlRoot.first("resource-facade").attribute("xml-actions-template-location")
        Template newTemplate = null
        Reader templateReader = null
        try {
            templateReader = new InputStreamReader(ecfi.resourceFacade.getLocationStream(templateLocation))
            newTemplate = new Template(templateLocation, templateReader,
                    ecfi.resourceFacade.ftlTemplateRenderer.getFtlConfiguration())
        } catch (Exception e) {
            logger.error("Error while initializing XMLActions template at [${templateLocation}]", e)
        } finally {
            if (templateReader != null) templateReader.close()
        }
        xmlActionsTemplate = newTemplate
    }
}
