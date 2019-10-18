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
package org.moqui.impl.context.renderer;

import freemarker.core.Environment;
import freemarker.core.InvalidReferenceException;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;
import groovy.transform.CompileStatic;

import org.moqui.BaseArtifactException;
import org.moqui.BaseException;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.resource.ResourceReference;
import org.moqui.context.TemplateRenderer;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.jcache.MCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@CompileStatic
public class FtlTemplateRenderer implements TemplateRenderer {
    public static final Version FTL_VERSION = Configuration.VERSION_2_3_29;
    private static final Logger logger = LoggerFactory.getLogger(FtlTemplateRenderer.class);

    protected ExecutionContextFactoryImpl ecfi;
    private Configuration defaultFtlConfiguration;
    private Cache<String, Template> templateFtlLocationCache;

    public FtlTemplateRenderer() { }

    @SuppressWarnings("unchecked")
    public TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        defaultFtlConfiguration = makeFtlConfiguration(ecfi);
        templateFtlLocationCache = ecfi.cacheFacade.getCache("resource.ftl.location", String.class, Template.class);
        return this;
    }

    public void render(String location, Writer writer) {
        Template theTemplate = getFtlTemplateByLocation(location);
        try {
            theTemplate.createProcessingEnvironment(ecfi.getEci().contextStack, writer).process();
        } catch (Exception e) { throw new BaseArtifactException("Error rendering template at " + location, e); }
    }
    public String stripTemplateExtension(String fileName) { return fileName.contains(".ftl") ? fileName.replace(".ftl", "") : fileName; }

    public void destroy() { }

    @SuppressWarnings("unchecked")
    private Template getFtlTemplateByLocation(final String location) {
        boolean hasVersion = location.indexOf("#") > 0;
        Template theTemplate = null;
        if (!hasVersion) {
            if (templateFtlLocationCache instanceof MCache) {
                MCache<String, Template> mCache = (MCache) templateFtlLocationCache;
                ResourceReference rr = ecfi.resourceFacade.getLocationReference(location);
                // if we have a rr and last modified is newer than the cache entry then throw it out (expire when cached entry
                //     updated time is older/less than rr.lastModified)
                long lastModified = rr != null ? rr.getLastModified() : 0L;
                theTemplate = mCache.get(location, lastModified);
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                theTemplate = templateFtlLocationCache.get(location);
            }
        }
        if (theTemplate == null) theTemplate = makeTemplate(location, hasVersion);
        if (theTemplate == null) throw new BaseArtifactException("Could not find template at " + location);
        return theTemplate;
    }

    private Template makeTemplate(final String location, boolean hasVersion) {
        if (!hasVersion) {
            Template theTemplate = templateFtlLocationCache.get(location);
            if (theTemplate != null) return theTemplate;
        }

        Template newTemplate;
        Reader templateReader = null;

        InputStream is = ecfi.resourceFacade.getLocationStream(location);
        if (is == null) throw new BaseArtifactException("Template not found at " + location);

        try {
            templateReader = new InputStreamReader(is, StandardCharsets.UTF_8);
            newTemplate = new Template(location, templateReader, getFtlConfiguration());
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing template at " + location, e);
        } finally {
            if (templateReader != null) {
                try { templateReader.close(); }
                catch (Exception e) { logger.error("Error closing template reader", e); }
            }
        }

        if (!hasVersion) templateFtlLocationCache.put(location, newTemplate);
        return newTemplate;
    }

    public Configuration getFtlConfiguration() { return defaultFtlConfiguration; }

    private static Configuration makeFtlConfiguration(ExecutionContextFactoryImpl ecfi) {
        Configuration newConfig = new MoquiConfiguration(FTL_VERSION, ecfi);
        BeansWrapper defaultWrapper = new BeansWrapperBuilder(FTL_VERSION).build();
        newConfig.setObjectWrapper(defaultWrapper);
        newConfig.setSharedVariable("Static", defaultWrapper.getStaticModels());

        /* not needed, using getTemplate override instead:
        newConfig.setCacheStorage(new NullCacheStorage())
        newConfig.setTemplateUpdateDelay(1)
        newConfig.setTemplateLoader(new MoquiTemplateLoader(ecfi))
        newConfig.setLocalizedLookup(false)
        */
        /*
        String moquiRuntime = System.getProperty("moqui.runtime");
        if (moquiRuntime != null && !moquiRuntime.isEmpty()) {
            File runtimeFile = new File(moquiRuntime);
            try {
                newConfig.setDirectoryForTemplateLoading(runtimeFile);
            } catch (Exception e) {
                logger.error("Error setting FTL template loading directory to " + moquiRuntime, e);
            }
        }
        */
        newConfig.setTemplateExceptionHandler(new MoquiTemplateExceptionHandler());
        newConfig.setLogTemplateExceptions(false);
        newConfig.setWhitespaceStripping(true);
        newConfig.setDefaultEncoding("UTF-8");
        return newConfig;
    }

    private static class MoquiConfiguration extends Configuration {
        private ExecutionContextFactoryImpl ecfi;
        MoquiConfiguration(Version version, ExecutionContextFactoryImpl ecfi) {
            super(version);
            this.ecfi = ecfi;
        }

        @Override
        public Template getTemplate(String name, Locale locale, Object customLookupCondition, String encoding,
                                    boolean parseAsFTL, boolean ignoreMissing) throws IOException {
            //return super.getTemplate(name, locale, encoding, parse)
            // NOTE: doing this because template loading behavior with cache/etc not desired and was having issues
            Template theTemplate;
            if (parseAsFTL) {
                theTemplate = ecfi.resourceFacade.getFtlTemplateRenderer().getFtlTemplateByLocation(name);
            } else {
                String text = ecfi.resourceFacade.getLocationText(name, true);
                theTemplate = Template.getPlainTextTemplate(name, text, this);
            }

            // NOTE: this is the same exception the standard FreeMarker code returns
            if (theTemplate == null && !ignoreMissing) throw new FileNotFoundException("Template " + name + " not found.");
            return theTemplate;
        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
    }

    /*
    private static class MoquiTemplateLoader implements TemplateLoader {
        private ExecutionContextFactoryImpl ecfi;
        MoquiTemplateLoader(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
        @Override public Object findTemplateSource(String name) throws IOException { return ecfi.resourceFacade.getLocationReference(name); }
        @Override public long getLastModified(Object templateSource) { if (templateSource instanceof ResourceReference) { return ((ResourceReference) templateSource).getLastModified(); } else { return 0; } }
        @Override public Reader getReader(Object templateSource, String encoding) throws IOException {
            if (!(templateSource instanceof ResourceReference))
                throw new IllegalArgumentException("Cannot get Reader, templateSource is not a ResourceReference");
            ResourceReference rr = (ResourceReference) templateSource;
            InputStream is = rr.openStream();
            if (is == null) throw new IOException("Template not found at " + rr.getLocation());
            return new InputStreamReader(is);
        }
        @Override public void closeTemplateSource(Object templateSource) throws IOException { }
    }
    */

    private static class MoquiTemplateExceptionHandler implements TemplateExceptionHandler {
        public void handleTemplateException(final TemplateException te, Environment env, Writer out) throws TemplateException {
            try {
                // TODO: encode error, something like: StringUtil.SimpleEncoder simpleEncoder = FreeMarkerWorker.getWrappedObject("simpleEncoder", env);
                // stackTrace = simpleEncoder.encode(stackTrace);
                if (te.getCause() != null) {
                    BaseException.filterStackTrace(te.getCause());
                    logger.error("Error from code called in FTL render", te.getCause());
                    // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                    String causeMsg = te.getCause().getMessage();
                    if (causeMsg == null || causeMsg.isEmpty()) causeMsg = te.getMessage();
                    if (causeMsg == null || causeMsg.isEmpty()) causeMsg = "no message available";
                    out.write("[Template Error: ");
                    out.write(causeMsg);
                    out.write("]");
                } else {
                    // NOTE: if there is not cause it is an exception generated by FreeMarker and not some code called in the template
                    if (te instanceof InvalidReferenceException) {
                        // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                        logger.error("[Template Error: expression '" + te.getBlamedExpressionString() + "' was null or not found (" + te.getTemplateSourceName() + ":" + te.getLineNumber() + "," + te.getColumnNumber() + ")]");
                        out.write("[Template Error]");
                    } else {
                        BaseException.filterStackTrace(te);
                        logger.error("Error from FTL in render", te);
                        // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                        out.write("[Template Error: ");
                        out.write(te.getMessage());
                        out.write("]");
                    }
                }
            } catch (IOException e) {
                throw new TemplateException("Failed to print error message. Cause: " + e, env);
            }
        }
    }
}
