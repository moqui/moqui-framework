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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
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
public class MoquiStart extends ClassLoader {
    // this default is for development and is here instead of having a buried properties file that might cause conflicts when trying to override
    static final String defaultConf = "conf/MoquiDevConf.xml";

    public static void main(String[] args) throws IOException {
        // now grab the first arg and see if it is a known command
        String firstArg = args.length > 0 ? args[0] : "";

        if (firstArg.contains("-help") || "-?".equals(firstArg)) {
            // setup the class loader
            MoquiStart moquiStartLoader = new MoquiStart(true);
            Thread.currentThread().setContextClassLoader(moquiStartLoader);
            Runtime.getRuntime().addShutdownHook(new MoquiShutdown(null, null, moquiStartLoader.jarFileList));
            initSystemProperties(moquiStartLoader, false);

            System.out.println("Internal Class Path Jars:");
            for (JarFile jf: moquiStartLoader.jarFileList) {
                String fn = jf.getName();
                System.out.println(fn.contains("moqui_temp") ? fn.substring(fn.indexOf("moqui_temp")) : fn);
            }
            System.out.println("------------------------------------------------");
            System.out.println("Current runtime directory (moqui.runtime): " + System.getProperty("moqui.runtime"));
            System.out.println("Current configuration file (moqui.conf): " + System.getProperty("moqui.conf"));
            System.out.println("To set these properties use something like: java -Dmoqui.conf=conf/MoquiStagingConf.xml -jar moqui.war ...");
            System.out.println("------------------------------------------------");
            System.out.println("Usage: java -jar moqui.war [command] [arguments]");
            System.out.println("-help, -? ---- Help (this text)");
            System.out.println("-load -------- Run data loader");
            System.out.println("    -types=<type>[,<type>] ------- Data types to load (can be anything, common are: seed, seed-initial, demo, ...)");
            System.out.println("    -components=<name>[,<name>] -- Component names to load for data types; if none specified loads from all");
            System.out.println("    -location=<location> ---- Location of data file to load");
            System.out.println("    -timeout=<seconds> ------ Transaction timeout for each file, defaults to 600 seconds (10 minutes)");
            System.out.println("    -dummy-fks -------------- Use dummy foreign-keys to avoid referential integrity errors");
            System.out.println("    -use-try-insert --------- Try insert and update on error instead of checking for record first");
            System.out.println("    -tenantId=<tenantId> ---- ID for the Tenant to load the data into");
            System.out.println("  If no -types or -location argument is used all known data files of all types will be loaded.");
            System.out.println("[default] ---- Run embedded Winstone server.");
            System.out.println("  See https://code.google.com/p/winstone/wiki/CmdLineOption for all argument details.");
            System.out.println("  Selected argument details:");
            System.out.println("    --httpPort               = set the http listening port. -1 to disable, Default is 8080");
            System.out.println("    --httpListenAddress      = set the http listening address. Default is all interfaces");
            System.out.println("    --httpsPort              = set the https listening port. -1 to disable, Default is disabled");
            System.out.println("    --ajp13Port              = set the ajp13 listening port. -1 to disable, Default is 8009");
            System.out.println("    --controlPort            = set the shutdown/control port. -1 to disable, Default disabled");
            System.out.println("");
            System.exit(0);
        }

        // make a list of arguments, remove the first one (the command)
        List<String> argList = new ArrayList<>(Arrays.asList(args));

        // now run the command
        if ("-load".equals(firstArg)) {
            MoquiStart moquiStartLoader = new MoquiStart(true);
            Thread.currentThread().setContextClassLoader(moquiStartLoader);
            Runtime.getRuntime().addShutdownHook(new MoquiShutdown(null, null, moquiStartLoader.jarFileList));
            initSystemProperties(moquiStartLoader, false);

            Map<String, String> argMap = new HashMap<>();
            for (String arg: argList) {
                if (arg.startsWith("-")) arg = arg.substring(1);
                if (arg.contains("=")) {
                    argMap.put(arg.substring(0, arg.indexOf("=")), arg.substring(arg.indexOf("=")+1));
                } else {
                    argMap.put(arg, "");
                }
            }

            try {
                System.out.println("Loading data with args [" + argMap + "]");
                Class<?> c = moquiStartLoader.loadClass("org.moqui.Moqui");
                Method m = c.getMethod("loadData", new Class[] { Map.class });
                m.invoke(null, argMap);
            } catch (Exception e) {
                System.out.println("Error loading or running Moqui.loadData with args [" + argMap + "]: " + e.toString());
                e.printStackTrace();
            }
            System.exit(0);
        }

        // ===== Done trying specific commands, so load the embedded server

        // Get a start loader with loadWebInf=false since the container will load those we don't want to here (would be on classpath twice)
        MoquiStart moquiStartLoader = new MoquiStart(false);
        Thread.currentThread().setContextClassLoader(moquiStartLoader);
        // NOTE: the MoquiShutdown hook is not set here because we want to get the winstone Launcher object first, so done below...
        initSystemProperties(moquiStartLoader, true);

        Map<String, String> argMap = new HashMap<>();
        for (String arg: argList) {
            if (arg.startsWith("--")) arg = arg.substring(2);
            if (arg.contains("=")) {
                argMap.put(arg.substring(0, arg.indexOf("=")), arg.substring(arg.indexOf("=")+1));
            } else {
                argMap.put(arg, "");
            }
        }

        try {
            argMap.put("warfile", moquiStartLoader.outerFile.getName());
            System.out.println("Running Winstone embedded server with args [" + argMap + "]");

            /* for old Winstone 0.9.10:
            Class<?> c = moquiStartLoader.loadClass("winstone.Launcher");
            Method initLogger = c.getMethod("initLogger", new Class[] { Map.class });
            Method shutdown = c.getMethod("shutdown");
            // init the Winstone logger
            initLogger.invoke(null, argMap);
            // start Winstone with a new instance of the server
            Constructor wlc = c.getConstructor(new Class[] { Map.class });
            Object winstone = wlc.newInstance(argMap);
            */

            Class<?> c = moquiStartLoader.loadClass("net.winstone.Server");
            Method start = c.getMethod("start");
            // Method shutdown = c.getMethod("shutdown");
            // start Winstone with a new instance of the server
            Constructor wlc = c.getConstructor(new Class[] { Map.class });
            Object winstone = wlc.newInstance(argMap);
            start.invoke(winstone);

            // NOTE: winstone seems to have its own shutdown hook in the newer version, so using hook to close files only:
            Thread shutdownHook = new MoquiShutdown(null, null, moquiStartLoader.jarFileList);
            shutdownHook.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (Exception e) {
            System.out.println("Error loading or running Winstone embedded server with args [" + argMap + "]: " + e.toString());
            e.printStackTrace();
        }

        // now wait for break...
    }

    protected static void initSystemProperties(MoquiStart cl, boolean useProperties) throws IOException {
        Properties moquiInitProperties = new Properties();
        URL initProps = cl.getResource("MoquiInit.properties");
        if (initProps != null) { InputStream is = initProps.openStream(); moquiInitProperties.load(is); is.close(); }

        // before doing anything else make sure the moqui.runtime system property exists (needed for config of various things)
        String runtimePath = System.getProperty("moqui.runtime");
        if (runtimePath != null && runtimePath.length() > 0)
            System.out.println("Determined runtime from system property: " + runtimePath);
        if (useProperties && (runtimePath == null || runtimePath.length() == 0)) {
            runtimePath = moquiInitProperties.getProperty("moqui.runtime");
            if (runtimePath != null && runtimePath.length() > 0)
                System.out.println("Determined runtime from MoquiInit.properties file: " + runtimePath);
        }
        if (runtimePath == null || runtimePath.length() == 0) {
            // see if runtime directory under the current directory exists, if not default to the current directory
            File testFile = new File("runtime");
            if (testFile.exists()) runtimePath = "runtime";
            if (runtimePath != null && runtimePath.length() > 0)
                System.out.println("Determined runtime from existing runtime directory: " + runtimePath);
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

        // moqui.conf=conf/development/MoquiDevConf.xml
        String confPath = System.getProperty("moqui.conf");
        if (confPath == null || confPath.length() == 0) {
            confPath = moquiInitProperties.getProperty("moqui.conf");
        }
        if (confPath == null || confPath.length() == 0) {
            File testFile = new File(runtimePath + "/" + defaultConf);
            if (testFile.exists()) confPath = defaultConf;
        }
        if (confPath != null) System.setProperty("moqui.conf", confPath);
    }

    protected static class MoquiShutdown extends Thread {
        final Method callMethod;
        final Object callObject;
        final List<JarFile> jarFileList;
        MoquiShutdown(Method callMethod, Object callObject, List<JarFile> jarFileList) {
            super();
            this.callMethod = callMethod;
            this.callObject = callObject;
            this.jarFileList = jarFileList;
        }
        public void run() {
            // run this first, ie shutdown the container before closing jarFiles to avoid errors with classes missing
            if (callMethod != null) {
                try { callMethod.invoke(callObject); } catch (Exception e) { System.out.println("Error in shutdown: " + e.toString()); }
            }

            // give things a couple seconds to destroy; this way of running is mostly for dev/test where this should be sufficient
            try { synchronized (this) { this.wait(2000); } } catch (Exception e) { System.out.println("Shutdown wait interrupted"); }
            System.out.println("========== Shutting down Moqui Executable (closing jars, etc) ==========");

            // close all jarFiles so they will "deleteOnExit"
            for (JarFile jarFile : jarFileList) {
                try {
                    jarFile.close();
                } catch (IOException e) {
                    System.out.println("Error closing jar [" + jarFile + "]: " + e.toString());
                }
            }
        }
    }

    protected JarFile outerFile = null;
    protected final ArrayList<JarFile> jarFileList = new ArrayList<>();
    protected final Map<String, Class<?>> classCache = new HashMap<>();
    protected final Map<String, URL> resourceCache = new HashMap<>();
    protected ProtectionDomain pd;
    protected final boolean loadWebInf;

    public MoquiStart(boolean loadWebInf) {
        this(ClassLoader.getSystemClassLoader(), loadWebInf);
    }

    public MoquiStart(ClassLoader parent, boolean loadWebInf) {
        super(parent);
        this.loadWebInf = loadWebInf;

        URL wrapperWarUrl = null;
        try {
            // get outer file (the war file)
            pd = getClass().getProtectionDomain();
            CodeSource cs = pd.getCodeSource();
            wrapperWarUrl = cs.getLocation();
            outerFile = new JarFile(new File(wrapperWarUrl.toURI()));

            // allow for classes in the outerFile as well
            jarFileList.add(outerFile);

            Enumeration<JarEntry> jarEntries = outerFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry je = jarEntries.nextElement();
                if (je.isDirectory()) continue;
                // if we aren't loading the WEB-INF files and it is one, skip it
                if (!loadWebInf && je.getName().startsWith("WEB-INF")) continue;
                // get jars, can be anywhere in the file
                String jeName = je.getName().toLowerCase();
                if (jeName.lastIndexOf(".jar") == jeName.length() - 4) {
                    File file = createTempFile(je);
                    jarFileList.add(new JarFile(file));
                }
            }
        } catch (Exception e) {
            System.out.println("Error loading jars in war file [" + wrapperWarUrl + "]: " + e.toString());
        }
    }

    protected File createTempFile(JarEntry je) throws IOException {
        byte[] jeBytes = getJarEntryBytes(outerFile, je);

        String tempName = je.getName().replace('/', '_') + ".";
        File tempDir = new File("execwartmp");
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

    protected byte[] getJarEntryBytes(JarFile jarFile, JarEntry je) throws IOException {
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
                    System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "] in war file [" + outerFile + "]: " + e.toString());
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
            // to better support war format, look for the resourceName in the WEB-INF/classes directory
            if (loadWebInf && jarEntry == null) jarEntry = jarFile.getJarEntry(webInfResourceName);
            if (jarEntry != null) {
                try {
                    String jarFileName = jarFile.getName();
                    if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                    urlList.add(new URL("jar:file:" + jarFileName + "!/" + jarEntry));
                } catch (MalformedURLException e) {
                    System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "] in war file [" + outerFile + "]: " + e.toString());
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
                System.out.println("Error loading class [" + className + "] from jars in war file [" + outerFile.getName() + "]: " + e.toString());
                e.printStackTrace();
            }

            throw new ClassNotFoundException("Class [" + className + "] not found");
        } finally {
            if (c != null  &&  resolve) {
                resolveClass(c);
            }
        }
    }

    protected Class<?> findJarClass(String className) throws IOException, ClassFormatError {
        if (classCache.containsKey(className)) return classCache.get(className);

        Class<?> c = null;
        String classFileName = className.replace('.', '/') + ".class";
        String webInfFileName = "WEB-INF/classes/" + classFileName;
        int jarFileListSize = jarFileList.size();
        for (int i = 0; i < jarFileListSize; i++) {
            JarFile jarFile = jarFileList.get(i);
            // System.out.println("Finding Class [" + className + "] in jarFile [" + jarFile.getName() + "]");

            JarEntry jarEntry = jarFile.getJarEntry(classFileName);
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
                c = defineClass(className, jeBytes, 0, jeBytes.length, pd);
                break;
            }
        }
        classCache.put(className, c);
        return c;
    }

    protected void definePackage(String className, JarFile jarFile) throws IllegalArgumentException {
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

    protected URL getSealURL(Manifest mf) {
        String seal = mf.getMainAttributes().getValue(Attributes.Name.SEALED);
        if (seal == null) return null;
        try {
            return new URL(seal);
        } catch (MalformedURLException e) {
            return null;
        }
    }
}
