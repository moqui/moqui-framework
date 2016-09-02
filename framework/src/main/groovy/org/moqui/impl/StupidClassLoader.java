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
package org.moqui.impl;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A caching ClassLoader that allows addition of JAR files and class directories to the classpath at runtime.
 *
 * This loads resources from its class directories and JAR files first, then tries the parent. This is not the standard
 * approach, but needed for configuration in moqui/runtime and components to override other classpath resources.
 *
 * This loads classes from the parent first, then its class directories and JAR files.
 */
public class StupidClassLoader extends ClassLoader {
    private static final boolean checkJars = false;
    private static final boolean rememberClassNotFound = false;
    private static final boolean rememberResourceNotFound = true;

    public static final Map<String, Class<?>> commonJavaClassesMap = createCommonJavaClassesMap();
    private static Map<String, Class<?>> createCommonJavaClassesMap() {
        Map<String, Class<?>> m = new HashMap<>();
        m.put("java.lang.String",java.lang.String.class); m.put("String", java.lang.String.class);
        m.put("java.lang.CharSequence",java.lang.CharSequence.class); m.put("CharSequence", java.lang.CharSequence.class);
        m.put("java.sql.Timestamp", java.sql.Timestamp.class); m.put("Timestamp", java.sql.Timestamp.class);
        m.put("java.sql.Time", java.sql.Time.class); m.put("Time", java.sql.Time.class);
        m.put("java.sql.Date", java.sql.Date.class); m.put("Date", java.sql.Date.class);
        m.put("java.util.Locale", java.util.Locale.class); m.put("java.util.TimeZone", java.util.TimeZone.class);
        m.put("java.lang.Byte", java.lang.Byte.class); m.put("java.lang.Character", java.lang.Character.class);
        m.put("java.lang.Integer", java.lang.Integer.class); m.put("Integer", java.lang.Integer.class);
        m.put("java.lang.Long", java.lang.Long.class); m.put("Long", java.lang.Long.class);
        m.put("java.lang.Short", java.lang.Short.class);
        m.put("java.lang.Float", java.lang.Float.class); m.put("Float", java.lang.Float.class);
        m.put("java.lang.Double", java.lang.Double.class); m.put("Double", java.lang.Double.class);
        m.put("java.math.BigDecimal", java.math.BigDecimal.class); m.put("BigDecimal", java.math.BigDecimal.class);
        m.put("java.math.BigInteger", java.math.BigInteger.class); m.put("BigInteger", java.math.BigInteger.class);
        m.put("java.lang.Boolean", java.lang.Boolean.class); m.put("Boolean", java.lang.Boolean.class);
        m.put("java.lang.Object", java.lang.Object.class); m.put("Object", java.lang.Object.class);
        m.put("java.sql.Blob", java.sql.Blob.class); m.put("Blob", java.sql.Blob.class);
        m.put("java.nio.ByteBuffer", java.nio.ByteBuffer.class);
        m.put("java.sql.Clob", java.sql.Clob.class); m.put("Clob", java.sql.Clob.class);
        m.put("java.util.Date", java.util.Date.class);
        m.put("java.util.Collection", java.util.Collection.class); m.put("Collection", java.util.Collection.class);
        m.put("java.util.List", java.util.List.class); m.put("List", java.util.List.class);
        m.put("java.util.ArrayList", java.util.ArrayList.class); m.put("ArrayList", java.util.ArrayList.class);
        m.put("java.util.Map", java.util.Map.class); m.put("Map", java.util.Map.class); m.put("java.util.HashMap", java.util.HashMap.class);
        m.put("java.util.Set", java.util.Set.class); m.put("Set", java.util.Set.class); m.put("java.util.HashSet", java.util.HashSet.class);
        m.put("groovy.util.Node", groovy.util.Node.class); m.put("Node", groovy.util.Node.class);
        m.put("org.moqui.util.MNode", org.moqui.util.MNode.class); m.put("MNode", org.moqui.util.MNode.class);
        m.put(Boolean.TYPE.getName(), Boolean.TYPE); m.put(Short.TYPE.getName(), Short.TYPE);
        m.put(Integer.TYPE.getName(), Integer.TYPE); m.put(Long.TYPE.getName(), Long.TYPE);
        m.put(Float.TYPE.getName(), Float.TYPE); m.put(Double.TYPE.getName(), Double.TYPE);
        m.put(Byte.TYPE.getName(), Byte.TYPE); m.put(Character.TYPE.getName(), Character.TYPE);
        m.put("org.moqui.entity.EntityValue", org.moqui.entity.EntityValue.class); m.put("EntityValue", org.moqui.entity.EntityValue.class);
        m.put("org.moqui.entity.EntityList", org.moqui.entity.EntityList.class); m.put("EntityList", org.moqui.entity.EntityList.class);
        m.put("long[]", long[].class); m.put("char[]", char[].class);
        return m;
    }

    private final ArrayList<JarFile> jarFileList = new ArrayList<>();
    private final ArrayList<File> classesDirectoryList = new ArrayList<>();

    private final HashMap<String, File> knownClassFiles = new HashMap<>();
    private final HashMap<String, JarEntryInfo> knownClassJarEntries = new HashMap<>();
    private static class JarEntryInfo {
        JarEntry entry;
        JarFile file;
        JarEntryInfo(JarEntry je, JarFile jf) { entry = je; file = jf; }
    }


    // This Map contains either a Class or a ClassNotFoundException, cached for fast access because Groovy hits a LOT of
    //     weird invalid class names resulting in expensive new ClassNotFoundException instances
    private final ConcurrentHashMap<String, Class> classCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClassNotFoundException> notFoundCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, URL> resourceCache = new ConcurrentHashMap<>();
    private final Set<String> resourcesNotFound = new HashSet<>();
    private ProtectionDomain pd;

    public StupidClassLoader(ClassLoader parent) {
        super(parent);

        if (parent == null) throw new IllegalArgumentException("Parent ClassLoader cannot be null");

        pd = getClass().getProtectionDomain();

        for (Map.Entry<String, Class<?>> commonClassEntry: commonJavaClassesMap.entrySet())
            classCache.put(commonClassEntry.getKey(), commonClassEntry.getValue());
    }

    private static final Map<String, String> jarByClass = new HashMap<>();
    public void addJarFile(JarFile jf) {
        jarFileList.add(jf);

        String jfName = jf.getName();
        Enumeration<JarEntry> jeEnum = jf.entries();
        while (jeEnum.hasMoreElements()) {
            JarEntry je = jeEnum.nextElement();
            if (je.isDirectory()) continue;
            String jeName = je.getName();
            if (!jeName.endsWith(".class")) continue;
            String className = jeName.substring(0, jeName.length() - 6).replace('/', '.');

            if (classCache.containsKey(className)) {
                System.out.println("Ignoring duplicate class " + className + " in jar " + jfName);
                continue;
            }
            knownClassJarEntries.put(className, new JarEntryInfo(je, jf));

            /* NOTE: can't do this as classes are defined out of order, end up with NoClassDefFoundError for dependencies:
            Class<?> cls = makeClass(className, jf, je);
            if (cls != null) classCache.put(className, cls);
            */

            if (checkJars) {
                try {
                    getParent().loadClass(className);
                    System.out.println("Class " + className + " in jar " + jfName + " already loaded from parent ClassLoader");
                } catch (ClassNotFoundException e) { /* hoping class is not found! */ }
                if (jarByClass.containsKey(className)) {
                    System.out.println("Found class " + className + " in \njar " + jfName + ", already loaded from \njar " + jarByClass.get(className));
                } else {
                    jarByClass.put(className, jfName);
                }
            }
        }
    }
    //List<JarFile> getJarFileList() { return jarFileList; }
    //Map<String, Class> getClassCache() { return classCache; }
    //Map<String, URL> getResourceCache() { return resourceCache; }

    public void addClassesDirectory(File classesDir) {
        if (!classesDir.exists()) throw new IllegalArgumentException("Classes directory [" + classesDir + "] does not exist.");
        if (!classesDir.isDirectory()) throw new IllegalArgumentException("Classes directory [" + classesDir + "] is not a directory.");
        classesDirectoryList.add(classesDir);
        findClassFiles("", classesDir);
    }
    private void findClassFiles(String pathSoFar, File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        String pathSoFarDot = pathSoFar.concat(".");
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            String fileName = child.getName();
            if (child.isDirectory()) {
                findClassFiles(pathSoFarDot.concat(fileName), child);
            } else if (fileName.endsWith(".class")) {
                String className = pathSoFarDot.concat(fileName.substring(0, fileName.length() - 6));
                if (knownClassFiles.containsKey(className)) {
                    System.out.println("Ignoring duplicate class " + className + " at " + child.getPath());
                    continue;
                }
                knownClassFiles.put(className, child);

                /* NOTE: can't do this as classes are defined out of order, end up with NoClassDefFoundError for dependencies:
                Class<?> cls = makeClass(className, child);
                if (cls != null) classCache.put(className, cls);
                */
            }
        }
    }

    public void clearNotFoundInfo() {
        notFoundCache.clear();
        resourcesNotFound.clear();
    }


    /** @see java.lang.ClassLoader#getResource(String) */
    @Override
    public URL getResource(String name) {
        return findResource(name);
    }

    /** @see java.lang.ClassLoader#getResources(String) */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return findResources(name);
    }

    /** @see java.lang.ClassLoader#findResource(java.lang.String) */
    @Override
    protected URL findResource(String resourceName) {
        URL cachedUrl = resourceCache.get(resourceName);
        if (cachedUrl != null) return cachedUrl;
        if (rememberResourceNotFound && resourcesNotFound.contains(resourceName)) return null;

        // Groovy looks for BeanInfo and Customizer groovy resources, even for anonymous scripts and they will never exist
        if (rememberResourceNotFound) {
            if ((resourceName.endsWith("BeanInfo.groovy") || resourceName.endsWith("Customizer.groovy")) &&
                    (resourceName.startsWith("script") || resourceName.contains("_actions") || resourceName.contains("_condition"))) {
                resourcesNotFound.add(resourceName);
                return null;
            }
        }

        URL resourceUrl = null;

        int classesDirectoryListSize = classesDirectoryList.size();
        for (int i = 0; i < classesDirectoryListSize; i++) {
            File classesDir = classesDirectoryList.get(i);
            File testFile = new File(classesDir.getAbsolutePath() + "/" + resourceName);
            try {
                if (testFile.exists() && testFile.isFile()) resourceUrl = testFile.toURI().toURL();
            } catch (MalformedURLException e) {
                System.out.println("Error making URL for [" + resourceName + "] in classes directory [" + classesDir + "]: " + e.toString());
            }
        }

        if (resourceUrl == null) {
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                JarEntry jarEntry = jarFile.getJarEntry(resourceName);
                if (jarEntry != null) {
                    try {
                        String jarFileName = jarFile.getName();
                        if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                        resourceUrl = new URL("jar:file:" + jarFileName + "!/" + jarEntry);
                    } catch (MalformedURLException e) {
                        System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "]: " + e.toString());
                    }
                }
            }
        }

        if (resourceUrl == null) resourceUrl = getParent().getResource(resourceName);
        if (resourceUrl != null) {
            URL existingUrl = resourceCache.putIfAbsent(resourceName, resourceUrl);
            if (existingUrl != null) return existingUrl;
            else return resourceUrl;
        } else {
            // for testing to see if resource not found cache is working, should see this once for each not found resource
            // System.out.println("Classpath resource not found with name " + resourceName);
            if (rememberResourceNotFound) resourcesNotFound.add(resourceName);
            return null;
        }
    }

    /** @see java.lang.ClassLoader#findResources(java.lang.String) */
    @Override
    public Enumeration<URL> findResources(String resourceName) throws IOException {
        List<URL> urlList = new ArrayList<>();
        int classesDirectoryListSize = classesDirectoryList.size();
        for (int i = 0; i < classesDirectoryListSize; i++) {
            File classesDir = classesDirectoryList.get(i);
            File testFile = new File(classesDir.getAbsolutePath() + "/" + resourceName);
            try {
                if (testFile.exists() && testFile.isFile()) urlList.add(testFile.toURI().toURL());
            } catch (MalformedURLException e) {
                System.out.println("Error making URL for [" + resourceName + "] in classes directory [" + classesDir + "]: " + e.toString());
            }
        }
        int jarFileListSize = jarFileList.size();
        for (int i = 0; i < jarFileListSize; i++) {
            JarFile jarFile = jarFileList.get(i);
            JarEntry jarEntry = jarFile.getJarEntry(resourceName);
            if (jarEntry != null) {
                try {
                    String jarFileName = jarFile.getName();
                    if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                    urlList.add(new URL("jar:file:" + jarFileName + "!/" + jarEntry));
                } catch (MalformedURLException e) {
                    System.out.println("Error making URL for [" + resourceName + "] in jar [" + jarFile + "]: " + e.toString());
                }
            }
        }
        // add all resources found in parent loader too
        Enumeration<URL> superResources = getParent().getResources(resourceName);
        while (superResources.hasMoreElements()) urlList.add(superResources.nextElement());
        return Collections.enumeration(urlList);
    }

    /** @see java.lang.ClassLoader#getResourceAsStream(String) */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL resourceUrl = findResource(name);
        if (resourceUrl == null) {
            // System.out.println("Classpath resource not found with name " + name);
            return null;
        }
        try {
            return resourceUrl.openStream();
        } catch (IOException e) {
            System.out.println("Error opening stream for classpath resource " + name + ": " + e.toString());
            return null;
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException { return loadClass(name, false); }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        Class cachedClass = classCache.get(className);
        if (cachedClass != null) return cachedClass;
        if (rememberClassNotFound) {
            ClassNotFoundException cachedExc = notFoundCache.get(className);
            if (cachedExc != null) throw cachedExc;
        }

        return loadClassInternal(className, resolve);
    }

    // private static final ArrayList<String> ignoreSuffixes = new ArrayList<>(Arrays.asList("Customizer", "BeanInfo"));
    // private static final int ignoreSuffixesSize = ignoreSuffixes.size();
    // TODO: does this need synchronized? slows it down...
    private Class<?> loadClassInternal(String className, boolean resolve) throws ClassNotFoundException {
        /* This may not be a good idea, Groovy looks for all sorts of bogus class name but there may be a reason so not doing this or looking for other patterns:
        for (int i = 0; i < ignoreSuffixesSize; i++) {
            String ignoreSuffix = ignoreSuffixes.get(i);
            if (className.endsWith(ignoreSuffix)) {
                ClassNotFoundException cfne = new ClassNotFoundException("Ignoring Groovy style bogus class name " + className);
                classCache.put(className, cfne);
                throw cfne;
            }
        }
        */

        Class<?> c = null;
        try {
            // classes handled opposite of resources, try parent first (avoid java.lang.LinkageError)
            try {
                ClassLoader cl = getParent();
                c = cl.loadClass(className);
            } catch (ClassNotFoundException|NoClassDefFoundError e) {
                // do nothing, common that class won't be found if expected in additional JARs and class directories
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }

            if (c == null) {
                try {
                    File classFile = knownClassFiles.get(className);
                    if (classFile != null) c = makeClass(className, classFile);
                    if (c == null) {
                        JarEntryInfo jei = knownClassJarEntries.get(className);
                        if (jei != null) c = makeClass(className, jei.file, jei.entry);
                    }

                    // old approach search through all class dirs and jars
                    // c = findJarClass(className);
                } catch (Exception e) {
                    System.out.println("Error loading class [" + className + "] from additional jars: " + e.toString());
                    e.printStackTrace();
                }
            }

            // System.out.println("Loading class name [" + className + "] got class: " + c);
            if (c == null) {
                ClassNotFoundException cnfe = new ClassNotFoundException("Class " + className + " not found.");
                if (rememberClassNotFound) {
                    // Groovy seems to look, then re-look, for funny names like:
                    //     groovy.lang.GroovyObject$java$io$org$moqui$entity$EntityListIterator
                    //     java.io.org$moqui$entity$EntityListIterator
                    //     groovy.util.org$moqui$context$ExecutionContext
                    //     org$moqui$context$ExecutionContext
                    // Groovy does similar with *Customizer and *BeanInfo; so just don't remember any of these
                    // In general it seems that anything with a '$' needs to be excluded
                    if (!className.contains("$") && !className.endsWith("Customizer") && !className.endsWith("BeanInfo")) {
                        ClassNotFoundException existingExc = notFoundCache.putIfAbsent(className, cnfe);
                        if (existingExc != null) throw existingExc;
                    }
                }
                throw cnfe;
            } else {
                classCache.put(className, c);
            }
            return c;
        } finally {
            if (c != null && resolve) resolveClass(c);
        }
    }

    private Class<?> makeClass(String className, File classFile) {
        try {
            byte[] jeBytes = getFileBytes(classFile);
            if (jeBytes == null) {
                System.out.println("Could not get bytes for " + classFile);
                return null;
            }
            return defineClass(className, jeBytes, 0, jeBytes.length, pd);
        } catch (Throwable t) {
            System.out.println("Error reading class file " + classFile + ": " + t.toString());
            return null;
        }
    }
    private Class<?> makeClass(String className, JarFile file, JarEntry entry) {
        try {
            definePackage(className, file);
            byte[] jeBytes = getJarEntryBytes(file, entry);
            if (jeBytes == null) {
                System.out.println("Could not get bytes for entry " + entry.getName() + " in jar" + file.getName());
                return null;
            } else {
                // System.out.println("Loading class " + className + " from " + entry.getName() + " in " + file.getName());
                return defineClass(className, jeBytes, 0, jeBytes.length, pd);
            }
        } catch (Throwable t) {
            System.out.println("Error reading class file " + entry.getName() + " in jar" + file.getName() + ": " + t.toString());
            return null;
        }
    }
    @SuppressWarnings("ThrowFromFinallyBlock")
    private byte[] getJarEntryBytes(JarFile jarFile, JarEntry je) throws IOException {
        DataInputStream dis = null;
        byte[] jeBytes = null;
        try {
            long lSize = je.getSize();
            if (lSize <= 0 || lSize >= Integer.MAX_VALUE)
                throw new IllegalArgumentException("Size [" + lSize + "] not valid for jar entry [" + je + "]");
            jeBytes = new byte[(int) lSize];
            InputStream is = jarFile.getInputStream(je);
            dis = new DataInputStream(is);
            dis.readFully(jeBytes);
        } finally {
            if (dis != null) dis.close();
        }
        return jeBytes;
    }

    @SuppressWarnings("ThrowFromFinallyBlock")
    private byte[] getFileBytes(File classFile) throws IOException {
        DataInputStream dis = null;
        byte[] jeBytes = null;
        try {
            long lSize = classFile.length();
            if (lSize <= 0  ||  lSize >= Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Size [" + lSize + "] not valid for classpath file [" + classFile + "]");
            }
            jeBytes = new byte[(int)lSize];
            InputStream is = new FileInputStream(classFile);
            dis = new DataInputStream(is);
            dis.readFully(jeBytes);
        } finally {
            if (dis != null) dis.close();
        }
        return jeBytes;
    }

    private Class<?> findJarClass(String className) throws IOException, ClassFormatError, ClassNotFoundException {
        Class cachedClass = classCache.get(className);
        if (cachedClass != null) return cachedClass;
        if (rememberClassNotFound) {
            ClassNotFoundException cachedExc = notFoundCache.get(className);
            if (cachedExc != null) throw cachedExc;
        }

        Class<?> c = null;
        String classFileName = className.replace('.', '/').concat(".class");

        int classesDirectoryListSize = classesDirectoryList.size();
        for (int i = 0; i < classesDirectoryListSize; i++) {
            File classesDir = classesDirectoryList.get(i);
            File testFile = new File(classesDir.getAbsolutePath() + "/" + classFileName);
            if (testFile.exists() && testFile.isFile()) {
                c = makeClass(className, testFile);
                if (c != null) break;
            }
        }

        if (c == null) {
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                // System.out.println("Finding class file " + classFileName + " in jar file " + jarFile.getName());
                JarEntry jarEntry = jarFile.getJarEntry(classFileName);
                if (jarEntry != null) {
                    c = makeClass(className, jarFile, jarEntry);
                    break;
                }
            }
        }

        // down here only cache if found
        if (c != null) {
            Class existingClass = classCache.putIfAbsent(className, c);
            if (existingClass != null) return existingClass;
            else return c;
        } else {
            return null;
        }
    }

    private void definePackage(String className, JarFile jarFile) throws IllegalArgumentException {
        Manifest mf;
        try {
            mf = jarFile.getManifest();
        } catch (IOException e) {
            // use default manifest
            mf = new Manifest();
        }
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
