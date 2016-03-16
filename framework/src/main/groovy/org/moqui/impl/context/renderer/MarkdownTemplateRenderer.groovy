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

import org.markdown4j.Markdown4jProcessor
import org.moqui.context.Cache
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ResourceReference
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MarkdownTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(MarkdownTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi
    protected Cache templateMarkdownLocationCache

    MarkdownTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateMarkdownLocationCache = ecfi.cacheFacade.getCache("resource.markdown.location")
        return this
    }

    void render(String location, Writer writer) {
        ResourceReference rr = ecfi.resourceFacade.getLocationReference(location)
        String mdText = templateMarkdownLocationCache.getIfCurrent(location, rr != null ? rr.getLastModified() : 0L)
        if (mdText) {
            writer.write(mdText)
            return
        }

        String sourceText = ecfi.resourceFacade.getLocationText(location, false)
        if (!sourceText) {
            logger.warn("In Markdown template render got no text from location ${location}")
            return
        }

        Markdown4jProcessor markdown4jProcessor = new Markdown4jProcessor()
        //ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getExecutionContext().getContext().get("sri")
        // how to set base URL? if (sri != null) builder.setBase(sri.getBaseLinkUri())

        mdText = markdown4jProcessor.process(sourceText)

        if (mdText) {
            templateMarkdownLocationCache.put(location, mdText)
            writer.write(mdText)
        }
    }

    String stripTemplateExtension(String fileName) {
        if (fileName.contains(".md")) return fileName.replace(".md", "")
        else if (fileName.contains(".markdown")) return fileName.replace(".markdown", "")
        else return fileName
    }

    void destroy() { }
}
