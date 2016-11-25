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

import org.moqui.context.ExecutionContextFactory
import org.moqui.resource.ResourceReference
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.jcache.MCache
import org.pegdown.Extensions
import org.pegdown.PegDownProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

class MarkdownTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(MarkdownTemplateRenderer.class)

    // ALL_WITH_OPTIONALS includes SMARTS and QUOTES so XOR them to remove them
    final static int pegDownOptions = Extensions.ALL_WITH_OPTIONALS ^ Extensions.SMARTS ^ Extensions.QUOTES

    protected ExecutionContextFactoryImpl ecfi
    protected Cache<String, String> templateMarkdownLocationCache

    MarkdownTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateMarkdownLocationCache = ecfi.cacheFacade.getCache("resource.markdown.location")
        return this
    }

    void render(String location, Writer writer) {
        String mdText;
        if (templateMarkdownLocationCache instanceof MCache) {
            MCache<String, String> mCache = (MCache) templateMarkdownLocationCache;
            ResourceReference rr = ecfi.resourceFacade.getLocationReference(location);
            long lastModified = rr != null ? rr.getLastModified() : 0L;
            mdText = mCache.get(location, lastModified);
        } else {
            // TODO: doesn't support on the fly reloading without cache expire/clear!
            mdText = templateMarkdownLocationCache.get(location);
        }

        if (mdText) {
            writer.write(mdText)
            return
        }

        String sourceText = ecfi.resourceFacade.getLocationText(location, false)
        if (!sourceText) {
            logger.warn("In Markdown template render got no text from location ${location}")
            return
        }

        //ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getExecutionContext().getContext().get("sri")
        // how to set base URL? if (sri != null) builder.setBase(sri.getBaseLinkUri())

        /*
        Markdown4jProcessor markdown4jProcessor = new Markdown4jProcessor()
        mdText = markdown4jProcessor.process(sourceText)
        */

        PegDownProcessor pdp = new PegDownProcessor(pegDownOptions)
        mdText = pdp.markdownToHtml(sourceText)

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
