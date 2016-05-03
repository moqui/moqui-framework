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

import com.hazelcast.cache.ICache
import groovy.transform.CompileStatic
import org.eclipse.mylyn.wikitext.confluence.core.ConfluenceLanguage
import org.eclipse.mylyn.wikitext.core.parser.MarkupParser
import org.eclipse.mylyn.wikitext.core.parser.builder.HtmlDocumentBuilder
import org.moqui.context.ResourceReference
import org.moqui.context.TemplateRenderer
import org.moqui.impl.screen.ScreenRenderImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import freemarker.template.Template
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.context.ExecutionContextFactory

import javax.cache.expiry.Duration
import javax.cache.expiry.ExpiryPolicy
import javax.cache.expiry.ModifiedExpiryPolicy

@CompileStatic
class FtlCwikiTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(FtlCwikiTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi

    protected ICache<String, Template> templateFtlLocationCache

    FtlCwikiTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateFtlLocationCache = ecfi.cacheFacade.getCache("resource.ftl.location", String.class, Template.class).unwrap(ICache.class)
        return this
    }

    void render(String location, Writer writer) {
        ResourceReference rr = ecfi.resourceFacade.getLocationReference(location)
        ExpiryPolicy expiryPolicy = rr != null ? new ModifiedExpiryPolicy(new Duration(0L, rr.getLastModified())) : null
        Template theTemplate = (Template) templateFtlLocationCache.get(location, expiryPolicy)
        if (!theTemplate) theTemplate = makeTemplate(location)
        if (!theTemplate) throw new IllegalArgumentException("Could not find template at ${location}")
        theTemplate.createProcessingEnvironment(ecfi.executionContext.context, writer).process()
    }

    protected Template makeTemplate(String location) {
        Template theTemplate = (Template) templateFtlLocationCache.get(location)
        if (theTemplate) return theTemplate

        Template newTemplate
        try {
            StringWriter cwikiWriter = new StringWriter()
            HtmlDocumentBuilder builder = new HtmlDocumentBuilder(cwikiWriter)
            // avoid the <html> and <body> tags
            builder.setEmitAsDocument(false)
            // if we're in the context of a screen render, use it's URL for the base
            ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getEci().getContext().getByString("sri")
            if (sri != null) builder.setBase(sri.getBaseLinkUri())

            MarkupParser parser = new MarkupParser(new ConfluenceLanguage())
            parser.setBuilder(builder)
            parser.parse(ecfi.resourceFacade.getLocationText(location, false))

            Reader templateReader = new StringReader(cwikiWriter.toString())
            newTemplate = new Template(location, templateReader, ecfi.resourceFacade.ftlTemplateRenderer.getFtlConfiguration())
        } catch (Exception e) {
            throw new IllegalArgumentException("Error while initializing template at [${location}]", e)
        }

        if (newTemplate) templateFtlLocationCache.put(location, newTemplate)
        return newTemplate
    }

    String stripTemplateExtension(String fileName) {
        String stripped = fileName.contains(".cwiki") ? fileName.replace(".cwiki", "") : fileName
        return stripped.contains(".ftl") ? stripped.replace(".ftl", "") : stripped
    }

    void destroy() { }
}
