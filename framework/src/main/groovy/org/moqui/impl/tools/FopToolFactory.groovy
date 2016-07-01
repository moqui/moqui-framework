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
package org.moqui.impl.tools

import groovy.transform.CompileStatic
import org.apache.fop.apps.*
import org.apache.fop.apps.io.ResourceResolverFactory
import org.apache.xmlgraphics.io.Resource
import org.apache.xmlgraphics.io.ResourceResolver
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ResourceReference
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Apache FOP tool factory to get a org.xml.sax.ContentHandler instance to write to an OutputStream with the given
 * contentType (like "application/pdf").
 */
@CompileStatic
class FopToolFactory implements ToolFactory<org.xml.sax.ContentHandler> {
    protected final static Logger logger = LoggerFactory.getLogger(FopToolFactory.class)
    final static String TOOL_NAME = "FOP"

    protected ExecutionContextFactory ecf = null

    protected FopFactory internalFopFactory = null

    /** Default empty constructor */
    FopToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf

        URI baseURI = ecf.resource.getLocationReference(ecf.runtimePath + "/conf").getUri()
        ResourceResolver resolver = new LocalResourceResolver(ecf, ResourceResolverFactory.createDefaultResourceResolver())
        FopConfParser parser = new FopConfParser(ecf.resource.getLocationStream("classpath://fop.xconf"), baseURI, resolver)
        FopFactoryBuilder builder = parser.getFopFactoryBuilder()
        // Limit the validation for backwards compatibility
        builder.setStrictFOValidation(false)
        internalFopFactory = builder.build()
        // need something like this? internalFopFactory.getFontManager().setResourceResolver(resolver)
    }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) { }

    /** Requires 2 parameters: OutputStream out, String contentType */
    @Override
    org.xml.sax.ContentHandler getInstance(Object... parameters) {
        if (parameters.length != 2) throw new IllegalArgumentException("FOP tool factory requires 2 parameters (OutputStream out, String contentType)")
        OutputStream out
        String contentType
        if (parameters[0] instanceof OutputStream) {
            out = (OutputStream) parameters[0]
        } else {
            throw new IllegalArgumentException("FOP tool factory first parameter must be an OutputStream")
        }
        if (parameters[1] instanceof String) {
            contentType = (String) parameters[1]
        } else {
            throw new IllegalArgumentException("FOP tool factory second parameter must be a String (contentType)")
        }

        FOUserAgent foUserAgent = internalFopFactory.newFOUserAgent()
        // no longer needed? foUserAgent.setURIResolver(new LocalResolver(ecf, foUserAgent.getURIResolver()))
        Fop fop = internalFopFactory.newFop(contentType, foUserAgent, out)
        return fop.getDefaultHandler()
    }

    @Override
    void destroy() {
    }

    ExecutionContextFactory getEcf() { return ecf }

    @CompileStatic
    static class LocalResourceResolver implements ResourceResolver {
        protected ExecutionContextFactory ecf
        protected ResourceResolver defaultResolver

        public LocalResourceResolver(ExecutionContextFactory ecf, ResourceResolver defaultResolver) {
            this.ecf = ecf
            this.defaultResolver = defaultResolver
        }

        public OutputStream getOutputStream(URI uri) throws IOException {
            ResourceReference rr = ecf.getResource().getLocationReference(uri.toASCIIString())
            if (rr != null) {
                OutputStream os = rr.getOutputStream()
                if (os != null) return os
            }

            return defaultResolver?.getOutputStream(uri)
        }

        public Resource getResource(URI uri) throws IOException {
            ResourceReference rr = ecf.getResource().getLocationReference(uri.toASCIIString())
            if (rr != null) {
                InputStream is = rr.openStream()
                if (is != null) return new Resource(is)
            }

            return defaultResolver?.getResource(uri)
        }
    }
}
