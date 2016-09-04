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
import freemarker.core.ParseException;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;
import groovy.transform.CompileStatic;
import org.moqui.BaseException;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ResourceReference;
import org.moqui.context.TemplateRenderer;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.jcache.MCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.*;
import java.util.Locale;

@CompileStatic
public class FtlTemplateRenderer implements TemplateRenderer {
    public static final Version FTL_VERSION = Configuration.VERSION_2_3_25;
    protected static final Logger logger = LoggerFactory.getLogger(FtlTemplateRenderer.class);
    protected ExecutionContextFactoryImpl ecfi;
    private Configuration defaultFtlConfiguration;
    private Cache<String, Template> templateFtlLocationCache;

    public FtlTemplateRenderer() { }

    @SuppressWarnings("unchecked")
    public TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        defaultFtlConfiguration = makeFtlConfiguration(ecfi);
        templateFtlLocationCache = ecfi.getCacheFacade().getCache("resource.ftl.location", String.class, Template.class);
        return this;
    }

    public void render(String location, Writer writer) {
        Template theTemplate = getFtlTemplateByLocation(location);
        try {
            theTemplate.createProcessingEnvironment(ecfi.getExecutionContext().getContext(), writer).process();
        } catch (Exception e) { throw new BaseException("Error rendering template at " + location, e); }
    }

    public String stripTemplateExtension(String fileName) {
        return fileName.contains(".ftl") ? fileName.replace(".ftl", "") : fileName;
    }

    public void destroy() {
    }

    @SuppressWarnings("unchecked")
    public Template getFtlTemplateByLocation(final String location) {
        Template theTemplate;
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
        if (theTemplate == null) theTemplate = makeTemplate(location);
        if (theTemplate == null) throw new IllegalArgumentException("Could not find template at [" + location + "]");
        return theTemplate;
    }

    private Template makeTemplate(final String location) {
        Template theTemplate = templateFtlLocationCache.get(location);
        if (theTemplate != null) return theTemplate;

        Template newTemplate = null;
        Reader templateReader = null;
        try {
            templateReader = new InputStreamReader(ecfi.resourceFacade.getLocationStream(location), "UTF-8");
            newTemplate = new Template(location, templateReader, getFtlConfiguration());
        } catch (Exception e) {
            throw new IllegalArgumentException("Error while initializing template at [" + location + "]", e);
        } finally {
            if (templateReader != null) {
                try { templateReader.close(); }
                catch (Exception e) { throw new BaseException("Error closing template reader", e); }
            }
        }

        //
        templateFtlLocationCache.put(location, newTemplate);
        return newTemplate;
    }

    public Configuration getFtlConfiguration() {
        return defaultFtlConfiguration;
    }

    private static Configuration makeFtlConfiguration(ExecutionContextFactoryImpl ecfi) {
        Configuration newConfig = new MoquiConfiguration(FTL_VERSION, ecfi);
        BeansWrapper defaultWrapper = new BeansWrapperBuilder(FTL_VERSION).build();
        newConfig.setObjectWrapper(defaultWrapper);
        newConfig.setSharedVariable("Static", defaultWrapper.getStaticModels());

        // not needed, using getTemplate override instead: newConfig.setCacheStorage(new NullCacheStorage())
        // not needed, using getTemplate override instead: newConfig.setTemplateUpdateDelay(1)
        // not needed, using getTemplate override instead: newConfig.setTemplateLoader(new MoquiResourceTemplateLoader(ecfi))
        // not needed, using getTemplate override instead: newConfig.setLocalizedLookup(false)

        newConfig.setTemplateExceptionHandler(new MoquiTemplateExceptionHandler());
        newConfig.setWhitespaceStripping(true);
        return newConfig;
    }

    private static class MoquiConfiguration extends Configuration {
        MoquiConfiguration(Version version, ExecutionContextFactoryImpl ecfi) {
            super(version);
            this.ecfi = ecfi;
        }

        @Override
        public Template getTemplate(final String name, Locale locale, Object customLookupCondition, String encoding,
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
            if (theTemplate == null && !ignoreMissing)
                throw new FileNotFoundException("Template [" + name + "] not found.");
            return theTemplate;
        }

        public ExecutionContextFactoryImpl getEcfi() {
            return ecfi;
        }

        public void setEcfi(ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi;
        }

        private ExecutionContextFactoryImpl ecfi;
    }

    private static class MoquiTemplateExceptionHandler implements TemplateExceptionHandler {
        public void handleTemplateException(final TemplateException te, Environment env, Writer out) throws TemplateException {
            try {
                // TODO: encode error, something like: StringUtil.SimpleEncoder simpleEncoder = FreeMarkerWorker.getWrappedObject("simpleEncoder", env);
                // stackTrace = simpleEncoder.encode(stackTrace);
                if (te.getCause() != null) {
                    BaseException.filterStackTrace(te.getCause());
                    logger.error("Error in FTL render", te.getCause());
                    out.write("[FTL Error: " + te.getCause().getMessage() + "]");
                } else {
                    BaseException.filterStackTrace(te);
                    logger.error("Error in FTL render", te);
                    out.write("[FTL Error: " + te.getMessage() + "]");
                }
            } catch (IOException e) {
                throw new TemplateException("Failed to print error message. Cause: " + e, env);
            }

        }

    }
}
