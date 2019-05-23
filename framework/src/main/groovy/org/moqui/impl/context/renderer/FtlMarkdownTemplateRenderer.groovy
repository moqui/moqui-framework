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

import freemarker.template.Template
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
class FtlMarkdownTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(FtlMarkdownTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi
    protected Cache<String, Template> templateFtlLocationCache

    FtlMarkdownTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateFtlLocationCache = ecfi.cacheFacade.getCache("resource.ftl.location", String.class, Template.class)
        return this
    }

    void render(String location, Writer writer) {
        boolean hasVersion = location.indexOf("#") > 0
        Template theTemplate = null
        if (!hasVersion) {
            if (templateFtlLocationCache instanceof MCache) {
                MCache<String, Template> mCache = (MCache) templateFtlLocationCache
                ResourceReference rr = ecfi.resourceFacade.getLocationReference(location)
                long lastModified = rr != null ? rr.getLastModified() : 0L
                theTemplate = mCache.get(location, lastModified)
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                theTemplate = templateFtlLocationCache.get(location)
            }
        }
        if (theTemplate == null) theTemplate = makeTemplate(location, hasVersion)
        if (theTemplate == null) throw new BaseArtifactException("Could not find template at ${location}")
        theTemplate.createProcessingEnvironment(ecfi.getEci().contextStack, writer).process()
    }

    protected Template makeTemplate(String location, boolean hasVersion) {
        if (!hasVersion) {
            Template theTemplate = (Template) templateFtlLocationCache.get(location)
            if (theTemplate != null) return theTemplate
        }

        Template newTemplate
        try {
            //ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getExecutionContext().getContext().get("sri")
            // how to set base URL? if (sri != null) builder.setBase(sri.getBaseLinkUri())
            /*
            Markdown4jProcessor markdown4jProcessor = new Markdown4jProcessor()
            String mdText = markdown4jProcessor.process(ecfi.resourceFacade.getLocationText(location, false))
            PegDownProcessor pdp = new PegDownProcessor(MarkdownTemplateRenderer.pegDownOptions)
            String mdText = pdp.markdownToHtml(ecfi.resourceFacade.getLocationText(location, false))
            */

            com.vladsch.flexmark.util.ast.Node document = MarkdownTemplateRenderer.PARSER.parse(ecfi.resourceFacade.getLocationText(location, false))
            String mdText = MarkdownTemplateRenderer.RENDERER.render(document)

            // logger.warn("======== .md.ftl post-markdown text: ${mdText}")

            Reader templateReader = new StringReader(mdText)
            newTemplate = new Template(location, templateReader, ecfi.resourceFacade.ftlTemplateRenderer.getFtlConfiguration())
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing template at [${location}]", e)
        }

        if (!hasVersion && newTemplate != null) templateFtlLocationCache.put(location, newTemplate)
        return newTemplate
    }

    String stripTemplateExtension(String fileName) {
        String stripped = fileName.contains(".md") ? fileName.replace(".md", "") : fileName
        stripped = stripped.contains(".markdown") ? stripped.replace(".markdown", "") : stripped
        return stripped.contains(".ftl") ? stripped.replace(".ftl", "") : stripped
    }

    void destroy() { }
}
