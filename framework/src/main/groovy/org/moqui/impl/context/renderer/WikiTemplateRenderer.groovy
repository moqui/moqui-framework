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

import org.eclipse.mylyn.wikitext.confluence.core.ConfluenceLanguage
import org.eclipse.mylyn.wikitext.core.parser.MarkupParser
import org.eclipse.mylyn.wikitext.core.parser.builder.HtmlDocumentBuilder
import org.eclipse.mylyn.wikitext.mediawiki.core.MediaWikiLanguage
import org.eclipse.mylyn.wikitext.textile.core.TextileLanguage
import org.eclipse.mylyn.wikitext.tracwiki.core.TracWikiLanguage
import org.eclipse.mylyn.wikitext.twiki.core.TWikiLanguage

import org.moqui.BaseException
import org.moqui.context.Cache
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ResourceReference
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.screen.ScreenRenderImpl

class WikiTemplateRenderer implements TemplateRenderer {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WikiTemplateRenderer.class)

    protected ExecutionContextFactoryImpl ecfi
    protected Cache templateWikiLocationCache

    WikiTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.templateWikiLocationCache = ecfi.cacheFacade.getCache("resource.wiki.location")
        return this
    }

    void render(String location, Writer writer) {
        ResourceReference rr = ecfi.resourceFacade.getLocationReference(location)
        String wikiText = templateWikiLocationCache.getIfCurrent(location, rr != null ? rr.getLastModified() : 0L)
        if (wikiText) {
            writer.write(wikiText)
            return
        }

        String sourceText = ecfi.resourceFacade.getLocationText(location, false)
        if (!sourceText) {
            logger.warn("In wiki template render got no text from location ${location}")
            return
        }

        StringWriter localWriter = new StringWriter()
        HtmlDocumentBuilder builder = new HtmlDocumentBuilder(localWriter)
        // avoid the <html> and <body> tags
        builder.setEmitAsDocument(false)
        // if we're in the context of a screen render, use it's URL for the base
        ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getEci().getContext().getByString("sri")
        if (sri != null) builder.setBase(sri.getBaseLinkUri())

        MarkupParser parser
        if (location.endsWith(".cwiki") || location.endsWith(".confluence")) parser = new MarkupParser(new ConfluenceLanguage())
        else if (location.endsWith(".mediawiki")) parser = new MarkupParser(new MediaWikiLanguage())
        else if (location.endsWith(".textile")) parser = new MarkupParser(new TextileLanguage())
        else if (location.endsWith(".tracwiki")) parser = new MarkupParser(new TracWikiLanguage())
        else if (location.endsWith(".twiki")) parser = new MarkupParser(new TWikiLanguage())
        else throw new BaseException("Extension not supported for wiki rendering for location ${location}")

        parser.setBuilder(builder)
        parser.parse(sourceText)

        wikiText = localWriter.toString()
        if (wikiText) {
            templateWikiLocationCache.put(location, wikiText)
            writer.write(wikiText)
        }
    }

    String stripTemplateExtension(String fileName) {
        if (fileName.contains(".cwiki")) return fileName.replace(".cwiki", "")
        else if (fileName.contains(".confluence")) return fileName.replace(".confluence", "")
        else if (fileName.contains(".mediawiki")) return fileName.replace(".mediawiki", "")
        else if (fileName.contains(".textile")) return fileName.replace(".textile", "")
        else if (fileName.contains(".tracwiki")) return fileName.replace(".tracwiki", "")
        else if (fileName.contains(".twiki")) return fileName.replace(".twiki", "")
        else return fileName
    }

    void destroy() { }
}
