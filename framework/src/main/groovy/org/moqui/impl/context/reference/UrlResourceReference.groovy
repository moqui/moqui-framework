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
package org.moqui.impl.context.reference

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.util.ResourceReference
import org.moqui.impl.StupidUtilities
import org.moqui.BaseException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class UrlResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(UrlResourceReference.class)

    protected URL locationUrl = null
    protected Boolean exists = null
    protected boolean isFileProtocol = false
    protected transient File localFile = null

    UrlResourceReference() { }

    UrlResourceReference(File file, ExecutionContextFactory ecf) {
        this.ecf = ecf
        isFileProtocol = true
        localFile = file
        locationUrl = file.toURI().toURL()
    }

    @Override
    ResourceReference init(String location, ExecutionContextFactory ecf) {
        this.ecf = ecf
        if (!location) throw new BaseException("Cannot create URL Resource Reference with empty location")
        if (location.startsWith("/") || location.indexOf(":") < 0) {
            // no prefix, local file: if starts with '/' is absolute, otherwise is relative to runtime path
            if (location.charAt(0) != '/') location = ecf.runtimePath + '/' + location
            locationUrl = new URL("file:" + location)
            isFileProtocol = true
        } else {
            try {
                locationUrl = new URL(location)
            } catch (MalformedURLException e) {
                if (logger.isTraceEnabled()) logger.trace("Ignoring MalformedURLException for location, trying a local file: ${e.toString()}")
                // special case for Windows, try going through a file:
                locationUrl = new URL("file:/" + location)
            }
            isFileProtocol = (locationUrl?.protocol == "file")
        }
        return this
    }

    File getFile() {
        if (!isFileProtocol) throw new IllegalArgumentException("File not supported for resource with protocol [${locationUrl.protocol}]")
        if (localFile != null) return localFile
        // NOTE: using toExternalForm().substring(5) instead of toURI because URI does not allow spaces in a filename
        localFile = new File(locationUrl.toExternalForm().substring(5))
        return localFile
    }

    @Override
    String getLocation() { return locationUrl?.toString() }

    @Override
    InputStream openStream() { return locationUrl?.openStream() }
    @Override
    OutputStream getOutputStream() {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        // first make sure the directory exists that this is in
        if (!getFile().parentFile.exists()) getFile().parentFile.mkdirs()
        OutputStream os = new FileOutputStream(getFile())
        return os
    }

    @Override
    String getText() { return StupidUtilities.getStreamText(openStream()) }

    @Override
    boolean supportsAll() { isFileProtocol }

    @Override
    boolean supportsUrl() { return true }
    @Override
    URL getUrl() { return locationUrl }

    @Override
    boolean supportsDirectory() { isFileProtocol }
    @Override
    boolean isFile() {
        if (isFileProtocol) {
            return getFile().isFile()
        } else {
            throw new IllegalArgumentException("Is file not supported for resource with protocol [${locationUrl.protocol}]")
        }
    }
    @Override
    boolean isDirectory() {
        if (isFileProtocol) {
            return getFile().isDirectory()
        } else {
            throw new IllegalArgumentException("Is directory not supported for resource with protocol [${locationUrl.protocol}]")
        }
    }
    @Override
    List<ResourceReference> getDirectoryEntries() {
        if (isFileProtocol) {
            File f = getFile()
            List<ResourceReference> children = new LinkedList<ResourceReference>()
            String baseLocation = getLocation()
            if (baseLocation.endsWith("/")) baseLocation = baseLocation.substring(0, baseLocation.length() - 1)
            for (File dirFile in f.listFiles())
                children.add(new UrlResourceReference().init(baseLocation + "/" + dirFile.getName(), ecf))
            return children
        } else {
            throw new IllegalArgumentException("Children not supported for resource with protocol [${locationUrl.protocol}]")
        }
    }
    @Override
    ResourceReference getChild(String childName) {
        if (childName == null || childName.length() == 0) return null
        if (ecf.getResource() != null) {
            return super.getChild(childName)
        } else {
            File thisFile = getFile()
            File childFile = new File(thisFile, childName)
            return new UrlResourceReference(childFile, ecf)
        }
    }


    @Override
    boolean supportsExists() { return isFileProtocol || exists != null }
    @Override
    boolean getExists() {
        // only count exists if true
        if (exists) return true

        if (isFileProtocol) {
            exists = getFile().exists()
            return exists
        } else {
            throw new IllegalArgumentException("Exists not supported for resource with protocol [${locationUrl?.protocol}]")
        }
    }

    @Override
    boolean supportsLastModified() { isFileProtocol }
    @Override
    long getLastModified() {
        if (isFileProtocol) {
            return getFile().lastModified()
        } else {
            System.currentTimeMillis()
        }
    }

    @Override
    boolean supportsSize() { isFileProtocol }
    @Override
    long getSize() { isFileProtocol ? getFile().length() : 0 }

    @Override
    boolean supportsWrite() { isFileProtocol }
    @Override
    void putText(String text) {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        // first make sure the directory exists that this is in
        if (!getFile().parentFile.exists()) getFile().parentFile.mkdirs()
        // now write the text to the file and close it
        FileWriter fw = new FileWriter(getFile())
        fw.write(text)
        fw.close()
        this.exists = null
    }
    @Override
    void putStream(InputStream stream) {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        // first make sure the directory exists that this is in
        if (!getFile().parentFile.exists()) getFile().parentFile.mkdirs()
        OutputStream os = new FileOutputStream(getFile())
        StupidUtilities.copyStream(stream, os)
        stream.close()
        os.close()
        this.exists = null
    }

    @Override
    void move(String newLocation) {
        if (!newLocation) throw new IllegalArgumentException("No location specified, not moving resource at ${getLocation()}")
        ResourceReference newRr = ecf.resource.getLocationReference(newLocation)

        if (newRr.getUrl().getProtocol() != "file")
            throw new IllegalArgumentException("Location [${newLocation}] is not a file location, not moving resource at ${getLocation()}")
        if (!isFileProtocol)
            throw new IllegalArgumentException("Move not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")

        File curFile = getFile()
        if (!curFile.exists()) return
        String path = newRr.getUrl().toExternalForm().substring(5)
        File newFile = new File(path)
        File newFileParent = newFile.getParentFile()
        if (newFileParent != null && !newFileParent.exists()) newFileParent.mkdirs()
        curFile.renameTo(newFile)
    }

    @Override
    ResourceReference makeDirectory(String name) {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init("${location}/${name}", ecf)
        newRef.getFile().mkdirs()
        return newRef
    }
    @Override
    ResourceReference makeFile(String name) {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init("${location}/${name}", ecf)
        // first make sure the directory exists that this is in
        if (!getFile().exists()) getFile().mkdirs()
        newRef.getFile().createNewFile()
        return newRef
    }
    @Override
    boolean delete() {
        if (!isFileProtocol) throw new IllegalArgumentException("Write not supported for resource [${getLocation()}] with protocol [${locationUrl?.protocol}]")
        return getFile().delete()
    }
}
