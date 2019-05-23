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
package org.moqui.resource;

import org.moqui.BaseException;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class UrlResourceReference extends ResourceReference {
    private static final Logger logger = LoggerFactory.getLogger(UrlResourceReference.class);
    static final String runtimePrefix = "runtime://";
    URL locationUrl = null;
    Boolean exists = null;
    boolean isFileProtocol = false;
    private transient File localFile = null;

    public UrlResourceReference() { }
    public UrlResourceReference(File file) {
        isFileProtocol = true;
        localFile = file;
        try { locationUrl = file.toURI().toURL(); }
        catch (MalformedURLException e) { throw new BaseException("Error creating URL for file " + file.getAbsolutePath(), e); }
    }

    @Override
    public ResourceReference init(String location) {
        if (location == null || location.isEmpty()) throw new BaseException("Cannot create URL Resource Reference with empty location");

        if (location.startsWith(runtimePrefix)) location = location.substring(runtimePrefix.length());

        if (location.startsWith("/") || !location.contains(":")) {
            // no prefix, local file: if starts with '/' is absolute, otherwise is relative to runtime path
            if (location.charAt(0) != '/') {
                String moquiRuntime = System.getProperty("moqui.runtime");
                if (moquiRuntime != null && !moquiRuntime.isEmpty()) {
                    File runtimeFile = new File(moquiRuntime);
                    location = runtimeFile.getAbsolutePath() + "/" + location;
                }
            }

            try { locationUrl = new URL("file:" + location); }
            catch (MalformedURLException e) { throw new BaseException("Invalid file url for location " + location, e); }
            isFileProtocol = true;
        } else {
            try {
                locationUrl = new URL(location);
            } catch (MalformedURLException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Ignoring MalformedURLException for location, trying a local file: " + e.toString());
                // special case for Windows, try going through a file:

                try { locationUrl = new URL("file:/" + location); }
                catch (MalformedURLException se) { throw new BaseException("Invalid url for location " + location, e); }
            }

            isFileProtocol = "file".equals(getUrl().getProtocol());
        }

        return this;
    }

    public File getFile() {
        if (!isFileProtocol) throw new IllegalArgumentException("File not supported for resource with protocol [" + locationUrl.getProtocol() + "]");
        if (localFile != null) return localFile;
        // NOTE: using toExternalForm().substring(5) instead of toURI because URI does not allow spaces in a filename
        localFile = new File(locationUrl.toExternalForm().substring(5));
        return localFile;
    }

    @Override public ResourceReference createNew(String location) {
        UrlResourceReference resRef = new UrlResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override public String getLocation() { return locationUrl.toString(); }

    @Override public InputStream openStream() {
        try {
            return locationUrl.openStream();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new BaseException("Error opening stream for " + locationUrl.toString(), e);
        }
    }

    @Override public OutputStream getOutputStream() {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Write not supported for resource [" + url.toString() + "] with protocol [" + url.getProtocol() + "]");
        }

        // first make sure the directory exists that this is in
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();
        try {
            return new FileOutputStream(curFile);
        } catch (FileNotFoundException e) {
            throw new BaseException("Error opening output stream for file " + curFile.getAbsolutePath(), e);
        }
    }

    @Override public String getText() { return ObjectUtilities.getStreamText(openStream()); }
    @Override public boolean supportsAll() { return isFileProtocol; }
    @Override public boolean supportsUrl() { return true; }

    @Override public URL getUrl() { return locationUrl; }

    @Override public boolean supportsDirectory() { return isFileProtocol; }
    @Override public boolean isFile() {
        if (isFileProtocol) {
            return getFile().isFile();
        } else {
            throw new IllegalArgumentException("Is file not supported for resource with protocol [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override public boolean isDirectory() {
        if (isFileProtocol) {
            return getFile().isDirectory();
        } else {
            throw new IllegalArgumentException("Is directory not supported for resource with protocol [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override public List<ResourceReference> getDirectoryEntries() {
        if (isFileProtocol) {
            File f = getFile();
            List<ResourceReference> children = new ArrayList<>();
            String baseLocation = getLocation();
            if (baseLocation.endsWith("/")) baseLocation = baseLocation.substring(0, baseLocation.length() - 1);
            File[] listFiles = f.listFiles();
            TreeSet<String> fileNameSet = new TreeSet<>();
            if (listFiles != null) for (File dirFile : listFiles) fileNameSet.add(dirFile.getName());
            for (String filename : fileNameSet) children.add(new UrlResourceReference().init(baseLocation + "/" + filename));
            return children;
        } else {
            throw new IllegalArgumentException("Children not supported for resource with protocol [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override public boolean supportsExists() { return isFileProtocol || exists != null; }

    @Override public boolean getExists() {
        // only count exists if true
        if (exists != null && exists) return true;

        if (isFileProtocol) {
            exists = getFile().exists();
            return exists;
        } else {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Exists not supported for resource with protocol [" + (url == null ? null : url.getProtocol()) + "]");
        }
    }

    @Override public boolean supportsLastModified() { return isFileProtocol; }
    @Override public long getLastModified() {
        if (isFileProtocol) {
            return getFile().lastModified();
        } else {
            return System.currentTimeMillis();
        }
    }

    @Override public boolean supportsSize() { return isFileProtocol; }
    @Override public long getSize() { return isFileProtocol ? getFile().length() : 0; }

    @Override public boolean supportsWrite() { return isFileProtocol; }
    @Override public void putText(String text) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Write not supported for resource [" + getLocation() + "] with protocol [" + (url == null ? null : getUrl().getProtocol()) + "]");
        }

        // first make sure the directory exists that this is in
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();
        // now write the text to the file and close it
        try {
            Writer fw = new OutputStreamWriter(new FileOutputStream(curFile), StandardCharsets.UTF_8);
            fw.write(text);
            fw.close();
            this.exists = null;
        } catch (IOException e) {
            throw new BaseException("Error writing text to file " + curFile.getAbsolutePath(), e);
        }
    }

    @Override public void putStream(InputStream stream) {
        if (!isFileProtocol) {
            throw new IllegalArgumentException("Write not supported for resource [" + locationUrl + "] with protocol [" + (locationUrl == null ? null : locationUrl.getProtocol()) + "]");
        }

        // first make sure the directory exists that this is in
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();

        try {
            OutputStream os = new FileOutputStream(curFile);
            ObjectUtilities.copyStream(stream, os);
            stream.close();
            os.close();
            this.exists = null;
        } catch (IOException e) {
            throw new BaseException("Error writing stream to file " + curFile.getAbsolutePath(), e);
        }
    }

    @Override public void move(final String newLocation) {
        if (newLocation == null || newLocation.isEmpty())
            throw new IllegalArgumentException("No location specified, not moving resource at " + getLocation());
        ResourceReference newRr = createNew(newLocation);

        if (!newRr.getUrl().getProtocol().equals("file")) throw new IllegalArgumentException("Location [" + newLocation + "] is not a file location, not moving resource at " + getLocation());
        if (!isFileProtocol) throw new IllegalArgumentException("Move not supported for resource [" + locationUrl + "] with protocol [" + (locationUrl == null ? null : locationUrl.getProtocol()) + "]");

        File curFile = getFile();
        if (!curFile.exists()) return;

        String path = newRr.getUrl().toExternalForm().substring(5);
        File newFile = new File(path);
        File newFileParent = newFile.getParentFile();
        if (newFileParent != null && !newFileParent.exists()) newFileParent.mkdirs();
        curFile.renameTo(newFile);
    }

    @Override public ResourceReference makeDirectory(final String name) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Write not supported for resource [" + getLocation() + "] with protocol [" + (url == null ? null : url.getProtocol()) + "]");
        }

        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init(getLocation() + "/" + name);
        newRef.getFile().mkdirs();
        return newRef;
    }

    @Override public ResourceReference makeFile(final String name) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Write not supported for resource [" + getLocation() + "] with protocol [" + (url == null ? null : url.getProtocol()) + "]");
        }

        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init(getLocation() + "/" + name);
        // first make sure the directory exists that this is in
        if (!getFile().exists()) getFile().mkdirs();
        try {
            newRef.getFile().createNewFile();
            return newRef;
        } catch (IOException e) {
            throw new BaseException("Error writing text to file " + newRef.getLocation(), e);
        }
    }

    @Override
    public boolean delete() {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("Write not supported for resource [" + getLocation() + "] with protocol [" + (url == null ? null : url.getProtocol()) + "]");
        }

        return getFile().delete();
    }
}
