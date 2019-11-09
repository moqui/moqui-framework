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

import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** This start class implements a ClassLoader and supports loading jars within a jar or war file in order to facilitate
 * an executable war file. To do this it overrides the findResource, findResources, and loadClass methods of the
 * ClassLoader class.
 *
 * The best source for research on the topic seems to be at http://www.jdotsoft.com, with a lot of good comments in the
 * JarClassLoader source file there.
 */
public class MoquiStart {
    // this default is for development and is here instead of having a buried properties file that might cause conflicts when trying to override
    private static final String defaultConf = "conf/MoquiDevConf.xml";
    private static final String tempDirName = "execwartmp";

    private final static boolean reportJarsUnused = Boolean.valueOf(System.getProperty("report.jars.unused", "false"));
    // private final static boolean reportJarsUnused = true;

    public static void main(String[] args) throws IOException {
        // now grab the first arg and see if it is a known command
        String firstArg = args.length > 0 ? args[0] : "";

        // make a list of arguments
        List<String> argList = Arrays.asList(args);
        Map<String, String> argMap = new LinkedHashMap<>();
        for (String arg: argList) {
            // run twice to allow one or two dashes
            if (arg.startsWith("-")) arg = arg.substring(1);
            if (arg.startsWith("-")) arg = arg.substring(1);
            if (arg.contains("=")) {
                argMap.put(arg.substring(0, arg.indexOf("=")), arg.substring(arg.indexOf("=")+1));
            } else {
                argMap.put(arg, "");
            }
        }

        if (firstArg.endsWith("help") || "-?".equals(firstArg)) {
            // setup the class loader
            StartClassLoader moquiStartLoader = new StartClassLoader(true);
            Thread.currentThread().setContextClassLoader(moquiStartLoader);
            Runtime.getRuntime().addShutdownHook(new MoquiShutdown(null, null, moquiStartLoader));
            initSystemProperties(moquiStartLoader, false, argMap);

            /* nice for debugging, messy otherwise:
            System.out.println("Internal Class Path Jars:");
            for (JarFile jf: moquiStartLoader.jarFileList) {
                String fn = jf.getName();
                System.out.println(fn.contains("moqui_temp") ? fn.substring(fn.indexOf("moqui_temp")) : fn);
            }
            */
            System.out.println("------------------------------------------------");
            System.out.println("Current runtime directory (moqui.runtime): " + System.getProperty("moqui.runtime"));
            System.out.println("Current configuration file (moqui.conf): " + System.getProperty("moqui.conf"));
            System.out.println("To set these properties use something like: java -Dmoqui.conf=conf/MoquiProductionConf.xml -jar moqui.war ...");
            System.out.println("------------------------------------------------");
            System.out.println("Executable WAR : java -jar moqui.war [command] [arguments]");
            System.out.println("Expanded WAR   : java -cp . MoquiStart [command] [arguments]");
            System.out.println("help, -? ---- Help (this text)");
            System.out.println("load -------- Run data loader");
            System.out.println("    types=<type>[,<type>] ------- Data types to load (can be anything, common are: seed, seed-initial, install, demo, ...)");
            System.out.println("    components=<name>[,<name>] -- Component names to load for data types; if none specified loads from all");
            System.out.println("    location=<location> --------- Location of data file to load");
            System.out.println("    timeout=<seconds> ----------- Transaction timeout for each file, defaults to 600 seconds (10 minutes)");
            System.out.println("    dummy-fks ------------------- Use dummy foreign-keys to avoid referential integrity errors");
            System.out.println("    use-try-insert -------------- Try insert and update on error instead of checking for record first");
            System.out.println("    disable-eeca ---------------- Disable Entity ECA rules");
            System.out.println("    disable-audit-log ----------- Disable Entity Audit Log");
            System.out.println("    raw ------------------------- Short for dummy-fks, use-try-insert, disable-eeca, disable-audit-log");
            System.out.println("    conf=<moqui.conf> ----------- The Moqui Conf XML file to use, overrides other ways of specifying it");
            System.out.println("    run-es ---------------------- Try starting and stopping ElasticSearch in runtime/elasticsearch");
            System.out.println("    If no -types or -location argument is used all known data files of all types will be loaded.");
            System.out.println("[default] ---- Run embedded Jetty server");
            System.out.println("    port=<port> ---------------- The http listening port. Default is 8080");
            System.out.println("    threads=<max threads> ------ Maximum number of threads. Default is 100");
            System.out.println("    conf=<moqui.conf> ---------- The Moqui Conf XML file to use, overrides other ways of specifying it");
            System.out.println("    run-es ---------------------- Try starting and stopping ElasticSearch in runtime/elasticsearch");
            System.out.println("");
            System.exit(0);
        }

        boolean isInWar = true;
        try {
            ProtectionDomain pd = MoquiStart.class.getProtectionDomain();
            CodeSource cs = pd.getCodeSource();
            URL wrapperUrl = cs.getLocation();
            File wrapperFile = new File(wrapperUrl.toURI());
            if (wrapperFile.isDirectory()) isInWar = false;
        } catch (Exception e) {
            System.out.println("Error checking class wrapper: " + e.toString());
        }

        // if doing anything other than help make sure temp dir deleted
        if (isInWar) {
            File tempDir = new File(tempDirName);
            System.out.println("Using temporary directory: " + tempDir.getCanonicalPath());
            if (tempDir.exists()) {
                System.out.println("Found temporary directory " + tempDirName + ", deleting");
                //noinspection ResultOfMethodCallIgnored
                tempDir.delete();
            }
        }

        // run load if is first argument
        if (firstArg.endsWith("load")) {
            StartClassLoader moquiStartLoader = new StartClassLoader(true);
            Thread.currentThread().setContextClassLoader(moquiStartLoader);
            // Runtime.getRuntime().addShutdownHook(new MoquiShutdown(null, null, moquiStartLoader));
            initSystemProperties(moquiStartLoader, false, argMap);
            Process esProcess = argMap.containsKey("run-es") ? checkStartElasticSearch() : null;

            try {
                System.out.println("Loading data with args " + argMap);
                Class<?> c = moquiStartLoader.loadClass("org.moqui.Moqui");
                Method m = c.getMethod("loadData", Map.class);
                m.invoke(null, argMap);
            } catch (Exception e) {
                System.out.println("Error loading or running Moqui.loadData with args [" + argMap + "]: " + e.toString());
                e.printStackTrace();
            }
            checkStopElasticSearch(esProcess);
            System.exit(0);
        }

        // ===== Done trying specific commands, so load the embedded server

        // Get a start loader with loadWebInf=false since the container will load those we don't want to here (would be on classpath twice)
        StartClassLoader moquiStartLoader = new StartClassLoader(reportJarsUnused);
        Thread.currentThread().setContextClassLoader(moquiStartLoader);

        // NOTE: not using MoquiShutdown hook any more, let Jetty stop everything
        //   may need to add back for jar file close, cleaner delete on exit
        // Thread shutdownHook = new MoquiShutdown(null, null, moquiStartLoader);
        // shutdownHook.setDaemon(true);
        // Runtime.getRuntime().addShutdownHook(shutdownHook);

        initSystemProperties(moquiStartLoader, false, argMap);
        String runtimePath = System.getProperty("moqui.runtime");

        Process esProcess = argMap.containsKey("run-es") ? checkStartElasticSearch() : null;
        if (esProcess != null) {
            Thread shutdownHook = new ElasticShutdown(esProcess);
            shutdownHook.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        try {
            int port = 8080;
            String portStr = argMap.get("port");
            if (portStr != null && portStr.length() > 0) port = Integer.parseInt(portStr);
            int threads = 100;
            String threadsStr = argMap.get("threads");
            if (threadsStr != null && threadsStr.length() > 0) threads = Integer.parseInt(threadsStr);

            System.out.println("Running Jetty server on port " + port + " max threads " + threads + " with args [" + argMap + "]");

            Class<?> serverClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.Server");
            Class<?> handlerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.Handler");
            Class<?> sizedThreadPoolClass = moquiStartLoader.loadClass("org.eclipse.jetty.util.thread.ThreadPool$SizedThreadPool");

            Class<?> httpConfigurationClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.HttpConfiguration");
            Class<?> forwardedRequestCustomizerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.ForwardedRequestCustomizer");
            Class<?> customizerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.HttpConfiguration$Customizer");

            Class<?> sessionIdManagerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.SessionIdManager");
            Class<?> defaultSessionIdManagerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.DefaultSessionIdManager");
            Class<?> sessionHandlerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.SessionHandler");
            Class<?> sessionCacheClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.SessionCache");
            Class<?> defaultSessionCacheClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.DefaultSessionCache");
            Class<?> sessionDataStoreClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.SessionDataStore");
            Class<?> fileSessionDataStoreClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.session.FileSessionDataStore");

            Class<?> connectorClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.Connector");
            Class<?> serverConnectorClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.ServerConnector");
            Class<?> webappClass = moquiStartLoader.loadClass("org.eclipse.jetty.webapp.WebAppContext");

            Class<?> connectionFactoryClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.ConnectionFactory");
            Class<?> connectionFactoryArrayClass = Array.newInstance(connectionFactoryClass, 1).getClass();
            Class<?> httpConnectionFactoryClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.HttpConnectionFactory");

            Class<?> scHandlerClass = moquiStartLoader.loadClass("org.eclipse.jetty.servlet.ServletContextHandler");
            Class<?> wsInitializerClass = moquiStartLoader.loadClass("org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer");

            Class<?> gzipHandlerClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.handler.gzip.GzipHandler");
            Class<?> handlerWrapperClass = moquiStartLoader.loadClass("org.eclipse.jetty.server.handler.HandlerWrapper");

            Object server = serverClass.getConstructor().newInstance();
            Object httpConfig = httpConfigurationClass.getConstructor().newInstance();
            Object forwardedRequestCustomizer = forwardedRequestCustomizerClass.getConstructor().newInstance();
            httpConfigurationClass.getMethod("addCustomizer", customizerClass).invoke(httpConfig, forwardedRequestCustomizer);

            Object httpConnectionFactory = httpConnectionFactoryClass.getConstructor(httpConfigurationClass).newInstance(httpConfig);
            Object connectionFactoryArray = Array.newInstance(connectionFactoryClass, 1);
            Array.set(connectionFactoryArray, 0, httpConnectionFactory);
            Object httpConnector = serverConnectorClass.getConstructor(serverClass, connectionFactoryArrayClass).newInstance(server, connectionFactoryArray);
            serverConnectorClass.getMethod("setPort", int.class).invoke(httpConnector, port);

            serverClass.getMethod("addConnector", connectorClass).invoke(server, httpConnector);

            // SessionDataStore
            File storeDir = new File(runtimePath + "/sessions");
            if (!storeDir.exists()) storeDir.mkdirs();
            System.out.println("Creating Jetty FileSessionDataStore with directory " + storeDir.getCanonicalPath());

            Object sessionHandler = sessionHandlerClass.getConstructor().newInstance();
            sessionHandlerClass.getMethod("setServer", serverClass).invoke(sessionHandler, server);
            Object sessionCache = defaultSessionCacheClass.getConstructor(sessionHandlerClass).newInstance(sessionHandler);
            Object sessionDataStore = fileSessionDataStoreClass.getConstructor().newInstance();
            fileSessionDataStoreClass.getMethod("setStoreDir", File.class).invoke(sessionDataStore, storeDir);
            fileSessionDataStoreClass.getMethod("setDeleteUnrestorableFiles", boolean.class).invoke(sessionDataStore, true);
            sessionCacheClass.getMethod("setSessionDataStore", sessionDataStoreClass).invoke(sessionCache, sessionDataStore);
            sessionHandlerClass.getMethod("setSessionCache", sessionCacheClass).invoke(sessionHandler, sessionCache);

            Object sidMgr = defaultSessionIdManagerClass.getConstructor(serverClass).newInstance(server);
            defaultSessionIdManagerClass.getMethod("setServer", serverClass).invoke(sidMgr, server);
            sessionHandlerClass.getMethod("setSessionIdManager", sessionIdManagerClass).invoke(sessionHandler, sidMgr);
            serverClass.getMethod("setSessionIdManager", sessionIdManagerClass).invoke(server, sidMgr);

            // WebApp
            Object webapp = webappClass.getConstructor().newInstance();

            webappClass.getMethod("setContextPath", String.class).invoke(webapp, "/");
            webappClass.getMethod("setDescriptor", String.class).invoke(webapp, moquiStartLoader.wrapperUrl.toExternalForm() + "/WEB-INF/web.xml");
            webappClass.getMethod("setServer", serverClass).invoke(webapp, server);
            webappClass.getMethod("setSessionHandler", sessionHandlerClass).invoke(webapp, sessionHandler);
            webappClass.getMethod("setMaxFormKeys", int.class).invoke(webapp, 5000);
            if (isInWar) {
                webappClass.getMethod("setWar", String.class).invoke(webapp, moquiStartLoader.wrapperUrl.toExternalForm());
                webappClass.getMethod("setTempDirectory", File.class).invoke(webapp, new File(tempDirName + "/ROOT"));
            } else {
                webappClass.getMethod("setResourceBase", String.class).invoke(webapp, moquiStartLoader.wrapperUrl.toExternalForm());
            }
            serverClass.getMethod("setHandler", handlerClass).invoke(server, webapp);

            if (reportJarsUnused) webappClass.getMethod("setClassLoader", ClassLoader.class).invoke(webapp, moquiStartLoader);

            // WebSocket
            Object wsContainer = wsInitializerClass.getMethod("configureContext", scHandlerClass).invoke(null, webapp);
            webappClass.getMethod("setAttribute", String.class, Object.class).invoke(webapp, "javax.websocket.server.ServerContainer", wsContainer);

            // GzipHandler
            Object gzipHandler = gzipHandlerClass.getConstructor().newInstance();
            // use defaults, should include all except certain excludes:
            // gzipHandlerClass.getMethod("setIncludedMimeTypes", String[].class).invoke(gzipHandler, new Object[] { new String[] {"text/html", "text/plain", "text/xml", "text/css", "application/javascript", "text/javascript"} });
            serverClass.getMethod("insertHandler", handlerWrapperClass).invoke(server, gzipHandler);

            // Log getMinThreads, getMaxThreads
            Object threadPool = serverClass.getMethod("getThreadPool").invoke(server);
            sizedThreadPoolClass.getMethod("setMaxThreads", int.class).invoke(threadPool, threads);
            int minThreads = (int) sizedThreadPoolClass.getMethod("getMinThreads").invoke(threadPool);
            int maxThreads = (int) sizedThreadPoolClass.getMethod("getMaxThreads").invoke(threadPool);
            System.out.println("Jetty min threads " + minThreads + ", max threads " + maxThreads);

            // Tell Jetty to stop on JVM shutdown
            serverClass.getMethod("setStopAtShutdown", boolean.class).invoke(server, true);
            serverClass.getMethod("setStopTimeout", long.class).invoke(server, 30000L);

            // Start
            serverClass.getMethod("start").invoke(server);
            serverClass.getMethod("join").invoke(server);

            /* The classpath dependent code we are running:

            Server server = new Server();
            HttpConfiguration httpConfig = new org.eclipse.jetty.server.HttpConfiguration();
            ForwardedRequestCustomizer forwardedRequestCustomizer = new ForwardedRequestCustomizer();
            httpConfig.addCustomizer(forwardedRequestCustomizer);

            HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
            ServerConnector httpConnector = new ServerConnector(server, httpConnectionFactory);
            httpConnector.setPort(port);
            server.addConnector(httpConnector);

            // SessionDataStore
            SessionIdManager sidMgr = new DefaultSessionIdManager(server);
            sidMgr.setServer(server);
            server.setSessionIdManager(sidMgr);
            SessionHandler sessionHandler = new SessionHandler();
            sessionHandler.setServer(server);
            SessionCache sessionCache = new DefaultSessionCache(sessionHandler);
            sessionHandler.setSessionCache(sessionCache);
            sessionHandler.setSessionIdManager(sidMgr);

            File storeDir = ...;
            FileSessionDataStore sessionDataStore = new FileSessionDataStore();
            sessionDataStore.setStoreDir(storeDir);
            sessionDataStore.setDeleteUnrestorableFiles(true);
            sessionCache.setSessionDataStore(sessionDataStore);

            sessionHandler.start();

            // WebApp
            WebAppContext webapp = new WebAppContext();
            webapp.setContextPath("/");
            webapp.setDescriptor(moquiStartLoader.wrapperWarUrl.toExternalForm() + "/WEB-INF/web.xml");
            webapp.setServer(server);
            webapp.setWar(moquiStartLoader.wrapperWarUrl.toExternalForm());

            // (Optional) Set the directory the war will extract to.
            // If not set, java.io.tmpdir will be used, which can cause problems
            // if the temp directory gets cleaned periodically.
            // Removed by the code elsewhere that deletes on close
            webapp.setTempDirectory(new File(tempDirName + "/ROOT"));
            server.setHandler(webapp);

            // WebSocket
            // NOTE: ServletContextHandler.SESSIONS = 1 (int)
            ServerContainer wsContainer = org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer.configureContext(webapp);
            webapp.setAttribute("javax.websocket.server.ServerContainer", wsContainer);

            // GzipHandler
            GzipHandler gzipHandler = new GzipHandler();
            // gzipHandler.setIncludedMimeTypes("text/html", "text/plain", "text/xml", "text/css", "application/javascript", "text/javascript");
            server.insertHandler(gzipHandler);

            // Start things up!
            server.start();
            // The use of server.join() the will make the current thread join and
            // wait until the server is done executing.
            // See http://docs.oracle.com/javase/7/docs/api/java/lang/Thread.html#join()
            server.join();

            // Possible code to handle HTTPS, HTTP/2 (h2, h2c):

            // see https://webtide.com/introduction-to-http2-in-jetty/
            // see https://www.eclipse.org/jetty/documentation/9.3.x/http2.html
            // org.mortbay.jetty.alpn:alpn-boot:8.1.9.v20160720
            // http2-common, http2-hpack, http2-server

            Server server = new Server();
            HttpConfiguration httpConfig = new org.eclipse.jetty.server.HttpConfiguration();
            httpConfig.setSecureScheme("https");
            httpConfig.setSecurePort(8443);
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            SslContextFactory sslContextFactory = new org.eclipse.jetty.util.ssl.SslContextFactory();
            sslContextFactory.setKeyStoreResource(org.eclipse.jetty.util.resource.Resource.newClassPathResource("keystore"));
            sslContextFactory.setKeyStorePassword("kStorePassword");
            sslContextFactory.setKeyManagerPassword("kMgrPassword");
            sslContextFactory.setCipherComparator(org.eclipse.jetty.http2.HTTP2Cipher.COMPARATOR);

            HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfig);

            HTTP2ServerConnectionFactory http2 = new org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory(httpsConfig);
            NegotiatingServerConnectionFactory.checkProtocolNegotiationAvailable();
            ALPNServerConnectionFactory alpn = new org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory();
            alpn.setDefaultProtocol("h2");
            SslConnectionFactory ssl = new org.eclipse.jetty.server?.SslConnectionFactory(sslContextFactory,alpn.getProtocol());

            HTTP2CServerConnectionFactory http2c = new org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory(httpsConfig);

            ServerConnector httpsConnector = new org.eclipse.jetty.server.ServerConnector(server, ssl, alpn, http2, http1 );
            httpsConnector.setPort(8443);
            server.addConnector(httpsConnector);

            ServerConnector httpConnector = new org.eclipse.jetty.server.ServerConnector(server, http1, http2c);
            httpConnector.setPort(8080);
            server.addConnector(httpConnector);

            */
        } catch (Exception e) {
            System.out.println("Error loading or running Jetty embedded server with args [" + argMap + "]: " + e.toString());
            e.printStackTrace();
        }

        // now wait for break...
    }

    private static void initSystemProperties(StartClassLoader cl, boolean useProperties, Map<String, String> argMap) throws IOException {
        Properties moquiInitProperties = null;
        if (useProperties) {
            moquiInitProperties = new Properties();
            URL initProps = cl.getResource("MoquiInit.properties");
            if (initProps != null) { InputStream is = initProps.openStream(); moquiInitProperties.load(is); is.close(); }
        }

        // before doing anything else make sure the moqui.runtime system property exists (needed for config of various things)
        String runtimePath = System.getProperty("moqui.runtime");
        if (runtimePath != null && runtimePath.length() > 0)
            System.out.println("Determined runtime from Java system property: " + runtimePath);
        if (moquiInitProperties != null && (runtimePath == null || runtimePath.length() == 0)) {
            runtimePath = moquiInitProperties.getProperty("moqui.runtime");
            if (runtimePath != null && runtimePath.length() > 0)
                System.out.println("Determined runtime from MoquiInit.properties file: " + runtimePath);
        }
        if (runtimePath == null || runtimePath.length() == 0) {
            // see if runtime directory under the current directory exists, if not default to the current directory
            File testFile = new File("runtime");
            if (testFile.exists()) runtimePath = "runtime";
            if (runtimePath != null && runtimePath.length() > 0)
                System.out.println("Determined runtime from existing runtime subdirectory: " + testFile.getCanonicalPath());
        }
        if (runtimePath == null || runtimePath.length() == 0) {
            runtimePath = ".";
            System.out.println("Determined runtime by defaulting to current directory: " + runtimePath);
        }
        File runtimeFile = new File(runtimePath);
        runtimePath = runtimeFile.getCanonicalPath();
        System.out.println("Canonicalized runtimePath: " + runtimePath);
        if (runtimePath.endsWith("/")) runtimePath = runtimePath.substring(0, runtimePath.length()-1);
        System.setProperty("moqui.runtime", runtimePath);

        /* Don't do this here... loads as lower-level that WEB-INF/lib jars and so can't have dependencies on those,
            and dependencies on those are necessary
        // add runtime/lib jar files to the class loader
        File runtimeLibFile = new File(runtimePath + "/lib");
        for (File jarFile: runtimeLibFile.listFiles()) {
            if (jarFile.getName().endsWith(".jar")) cl.jarFileList.add(new JarFile(jarFile));
        }
        */

        String confPath = argMap.get("conf");
        if (confPath != null && !confPath.isEmpty()) System.out.println("Determined conf from conf argument: " + confPath);
        if (confPath == null || confPath.isEmpty()) {
            confPath = System.getProperty("moqui.conf");
            if (confPath != null && !confPath.isEmpty()) System.out.println("Determined conf from Java system property: " + confPath);
        }
        if (moquiInitProperties != null && (confPath == null || confPath.isEmpty())) {
            confPath = moquiInitProperties.getProperty("moqui.conf");
            if (confPath != null && !confPath.isEmpty()) System.out.println("Determined conf from MoquiInit.properties file: " + confPath);
        }
        if (confPath == null || confPath.isEmpty()) {
            File testFile = new File(runtimePath + "/" + defaultConf);
            if (testFile.exists()) confPath = defaultConf;
            System.out.println("Determined conf by default (dev conf file): " + confPath);
        }
        if (confPath != null && !confPath.isEmpty()) System.setProperty("moqui.conf", confPath);
    }

    private static Process checkStartElasticSearch() {
        String runtimePath = System.getProperty("moqui.runtime");
        String esDir = runtimePath + "/elasticsearch";
        if (!new File(esDir + "/bin").exists()) return null;
        if (new File(esDir + "/pid").exists()) {
            System.out.println("ElasticSearch install found in runtime/elasticsearch, pid file found so not starting");
            return null;
        }
        String[] envArr = { "JAVA_HOME=" + System.getProperty("java.home") };
        System.out.println("Starting ElasticSearch install found in runtime/elasticsearch, pid file not found (" + envArr[0] + ")");
        try {
            Process esProcess = Runtime.getRuntime().exec("./bin/elasticsearch", envArr, new File(esDir));
            System.setProperty("moqui.elasticsearch.started", "true");
            return esProcess;
        } catch (Exception e) {
            System.out.println("Error starting ElasticSearch in runtime/elasticsearch: " + e.toString());
            return null;
        }
    }
    private static void checkStopElasticSearch(Process esProcess) {
        if (esProcess != null) esProcess.destroy();
    }
    private static class ElasticShutdown extends Thread {
        final Process esProcess;
        ElasticShutdown(Process esProcess) { super(); this.esProcess = esProcess; }
        @Override public void run() { esProcess.destroy(); }
    }

    private static class MoquiShutdown extends Thread {
        final Method callMethod;
        final Object callObject;
        final StartClassLoader moquiStart;
        MoquiShutdown(Method callMethod, Object callObject, StartClassLoader moquiStart) {
            super();
            this.callMethod = callMethod;
            this.callObject = callObject;
            this.moquiStart = moquiStart;
        }
        @Override
        public void run() {
            // run this first, ie shutdown the container before closing jarFiles to avoid errors with classes missing
            if (callMethod != null) {
                try { callMethod.invoke(callObject); } catch (Exception e) { System.out.println("Error in shutdown: " + e.toString()); }
            }

            // give things a couple seconds to destroy; this way of running is mostly for dev/test where this should be sufficient
            try { synchronized (this) { this.wait(2000); } } catch (Exception e) { System.out.println("Shutdown wait interrupted"); }
            System.out.println("========== Shutting down Moqui Executable (closing jars, etc) ==========");

            // close all jarFiles so they will "deleteOnExit"
            for (JarFile jarFile : moquiStart.jarFileList) {
                try {
                    jarFile.close();
                } catch (IOException e) {
                    System.out.println("Error closing jar [" + jarFile + "]: " + e.toString());
                }
            }

            if (reportJarsUnused) {
                Set<String> sortedJars = new TreeSet<>();
                String baseName = "execwartmp/moqui_temp";
                for (String jarName: moquiStart.jarsUnused) {
                    if (jarName.startsWith(baseName)) {
                        jarName = jarName.substring(baseName.length());
                        while (Character.isDigit(jarName.charAt(0))) jarName = jarName.substring(1);
                    }
                    sortedJars.add(jarName);
                }
                for (String jarName: sortedJars) System.out.println("JAR unused: " + jarName);
            }
        }
    }

    private static class StartClassLoader extends ClassLoader {

        private URL wrapperUrl = null;
        private boolean isInWar = true;
        final ArrayList<JarFile> jarFileList = new ArrayList<>();
        private final Map<String, URL> jarLocationByJarName = new HashMap<>();
        private final Map<String, Class<?>> classCache = new HashMap<>();
        private final Map<String, URL> resourceCache = new HashMap<>();
        private ProtectionDomain pd;
        private final boolean loadWebInf;

        final Set<String> jarsUnused = new HashSet<>();

        private StartClassLoader(boolean loadWebInf) {
            this(ClassLoader.getSystemClassLoader(), loadWebInf);
        }

        private StartClassLoader(ClassLoader parent, boolean loadWebInf) {
            super(parent);
            this.loadWebInf = loadWebInf;

            try {
                // get outer file (the war file)
                pd = getClass().getProtectionDomain();
                CodeSource cs = pd.getCodeSource();
                wrapperUrl = cs.getLocation();
                File wrapperFile = new File(wrapperUrl.toURI());
                isInWar = !wrapperFile.isDirectory();

                if (isInWar) {
                    JarFile outerFile = new JarFile(wrapperFile);

                    // allow for classes in the outerFile as well
                    jarFileList.add(outerFile);
                    jarLocationByJarName.put(outerFile.getName(), wrapperUrl);

                    Enumeration<JarEntry> jarEntries = outerFile.entries();
                    while (jarEntries.hasMoreElements()) {
                        JarEntry je = jarEntries.nextElement();
                        if (je.isDirectory()) continue;
                        // if we aren't loading the WEB-INF files and it is one, skip it
                        if (!loadWebInf && je.getName().startsWith("WEB-INF")) continue;
                        // get jars, can be anywhere in the file
                        String jeName = je.getName().toLowerCase();
                        if (jeName.lastIndexOf(".jar") == jeName.length() - 4) {
                            File file = createTempFile(outerFile, je);
                            JarFile newJarFile = new JarFile(file);
                            jarFileList.add(newJarFile);
                            jarLocationByJarName.put(newJarFile.getName(), file.toURI().toURL());
                        }
                    }
                } else {
                    ArrayList<File> jarList = new ArrayList<>();
                    addJarFilesNested(wrapperFile, jarList, loadWebInf);
                    for (File jarFile : jarList) {
                        JarFile newJarFile = new JarFile(jarFile);
                        jarFileList.add(newJarFile);
                        jarLocationByJarName.put(newJarFile.getName(), jarFile.toURI().toURL());
                        // System.out.println("jar file: " + jarFile.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                System.out.println("Error loading jars in war file [" + wrapperUrl + "]: " + e.toString());
            }

            if (reportJarsUnused) for (JarFile jf : jarFileList) jarsUnused.add(jf.getName());
        }

        private ConcurrentHashMap<URL, ProtectionDomain> protectionDomainByUrl = new ConcurrentHashMap<>();
        private ProtectionDomain getProtectionDomain(URL jarLocation) {
            ProtectionDomain curPd = protectionDomainByUrl.get(jarLocation);
            if (curPd != null) return curPd;
            CodeSource codeSource = new CodeSource(jarLocation, (Certificate[]) null);
            ProtectionDomain newPd = new ProtectionDomain(codeSource, null, this, null);
            ProtectionDomain existingPd = protectionDomainByUrl.putIfAbsent(jarLocation, newPd);
            return existingPd != null ? existingPd : newPd;
        }

        private void addJarFilesNested(File file, List<File> jarList, boolean loadWebInf) {
            for (File child : file.listFiles()) {
                if (child.isDirectory()) {
                    // generally run with the runtime directory in the same directory, so skip it (or causes weird class dependency errors)
                    if ("runtime".equals(child.getName())) continue;
                    // if we aren't loading the WEB-INF files and it is one, skip it
                    if (!loadWebInf && "WEB-INF".equals(child.getName())) continue;
                    addJarFilesNested(child, jarList, loadWebInf);
                } else if (child.getName().endsWith(".jar")) {
                    jarList.add(child);
                }
            }
        }

        @SuppressWarnings("ThrowFromFinallyBlock")
        private File createTempFile(JarFile outerFile, JarEntry je) throws IOException {
            byte[] jeBytes = getJarEntryBytes(outerFile, je);

            String tempName = je.getName().replace('/', '_') + ".";
            File tempDir = new File(tempDirName);
            if (tempDir.mkdir()) tempDir.deleteOnExit();
            File file = File.createTempFile("moqui_temp", tempName, tempDir);
            file.deleteOnExit();
            BufferedOutputStream os = null;
            try {
                os = new BufferedOutputStream(new FileOutputStream(file));
                os.write(jeBytes);
            } finally {
                if (os != null) os.close();
            }
            return file;
        }

        @SuppressWarnings("ThrowFromFinallyBlock")
        private byte[] getJarEntryBytes(JarFile jarFile, JarEntry je) throws IOException {
            DataInputStream dis = null;
            byte[] jeBytes = null;
            try {
                long lSize = je.getSize();
                if (lSize <= 0  ||  lSize >= Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Size [" + lSize + "] not valid for war entry [" + je + "]");
                }
                jeBytes = new byte[(int)lSize];
                InputStream is = jarFile.getInputStream(je);
                dis = new DataInputStream(is);
                dis.readFully(jeBytes);
            } finally {
                if (dis != null) dis.close();
            }
            return jeBytes;
        }

        /** @see java.lang.ClassLoader#findResource(java.lang.String) */
        @Override
        protected URL findResource(String resourceName) {
            if (resourceCache.containsKey(resourceName)) return resourceCache.get(resourceName);

            // try the runtime/classes directory for conf files and such
            String runtimePath = System.getProperty("moqui.runtime");
            String fullPath = runtimePath + "/classes/" + resourceName;
            File resourceFile = new File(fullPath);
            if (resourceFile.exists()) try {
                return resourceFile.toURI().toURL();
            } catch (MalformedURLException e) {
                System.out.println("Error making URL for [" + resourceName + "] in runtime classes directory [" + runtimePath + "/classes/" + "]: " + e.toString());
            }

            String webInfResourceName = "WEB-INF/classes/" + resourceName;
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                JarEntry jarEntry = jarFile.getJarEntry(resourceName);
                if (reportJarsUnused && jarEntry != null) jarsUnused.remove(jarFile.getName());
                // to better support war format, look for the resourceName in the WEB-INF/classes directory
                if (loadWebInf && jarEntry == null) jarEntry = jarFile.getJarEntry(webInfResourceName);
                if (jarEntry != null) {
                    try {
                        String jarFileName = jarFile.getName();
                        if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                        URL resourceUrl = new URL("jar:file:" + jarFileName + "!/" + jarEntry);
                        resourceCache.put(resourceName, resourceUrl);
                        return resourceUrl;
                    } catch (MalformedURLException e) {
                        System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "] in war file [" + wrapperUrl + "]: " + e.toString());
                    }
                }
            }
            return super.findResource(resourceName);
        }

        /** @see java.lang.ClassLoader#findResources(java.lang.String) */
        @Override
        public Enumeration<URL> findResources(String resourceName) throws IOException {
            String webInfResourceName = "WEB-INF/classes/" + resourceName;
            List<URL> urlList = new ArrayList<>();
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                JarEntry jarEntry = jarFile.getJarEntry(resourceName);
                if (reportJarsUnused && jarEntry != null) jarsUnused.remove(jarFile.getName());
                // to better support war format, look for the resourceName in the WEB-INF/classes directory
                if (loadWebInf && jarEntry == null) jarEntry = jarFile.getJarEntry(webInfResourceName);
                if (jarEntry != null) {
                    try {
                        String jarFileName = jarFile.getName();
                        if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                        urlList.add(new URL("jar:file:" + jarFileName + "!/" + jarEntry));
                    } catch (MalformedURLException e) {
                        System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "] in war file [" + wrapperUrl + "]: " + e.toString());
                    }
                }
            }
            // add all resources found in parent loader too
            Enumeration<URL> superResources = super.findResources(resourceName);
            while (superResources.hasMoreElements()) urlList.add(superResources.nextElement());
            return Collections.enumeration(urlList);
        }

        @Override
        protected synchronized Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
            Class<?> c = null;
            try {
                try {
                    ClassLoader cl = getParent();
                    c = cl.loadClass(className);
                    if (c != null) return c;
                } catch (ClassNotFoundException e) { /* let the next one handle this */ }

                try {
                    c = findJarClass(className);
                    if (c != null) return c;
                } catch (Exception e) {
                    System.out.println("Error loading class [" + className + "] from jars in war file [" + wrapperUrl + "]: " + e.toString());
                    e.printStackTrace();
                }

                throw new ClassNotFoundException("Class [" + className + "] not found");
            } finally {
                if (c != null  &&  resolve) {
                    resolveClass(c);
                }
            }
        }

        private Class<?> findJarClass(String className) throws IOException, ClassFormatError {
            if (classCache.containsKey(className)) return classCache.get(className);

            Class<?> c = null;
            String classFileName = className.replace('.', '/') + ".class";
            String webInfFileName = "WEB-INF/classes/" + classFileName;
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                // System.out.println("Finding Class [" + className + "] in jarFile [" + jarFile.getName() + "]");
                JarEntry jarEntry = jarFile.getJarEntry(classFileName);
                if (reportJarsUnused && jarEntry != null) jarsUnused.remove(jarFile.getName());

                // to better support war format, look for the resourceName in the WEB-INF/classes directory
                if (loadWebInf && jarEntry == null) jarEntry = jarFile.getJarEntry(webInfFileName);
                if (jarEntry != null) {
                    definePackage(className, jarFile);
                    byte[] jeBytes = getJarEntryBytes(jarFile, jarEntry);
                    if (jeBytes == null) {
                        System.out.println("Could not get bytes for [" + jarEntry.getName() + "] in [" + jarFile.getName() + "]");
                        continue;
                    }
                    // System.out.println("Class [" + classFileName + "] FOUND in jarFile [" + jarFile.getName() + "], size is " + (jeBytes == null ? "null" : jeBytes.length));
                    URL jarLocation = jarLocationByJarName.get(jarFile.getName());
                    c = defineClass(className, jeBytes, 0, jeBytes.length, jarLocation != null ? getProtectionDomain(jarLocation) : pd);
                    break;
                }
            }
            classCache.put(className, c);
            return c;
        }

        private void definePackage(String className, JarFile jarFile) throws IllegalArgumentException {
            Manifest mf;
            try {
                mf = jarFile.getManifest();
            } catch (IOException e) {
                // use default manifest
                mf = new Manifest();
            }
            if (mf == null) mf = new Manifest();
            int dotIndex = className.lastIndexOf('.');
            String packageName = dotIndex > 0 ? className.substring(0, dotIndex) : "";
            if (getPackage(packageName) == null) {
                definePackage(packageName,
                        mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_TITLE),
                        mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_VERSION),
                        mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_VENDOR),
                        mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_TITLE),
                        mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION),
                        mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VENDOR),
                        getSealURL(mf));
            }
        }

        private URL getSealURL(Manifest mf) {
            String seal = mf.getMainAttributes().getValue(Attributes.Name.SEALED);
            if (seal == null) return null;
            try {
                return new URL(seal);
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }
}
