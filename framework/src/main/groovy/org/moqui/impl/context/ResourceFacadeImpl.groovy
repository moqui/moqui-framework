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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper
import org.moqui.BaseArtifactException
import org.moqui.context.*
import org.moqui.impl.context.reference.BaseResourceReference
import org.moqui.impl.context.renderer.FtlTemplateRenderer
import org.moqui.impl.context.runner.JavaxScriptRunner
import org.moqui.impl.context.runner.XmlActionsScriptRunner
import org.moqui.impl.entity.EntityValueBase
import org.moqui.jcache.MCache
import org.moqui.util.ContextBinding
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import org.moqui.resource.ResourceReference
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.activation.DataSource
import javax.cache.Cache
import javax.jcr.Repository
import javax.jcr.RepositoryFactory
import javax.jcr.Session
import javax.jcr.SimpleCredentials
import javax.mail.util.ByteArrayDataSource

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.xml.transform.Source
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.URIResolver
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.stream.StreamSource
import java.lang.reflect.Method

@CompileStatic
class ResourceFacadeImpl implements ResourceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ResourceFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    final FtlTemplateRenderer ftlTemplateRenderer
    final XmlActionsScriptRunner xmlActionsScriptRunner

    // the groovy Script object is not thread safe, so have one per thread per expression; can be reused as thread is reused
    protected final ThreadLocal<Map<String, Script>> threadScriptByExpression = new ThreadLocal<>()
    protected final Map<String, Class> scriptGroovyExpressionCache = new HashMap<>()

    protected final Cache<String, String> textLocationCache
    protected final Cache<String, ResourceReference> resourceReferenceByLocation

    protected final Map<String, Class> resourceReferenceClasses = new HashMap<>()
    protected final Map<String, TemplateRenderer> templateRenderers = new HashMap<>()
    protected final ArrayList<String> templateRendererExtensions = new ArrayList<>()
    protected final ArrayList<Integer> templateRendererExtensionsDots = new ArrayList<>()
    protected final Map<String, ScriptRunner> scriptRunners = new HashMap<>()
    protected final ScriptEngineManager scriptEngineManager = new ScriptEngineManager()
    protected final ToolFactory<org.xml.sax.ContentHandler> xslFoHandlerFactory

    protected final Map<String, Repository> contentRepositories = new HashMap<>()
    protected final ThreadLocal<Map<String, Session>> contentSessions = new ThreadLocal<Map<String, Session>>()

    ResourceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        ftlTemplateRenderer = new FtlTemplateRenderer()
        ftlTemplateRenderer.init(ecfi)

        xmlActionsScriptRunner = new XmlActionsScriptRunner()
        xmlActionsScriptRunner.init(ecfi)

        textLocationCache = ecfi.cacheFacade.getCache("resource.text.location", String.class, String.class)
        // a plain HashMap is faster and just fine here: scriptGroovyExpressionCache = ecfi.cacheFacade.getCache("resource.groovy.expression")
        resourceReferenceByLocation = ecfi.cacheFacade.getCache("resource.reference.location", String.class, ResourceReference.class)

        MNode resourceFacadeNode = ecfi.confXmlRoot.first("resource-facade")

        // Setup resource reference classes
        for (MNode rrNode in resourceFacadeNode.children("resource-reference")) {
            try {
                Class rrClass = Thread.currentThread().getContextClassLoader().loadClass(rrNode.attribute("class"))
                resourceReferenceClasses.put(rrNode.attribute("scheme"), rrClass)
            } catch (ClassNotFoundException e) {
                logger.info("Class [${rrNode.attribute("class")}] not found (${e.toString()})")
            }
        }

        // Setup template renderers
        for (MNode templateRendererNode in resourceFacadeNode.children("template-renderer")) {
            TemplateRenderer tr = (TemplateRenderer) Thread.currentThread().getContextClassLoader()
                    .loadClass(templateRendererNode.attribute("class")).newInstance()
            templateRenderers.put(templateRendererNode.attribute("extension"), tr.init(ecfi))
        }
        for (String ext in templateRenderers.keySet()) {
            templateRendererExtensions.add(ext)
            templateRendererExtensionsDots.add(ObjectUtilities.countChars(ext, (char) '.'))
        }

        // Setup script runners
        for (MNode scriptRunnerNode in resourceFacadeNode.children("script-runner")) {
            if (scriptRunnerNode.attribute("class")) {
                ScriptRunner sr = (ScriptRunner) Thread.currentThread().getContextClassLoader()
                        .loadClass(scriptRunnerNode.attribute("class")).newInstance()
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr.init(ecfi))
            } else if (scriptRunnerNode.attribute("engine")) {
                ScriptRunner sr = new JavaxScriptRunner(scriptRunnerNode.attribute("engine")).init(ecfi)
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr)
            } else {
                logger.error("Configured script-runner for extension [${scriptRunnerNode.attribute("extension")}] must have either a class or engine attribute and has neither.")
            }
        }

        // Get XSL-FO Handler Factory
        if (resourceFacadeNode.attribute("xsl-fo-handler-factory")) {
            xslFoHandlerFactory = ecfi.getToolFactory(resourceFacadeNode.attribute("xsl-fo-handler-factory"))
            if (xslFoHandlerFactory != null) {
                logger.info("Using xsl-fo-handler-factory ${resourceFacadeNode.attribute("xsl-fo-handler-factory")} (${xslFoHandlerFactory.class.name})")
            } else {
                logger.warn("Could not find xsl-fo-handler-factory with name ${resourceFacadeNode.attribute("xsl-fo-handler-factory")}")
            }
        } else {
            xslFoHandlerFactory = null
        }

        // Setup content repositories
        for (MNode repositoryNode in ecfi.confXmlRoot.first("repository-list").children("repository")) {
            String repoName = repositoryNode.attribute("name")
            Repository repo = null
            Map parameters = new HashMap()
            for (MNode paramNode in repositoryNode.children("init-param"))
                parameters.put(paramNode.attribute("name"), paramNode.attribute("value"))

            try {
                for (RepositoryFactory factory : ServiceLoader.load(RepositoryFactory.class)) {
                    repo = factory.getRepository(parameters)
                    // factory accepted parameters
                    if (repo != null) break
                }
                if (repo != null) {
                    contentRepositories.put(repoName, repo)
                    logger.info("Added JCR Repository ${repoName} of type ${repo.class.name} for workspace ${repositoryNode.attribute("workspace")} using parameters: ${parameters}")
                } else {
                    logger.error("Could not find JCR RepositoryFactory for repository ${repoName} using parameters: ${parameters}")
                }
            } catch (Exception e) {
                logger.error("Error getting JCR Repository ${repositoryNode.attribute("name")}: ${e.toString()}")
            }
        }
    }

    void destroyAllInThread() {
        Map<String, Session> sessionMap = contentSessions.get()
        if (sessionMap) for (Session openSession in sessionMap.values()) openSession.logout()
        contentSessions.remove()
    }

    ExecutionContextFactoryImpl getEcfi() { ecfi }
    Map<String, TemplateRenderer> getTemplateRenderers() { Collections.unmodifiableMap(templateRenderers) }
    TreeSet<String> getTemplateRendererExtensionSet() { new TreeSet(templateRendererExtensions) }

    Repository getContentRepository(String name) { contentRepositories.get(name) }

    /** Get the active JCR Session for the context/thread, making sure it is live, and make one if needed. */
    Session getContentRepositorySession(String name) {
        Map<String, Session> sessionMap = contentSessions.get()
        if (sessionMap == null) {
            sessionMap = new HashMap()
            contentSessions.set(sessionMap)
        }
        Session newSession = sessionMap.get(name)
        if (newSession != null) {
            if (newSession.isLive()) {
                return newSession
            } else {
                sessionMap.remove(name)
                // newSession = null
            }
        }

        Repository rep = contentRepositories[name]
        if (!rep) return null
        MNode repositoryNode = ecfi.confXmlRoot.first("repository-list")
                .first({ MNode it -> it.name == "repository" && it.attribute("name") == name })
        SimpleCredentials credentials = new SimpleCredentials(repositoryNode.attribute("username") ?: "anonymous",
                (repositoryNode.attribute("password") ?: "").toCharArray())
        if (repositoryNode.attribute("workspace")) {
            newSession = rep.login(credentials, repositoryNode.attribute("workspace"))
        } else {
            newSession = rep.login(credentials)
        }

        if (newSession != null) sessionMap.put(name, newSession)
        return newSession
    }

    @Override ResourceReference getLocationReference(String location) {
        if (location == null) return null
        return internalGetReference(getLocationScheme(location), location)
    }
    static String getLocationScheme(String location) {
        String scheme = "file"
        // Q: how to get the scheme for windows? the Java URI class doesn't like spaces, the if we look for the first ":"
        //    it may be a drive letter instead of a scheme/protocol
        // A: ignore colon if only one character before it
        if (location.indexOf(":") > 1) {
            String prefix = location.substring(0, location.indexOf(":"))
            if (!prefix.contains("/") && prefix.length() > 2) scheme = prefix
        }
        return scheme
    }
    @Override ResourceReference getUriReference(URI uri) {
        if (uri == null) return null
        // we care about 2 parts: scheme, path (use full scheme-specific part)
        String scheme = uri.getScheme() ?: "file"
        String ssPart = uri.getSchemeSpecificPart()
        return internalGetReference(scheme, scheme + ":" + ssPart)
    }
    private ResourceReference internalGetReference(String scheme, String location) {
        // version ignored for this call, just strip it
        int hashIdx = location.indexOf("#")
        if (hashIdx > 0) location = location.substring(0, hashIdx)

        ResourceReference cachedRr = resourceReferenceByLocation.get(location)
        if (cachedRr != null) return cachedRr

        Class rrClass = resourceReferenceClasses.get(scheme)
        if (rrClass == null) throw new BaseArtifactException("Prefix (${scheme}) not supported for location ${location}")

        ResourceReference rr = (ResourceReference) rrClass.newInstance()
        if (rr instanceof BaseResourceReference) {
            ((BaseResourceReference) rr).init(location, ecfi)
        } else {
            rr.init(location)
        }
        resourceReferenceByLocation.put(location, rr)
        return rr
    }

    @Override InputStream getLocationStream(String location) {
        int hashIdx = location.indexOf("#")
        String versionName = null
        if (hashIdx > 0) {
            if ((hashIdx+1) < location.length()) versionName = location.substring(hashIdx+1)
            location = location.substring(0, hashIdx)
        }

        ResourceReference rr = getLocationReference(location)
        if (rr == null) return null
        return rr.openStream(versionName)
    }

    @Override String getLocationText(String location, boolean cache) {
        int hashIdx = location.indexOf("#")
        String versionName = (hashIdx > 0 && (hashIdx+1) < location.length()) ? location.substring(hashIdx+1) : null

        ResourceReference textRr = getLocationReference(location)
        if (textRr == null) {
            logger.info("Cound not get resource reference for location [${location}], returning empty location text String")
            return ""
        }
        // don't cache when getting by version
        if (versionName != null) cache = false
        if (cache) {
            String cachedText
            if (textLocationCache instanceof MCache) {
                MCache<String, String> mCache = (MCache) textLocationCache
                // if we have a rr and last modified is newer than the cache entry then throw it out (expire when cached entry
                //     updated time is older/less than rr.lastModified)
                cachedText = (String) mCache.get(location, textRr.getLastModified())
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                cachedText = (String) textLocationCache.get(location)
            }
            if (cachedText != null) return cachedText
        }
        InputStream locStream = textRr.openStream(versionName)
        if (locStream == null) logger.info("Cannot get text, no resource found at location [${location}]")
        String text = ObjectUtilities.getStreamText(locStream)
        if (cache) textLocationCache.put(location, text)
        // logger.warn("==== getLocationText at ${location} version ${versionName} text ${text.length() > 100 ? text.substring(0, 100) : text}")
        return text
    }

    @Override DataSource getLocationDataSource(String location) {
        int hashIdx = location.indexOf("#")
        String versionName = null
        if (hashIdx > 0) {
            if ((hashIdx+1) < location.length()) versionName = location.substring(hashIdx+1)
            location = location.substring(0, hashIdx)
        }

        ResourceReference fileResourceRef = getLocationReference(location)
        TemplateRenderer tr = getTemplateRendererByLocation(fileResourceRef.location)

        String fileName = fileResourceRef.fileName
        // strip template extension(s) to avoid problems with trying to find content types based on them
        String fileContentType = getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName)

        boolean isBinary = ResourceReference.isBinaryContentType(fileContentType)

        if (isBinary) {
            return new ByteArrayDataSource(fileResourceRef.openStream(versionName), fileContentType)
        } else {
            // not a binary object (hopefully), get the text and pass it over
            if (tr != null) {
                // NOTE: version ignored here
                StringWriter sw = new StringWriter()
                tr.render(fileResourceRef.location, sw)
                return new ByteArrayDataSource(sw.toString(), fileContentType)
            } else {
                // no renderer found, just grab the text (cached) and throw it to the writer
                String textLoc = fileResourceRef.location
                if (versionName != null && !versionName.isEmpty()) textLoc = textLoc.concat("#").concat(versionName)
                String text = getLocationText(textLoc, true)
                return new ByteArrayDataSource(text, fileContentType)
            }
        }
    }

    @Override void template(String location, Writer writer) {
        // NOTE: let version fall through to tr.render() and getLocationText()
        TemplateRenderer tr = getTemplateRendererByLocation(location)
        if (tr != null) {
            tr.render(location, writer)
        } else {
            // no renderer found, just grab the text and throw it to the writer
            String text = getLocationText(location, true)
            if (text) writer.write(text)
        }
    }

    static final Set<String> binaryExtensions = new HashSet<>(["png", "jpg", "jpeg", "gif", "pdf", "doc", "docx", "xsl", "xslx"])
    TemplateRenderer getTemplateRendererByLocation(String location) {
        int hashIdx = location.indexOf("#")
        if (hashIdx > 0) location = location.substring(0, hashIdx)

        // match against extension for template renderer, with as many dots that match as possible (most specific match)
        int lastSlashIndex = location.lastIndexOf("/")
        int dotIndex = location.indexOf(".", lastSlashIndex)
        String fullExt = location.substring(dotIndex + 1)
        TemplateRenderer tr = (TemplateRenderer) templateRenderers.get(fullExt)
        if (tr != null || templateRenderers.containsKey(fullExt)) return tr

        int lastDotIndex = location.lastIndexOf(".", lastSlashIndex)
        String lastExt = location.substring(lastDotIndex+ 1)
        if (binaryExtensions.contains(lastExt)) {
            templateRenderers.put(fullExt, null)
            return null
        }

        int mostDots = -1
        int templateRendererExtensionsSize = templateRendererExtensions.size()
        for (int i = 0; i < templateRendererExtensionsSize; i++) {
            String ext = (String) templateRendererExtensions.get(i)
            if (location.endsWith(ext)) {
                int dots = templateRendererExtensionsDots.get(i).intValue()
                if (dots > mostDots) {
                    mostDots = dots
                    tr = (TemplateRenderer) templateRenderers.get(ext)
                }
            }
        }
        // if there is no template renderer for extension remember that
        if (tr == null) {
            // logger.warn("No renderer found for ${location}, exts: ${templateRendererExtensions}\ntemplateRenderers: ${templateRenderers}")
            templateRenderers.put(fullExt, null)
        }
        return tr
    }

    @Override Object script(String location, String method) {
        int hashIdx = location.indexOf("#")
        if (hashIdx > 0) location = location.substring(0, hashIdx)
        // NOTE: version ignored here

        ExecutionContextImpl ec = ecfi.getEci()
        String extension = location.substring(location.lastIndexOf("."))
        ScriptRunner sr = scriptRunners.get(extension)

        if (sr != null) {
            return sr.run(location, method, ec)
        } else {
            // see if the extension is known
            ScriptEngine engine = scriptEngineManager.getEngineByExtension(extension)
            if (engine == null) throw new BaseArtifactException("Cannot run script [${location}], unknown extension (not in Moqui Conf file, and unkown to Java ScriptEngineManager).")
            return JavaxScriptRunner.bindAndRun(location, ec, engine, ecfi.cacheFacade.getCache("resource.script${extension}.location"))
        }
    }
    @Override Object script(String location, String method, Map additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci()
        ContextStack cs = ec.contextStack
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) cs.push(((EntityValueBase) additionalContext).getValueMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return script(location, method)
        } finally {
            if (doPushPop) { cs.pop(); cs.pop() }
        }
    }

    Object setInContext(String field, String from, String value, String defaultValue, String type, String setIfEmpty) {
        def tempValue = getValueFromContext(from, value, defaultValue, type)
        ecfi.getEci().contextStack.put("_tempValue", tempValue)
        if (tempValue || setIfEmpty) expression("${field} = _tempValue", "")

        return tempValue
    }
    Object getValueFromContext(String from, String value, String defaultValue, String type) {
        def tempValue = from ? expression(from, "") : expand(value, "", null, false)
        if (!tempValue && defaultValue) tempValue = expand(defaultValue, "", null, false)
        if (type) tempValue = ObjectUtilities.basicConvert(tempValue, type)
        return tempValue
    }

    @Override boolean condition(String expression, String debugLocation) {
        return conditionInternal(expression, debugLocation, ecfi.getEci())
    }
    protected boolean conditionInternal(String expression, String debugLocation, ExecutionContextImpl ec) {
        if (expression == null || expression.isEmpty()) return false
        try {
            Script script = getGroovyScript(expression, ec)
            Object result = script.run()
            script.setBinding(null)
            return result as boolean
        } catch (Exception e) {
            throw new BaseArtifactException("Error in condition [${expression}] from [${debugLocation}]", e)
        }
    }
    @Override boolean condition(String expression, String debugLocation, Map additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci()
        ContextStack cs = ec.contextStack
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) cs.push(((EntityValueBase) additionalContext).getValueMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return conditionInternal(expression, debugLocation, ec)
        } finally {
            if (doPushPop) { cs.pop(); cs.pop() }
        }
    }

    @Override Object expression(String expression, String debugLocation) {
        return expressionInternal(expression, debugLocation, ecfi.getEci()) }
    protected Object expressionInternal(String expression, String debugLocation, ExecutionContextImpl ec) {
        if (expression == null || expression.isEmpty()) return null
        try {
            Script script = getGroovyScript(expression, ec)
            Object result = script.run()
            script.setBinding(null)
            return result
        } catch (Exception e) {
            throw new BaseArtifactException("Error in field expression [${expression}] from [${debugLocation}]", e)
        }
    }
    @Override Object expression(String expr, String debugLocation, Map additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci()
        ContextStack cs = ec.contextStack
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) cs.push(((EntityValueBase) additionalContext).getValueMap())
                else cs.push(additionalContext)
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }
            return expressionInternal(expr, debugLocation, ec)
        } finally {
            if (doPushPop) { cs.pop(); cs.pop() }
        }
    }


    @Override String expandNoL10n(String inputString, String debugLocation) { return expand(inputString, debugLocation, null, false) }
    @Override String expand(String inputString, String debugLocation) { return expand(inputString, debugLocation, null, true) }
    @Override String expand(String inputString, String debugLocation, Map additionalContext) {
        return expand(inputString, debugLocation, additionalContext, true) }
    @Override String expand(String inputString, String debugLocation, Map additionalContext, boolean localize) {
        if (inputString == null) return ""
        int inputStringLength = inputString.length()
        if (inputStringLength == 0) return ""

        ExecutionContextImpl eci = (ExecutionContextImpl) null
        // localize string before expanding
        if (localize && inputStringLength < 256) {
            eci = ecfi.getEci()
            inputString = eci.l10nFacade.localize(inputString)
        }
        // if no $ then it's a plain String, just return it
        if (!inputString.contains('$')) return inputString

        if (eci == null) eci = ecfi.getEci()
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0
        ContextStack cs = (ContextStack) null
        if (doPushPop) cs = eci.contextStack
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) { cs.push(((EntityValueBase) additionalContext).getValueMap()) }
                else { cs.push(additionalContext) }
                // do another push so writes to the context don't modify the passed in Map
                cs.push()
            }

            String expression = '"""' + inputString + '"""'
            try {
                Script script = getGroovyScript(expression, eci)
                if (script == null) return ""
                Object result = script.run()
                script.setBinding(null)
                return result as String
            } catch (Exception e) {
                throw new BaseArtifactException("Error in string expression [${expression}] from ${debugLocation}", e)
            }
        } finally {
            if (doPushPop) { cs.pop(); cs.pop() }
        }
    }

    Script getGroovyScript(String expression, ExecutionContextImpl eci) {
        ContextBinding curBinding = eci.contextBindingInternal

        Map<String, Script> curScriptByExpr = (Map<String, Script>) threadScriptByExpression.get()
        if (curScriptByExpr == null) {
            curScriptByExpr = new HashMap<String, Script>()
            threadScriptByExpression.set(curScriptByExpr)
        }

        Script script = (Script) curScriptByExpr.get(expression)
        if (script == null) {
            script = InvokerHelper.createScript(getGroovyClass(expression), curBinding)
            curScriptByExpr.put(expression, script)
        } else {
            script.setBinding(curBinding)
        }

        return script
    }
    Class getGroovyClass(String expression) {
        if (expression == null || expression.isEmpty()) return null
        Class groovyClass = (Class) scriptGroovyExpressionCache.get(expression)
        if (groovyClass == null) {
            groovyClass = ecfi.compileGroovy(expression, StringUtilities.getExpressionClassName(expression))
            scriptGroovyExpressionCache.put(expression, groovyClass)
            // logger.warn("class ${groovyClass.getName()} parsed expression ${expression}")
        }
        return groovyClass
    }

    @Override String getContentType(String filename) { return ResourceReference.getContentType(filename) }

    @Override
    Integer xslFoTransform(StreamSource xslFoSrc, StreamSource xsltSrc, OutputStream out, String contentType) {
        if (xslFoHandlerFactory == null) throw new BaseArtifactException("No XSL-FO Handler ToolFactory found (from resource-facade.@xsl-fo-handler-factory)")

        TransformerFactory factory = TransformerFactory.newInstance()
        factory.setURIResolver(new LocalResolver(ecfi, factory.getURIResolver()))

        Transformer transformer = xsltSrc == null ? factory.newTransformer() : factory.newTransformer(xsltSrc)
        transformer.setURIResolver(new LocalResolver(ecfi, transformer.getURIResolver()))

        final org.xml.sax.ContentHandler contentHandler = xslFoHandlerFactory.getInstance(out, contentType)

        // There's a ThreadLocal memory leak in XALANJ, reported in 2005 but still not fixed in 2016
        // The memory it prevent GC depend on the fo file size and the thread pool size. So use a separate thread to workaround.
        // https://issues.apache.org/jira/browse/XALANJ-2195
        BaseArtifactException transformException = null
        ExecutionContextImpl.ThreadPoolRunnable runnable = new ExecutionContextImpl.ThreadPoolRunnable(ecfi.getEci(), {
            try { transformer.transform(xslFoSrc, new SAXResult(contentHandler)) }
            catch (Throwable t) { transformException = new BaseArtifactException("Error transforming XSL-FO to ${contentType}", t) }
        })
        Thread transThread = new Thread(runnable)
        transThread.start()
        transThread.join()
        if (transformException != null) throw transformException

        try {
            Method pcMethod = xslFoHandlerFactory.class.getMethod("getPageCount", org.xml.sax.ContentHandler.class)
            return pcMethod.invoke(xslFoHandlerFactory, contentHandler) as Integer
        } catch (NoSuchMethodException e) {
            if (logger.isDebugEnabled()) logger.debug("xsl-fo transform factory has no getPageCount method, returning null for page count", e)
            return null
        }
    }

    @CompileStatic
    static class LocalResolver implements URIResolver {
        protected ExecutionContextFactoryImpl ecfi
        protected URIResolver defaultResolver

        LocalResolver(ExecutionContextFactoryImpl ecfi, URIResolver defaultResolver) {
            this.ecfi = ecfi
            this.defaultResolver = defaultResolver
        }

        Source resolve(String href, String base) {
            // try plain href
            ResourceReference rr = ecfi.resourceFacade.getLocationReference(href)

            // if href has no colon try base + href
            if (rr == null && href.indexOf(':') < 0) rr = ecfi.resourceFacade.getLocationReference(base + href)

            if (rr != null) {
                URL url = rr.getUrl()
                InputStream is = rr.openStream()
                if (is != null) {
                    if (url != null) {
                        return new StreamSource(is, url.toExternalForm())
                    } else {
                        return new StreamSource(is)
                    }
                }
            }

            return defaultResolver.resolve(href, base)
        }
    }
}
