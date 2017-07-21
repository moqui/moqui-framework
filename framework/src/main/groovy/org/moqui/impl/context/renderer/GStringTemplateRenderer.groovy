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
package org.moqui.impl.context.renderer

import groovy.text.GStringTemplateEngine
import groovy.text.Template
import groovy.transform.CompileStatic
import org.moqui.BaseArtifactException
import org.moqui.context.ExecutionContextFactory
import org.moqui.resource.ResourceReference
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.jcache.MCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

@CompileStatic
class GStringTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(GStringTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi
    protected Cache<String, Template> templateGStringLocationCache

    GStringTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateGStringLocationCache = ecfi.cacheFacade.getCache("resource.gstring.location", String.class, Template.class)
        return this
    }

    void render(String location, Writer writer) {
        Template theTemplate = getGStringTemplateByLocation(location)
        Writable writable = theTemplate.make(ecfi.executionContext.context)
        writable.writeTo(writer)
    }

    String stripTemplateExtension(String fileName) {
        return fileName.contains(".gstring") ? fileName.replace(".gstring", "") : fileName
    }

    void destroy() { }

    Template getGStringTemplateByLocation(String location) {
        Template theTemplate;
        if (templateGStringLocationCache instanceof MCache) {
            MCache<String, Template> mCache = (MCache) templateGStringLocationCache;
            ResourceReference rr = ecfi.resourceFacade.getLocationReference(location);
            long lastModified = rr != null ? rr.getLastModified() : 0L;
            theTemplate = mCache.get(location, lastModified);
        } else {
            // TODO: doesn't support on the fly reloading without cache expire/clear!
            theTemplate = templateGStringLocationCache.get(location);
        }
        if (!theTemplate) theTemplate = makeGStringTemplate(location)
        if (!theTemplate) throw new BaseArtifactException("Could not find template at [${location}]")
        return theTemplate
    }
    protected Template makeGStringTemplate(String location) {
        Template theTemplate = (Template) templateGStringLocationCache.get(location)
        if (theTemplate) return theTemplate

        Template newTemplate = null
        Reader templateReader = null
        try {
            templateReader = new InputStreamReader(ecfi.resourceFacade.getLocationStream(location))
            GStringTemplateEngine gste = new GStringTemplateEngine()
            newTemplate = gste.createTemplate(templateReader)
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing template at [${location}]", e)
        } finally {
            if (templateReader != null) templateReader.close()
        }

        if (newTemplate) templateGStringLocationCache.put(location, newTemplate)
        return newTemplate
    }

}
