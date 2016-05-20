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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class StupidClassLoader extends ClassLoader {
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
    // This Map contains either a Class or a ClassNotFoundException, cached for fast access because Groovy hits a LOT of
    //     weird invalid class names resulting in expensive new ClassNotFoundException instances
    private final Map<String, Object> classCache = new HashMap<>();
    private final Map<String, URL> resourceCache = new HashMap<>();
    private ProtectionDomain pd;

    public StupidClassLoader(ClassLoader parent) {
        super(parent);

        if (parent == null) throw new IllegalArgumentException("Parent ClassLoader cannot be null");

        pd = getClass().getProtectionDomain();

        for (Map.Entry commonClassEntry: commonJavaClassesMap.entrySet())
            classCache.put((String) commonClassEntry.getKey(), (Class) commonClassEntry.getValue());
    }

    public void addJarFile(JarFile jf) { jarFileList.add(jf); }
    //List<JarFile> getJarFileList() { return jarFileList; }
    //Map<String, Class> getClassCache() { return classCache; }
    //Map<String, URL> getResourceCache() { return resourceCache; }

    public void addClassesDirectory(File classesDir) {
        if (!classesDir.exists()) throw new IllegalArgumentException("Classes directory [" + classesDir + "] does not exist.");
        if (!classesDir.isDirectory()) throw new IllegalArgumentException("Classes directory [" + classesDir + "] is not a directory.");
        classesDirectoryList.add(classesDir);
    }

    protected byte[] getJarEntryBytes(JarFile jarFile, JarEntry je) throws IOException {
        DataInputStream dis = null;
        byte[] jeBytes = null;
        try {
            long lSize = je.getSize();
            if (lSize <= 0  ||  lSize >= Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Size [" + lSize + "] not valid for jar entry [" + je + "]");
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

    protected byte[] getFileBytes(File classFile) throws IOException {
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

    /**
     * @see java.lang.ClassLoader#getResource(String)
     */
    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        if (url == null && getParent() !=null) {
            url = getParent().getResource(name);
        }
        return url;
    }

    /**
     * @see java.lang.ClassLoader#getResources(String)
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> tmp = new ArrayList<>();
        tmp.addAll(Collections.list(findResources(name)));
        if(getParent() != null) {
            tmp.addAll(Collections.list(getParent().getResources(name)));
        }
        return Collections.enumeration(tmp);
    }

    /** @see java.lang.ClassLoader#findResource(java.lang.String) */
    @Override
    protected URL findResource(String resourceName) {
        if (resourceCache.containsKey(resourceName)) return resourceCache.get(resourceName);

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

        if (resourceUrl == null) resourceUrl = super.findResource(resourceName);
        resourceCache.put(resourceName, resourceUrl);
        return resourceUrl;
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
        Enumeration<URL> superResources = super.findResources(resourceName);
        while (superResources.hasMoreElements()) urlList.add(superResources.nextElement());
        return Collections.enumeration(urlList);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException { return loadClass(name, false); }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        Object cachedValue = classCache.get(className);
        if (cachedValue != null) {
            if (cachedValue instanceof Class) {
                return (Class) cachedValue;
            } else if (cachedValue instanceof ClassNotFoundException) {
                throw (ClassNotFoundException) cachedValue;
            }
            // should never be an instance of anything else, if it is fall through and try to load the class
        }
        return loadClassInternal(className, resolve);
    }

    static final ArrayList<String> ignoreSuffixes = new ArrayList<>(Arrays.asList("Customizer", "BeanInfo"));
    static final int ignoreSuffixesSize = ignoreSuffixes.size();
    protected synchronized Class<?> loadClassInternal(String className, boolean resolve) throws ClassNotFoundException {
        /* This may be a good idea, Groovy looks for all sorts of bogus class name but there may be a reason so not doing this or looking for other patterns:
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
            try {
                c = findJarClass(className);
            } catch (Exception e) {
                System.out.println("Error loading class [" + className + "] from additional jars: " + e.toString());
                e.printStackTrace();
            }

            if (c == null) {
                try {
                    ClassLoader cl = getParent();
                    c = cl.loadClass(className);
                } catch (ClassNotFoundException e) {
                    // remember that the class is not found
                    classCache.put(className, e);
                    // System.out.println("Class " + className + " not found (ClassNotFoundException was thrown)");
                    // e.printStackTrace();
                    throw e;
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    throw e;
                }
            }

            // System.out.println("Loading class name [" + className + "] got class: " + c);
            if (c == null) {
                // this shouldn't generally happen, the parent ClassLoader should throw a ClassNotFoundException
                classCache.put(className, new ClassNotFoundException("Class " + className + " not found."));
                System.out.println("Class " + className + " not found and no ClassNotFoundException was thrown, creating new one.");
            } else {
                classCache.put(className, c);
            }
            return c;
        } finally {
            if (c != null && resolve) resolveClass(c);
        }
    }

    protected Class<?> findJarClass(String className) throws IOException, ClassFormatError {
        Object cachedValue = classCache.get(className);
        if (cachedValue != null) {
            if (cachedValue instanceof Class) {
                return (Class) cachedValue;
            } else {
                return null;
            }
        }

        Class<?> c = null;
        String classFileName = className.replace('.', '/') + ".class";

        int classesDirectoryListSize = classesDirectoryList.size();
        for (int i = 0; i < classesDirectoryListSize; i++) {
            File classesDir = classesDirectoryList.get(i);
            File testFile = new File(classesDir.getAbsolutePath() + "/" + classFileName);
            try {
                if (testFile.exists() && testFile.isFile()) {
                    byte[] jeBytes = getFileBytes(testFile);
                    if (jeBytes == null) {
                        System.out.println("Could not get bytes for [" + testFile + "] in [" + classesDir + "]");
                        continue;
                    }
                    // System.out.println("Class [" + classFileName + "] FOUND in jarFile [" + jarFile.getName() + "], size is " + jeBytes.length);
                    c = defineClass(className, jeBytes, 0, jeBytes.length, pd);
                    break;
                }
            } catch (MalformedURLException e) {
                System.out.println("Error making URL for [" + classFileName + "] in classes directory [" + classesDir + "]: " + e.toString());
            }
        }

        if (c == null) {
            int jarFileListSize = jarFileList.size();
            for (int i = 0; i < jarFileListSize; i++) {
                JarFile jarFile = jarFileList.get(i);
                // System.out.println("Finding class file " + classFileName + " in jar file " + jarFile.getName());
                JarEntry jarEntry = jarFile.getJarEntry(classFileName);
                if (jarEntry != null) {
                    definePackage(className, jarFile);
                    byte[] jeBytes = getJarEntryBytes(jarFile, jarEntry);
                    if (jeBytes == null) {
                        System.out.println("Could not get bytes for [" + jarEntry.getName() + "] in [" + jarFile.getName() + "]");
                        continue;
                    }
                    // System.out.println("Class [" + classFileName + "] FOUND in jarFile [" + jarFile.getName() + "], size is " + jeBytes.length);
                    c = defineClass(className, jeBytes, 0, jeBytes.length, pd);
                    break;
                }
            }
        }

        // down here only cache if found
        if (c != null) classCache.put(className, c);
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
