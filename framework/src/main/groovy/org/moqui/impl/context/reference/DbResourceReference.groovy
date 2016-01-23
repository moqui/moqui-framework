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

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ResourceReference
import org.moqui.impl.StupidUtilities
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.rowset.serial.SerialBlob

class DbResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(DbResourceReference.class)
    public final static String locationPrefix = "dbresource://"

    String location
    EntityValue dbResource = null
    EntityValue dbResourceFile = null

    DbResourceReference() { }
    
    @Override
    ResourceReference init(String location, ExecutionContextFactory ecf) {
        this.ecf = ecf
        this.location = location
        return this
    }

    ResourceReference init(String location, EntityValue dbResource, ExecutionContextFactory ecf) {
        this.ecf = ecf
        this.location = location
        this.dbResource = dbResource
        return this
    }

    @Override
    String getLocation() { location }

    String getPath() {
        if (!location) return ""
        // should have a prefix of "dbresource://"
        return location.substring(locationPrefix.length())
    }

    @Override
    InputStream openStream() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return null
        return dbrf.getSerialBlob("fileData")?.getBinaryStream()
    }

    @Override
    OutputStream getOutputStream() {
        throw new UnsupportedOperationException("The getOutputStream method is not supported for DB resources, use putStream() instead")
    }

    @Override
    String getText() { return StupidUtilities.getStreamText(openStream()) }

    @Override
    boolean supportsAll() { true }

    @Override
    boolean supportsUrl() { false }
    @Override
    URL getUrl() { return null }

    @Override
    boolean supportsDirectory() { true }
    @Override
    boolean isFile() { return getDbResource()?.isFile == "Y" }
    @Override
    boolean isDirectory() {
        if (!getPath()) return true // consider root a directory
        return getDbResource() != null && getDbResource().isFile != "Y"
    }
    @Override
    List<ResourceReference> getDirectoryEntries() {
        List<ResourceReference> dirEntries = new LinkedList()
        EntityValue dbr = getDbResource()
        if (getPath() && dbr == null) return dirEntries

        // allow parentResourceId to be null for the root
        EntityList childList = ecf.entity.find("moqui.resource.DbResource").condition([parentResourceId:dbr?.resourceId])
                .useCache(true).list()
        for (EntityValue child in childList) {
            String childLoc = getPath() ? "${location}/${child.filename}" : "${location}${child.filename}"
            dirEntries.add(new DbResourceReference().init(childLoc, child, ecf))
        }
        return dirEntries
    }

    @Override
    boolean supportsExists() { true }
    @Override
    boolean getExists() { return getDbResource() != null }

    @Override
    boolean supportsLastModified() { true }
    @Override
    long getLastModified() { return getDbResource()?.getTimestamp("lastUpdatedStamp")?.getTime() ?: System.currentTimeMillis() }

    @Override
    boolean supportsSize() { true }
    @Override
    long getSize() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return 0
        return dbrf.getSerialBlob("fileData")?.length() ?: 0
    }

    @Override
    boolean supportsWrite() { true }
    @Override
    void putText(String text) {
        SerialBlob sblob = text ? new SerialBlob(text.getBytes()) : null
        this.putObject(sblob)
    }
    @Override
    void putStream(InputStream stream) {
        if (stream == null) return
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        StupidUtilities.copyStream(stream, baos)
        SerialBlob sblob = new SerialBlob(baos.toByteArray())
        this.putObject(sblob)
    }

    protected void putObject(Object fileObj) {
        EntityValue dbrf = getDbResourceFile()

        if (dbrf != null) {
            ecf.service.sync().name("update", "moqui.resource.DbResourceFile")
                    .parameters([resourceId:dbrf.resourceId, fileData:fileObj]).call()
            dbResourceFile = null
        } else {
            // first make sure the directory exists that this is in
            List<String> filenameList = getPath().split("/")
            if (filenameList) filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            // now write the DbResource and DbResourceFile records
            Map createDbrResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                    .parameters([parentResourceId:parentResourceId, filename:getFileName(), isFile:"Y"]).call()
            ecf.service.sync().name("create", "moqui.resource.DbResourceFile")
                    .parameters([resourceId:createDbrResult.resourceId, mimeType:getContentType(), fileData:fileObj]).call()
            // clear out the local reference to the old file record
            dbResourceFile = null
        }
    }
    String findDirectoryId(List<String> pathList, boolean create) {
        String parentResourceId = null
        if (pathList) {
            for (String filename in pathList) {
                EntityValue directoryValue = ecf.entity.find("moqui.resource.DbResource")
                        .condition([parentResourceId:parentResourceId, filename:filename])
                        .useCache(true).list().getFirst()
                if (directoryValue == null) {
                    if (create) {
                        Map createResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                                .parameters([parentResourceId:parentResourceId, filename:filename, isFile:"N"]).call()
                        parentResourceId = createResult.resourceId
                        // logger.warn("=============== put text to ${location}, created dir ${filename}")
                    }
                } else {
                    parentResourceId = directoryValue.resourceId
                    // logger.warn("=============== put text to ${location}, found existing dir ${filename}")
                }
            }
        }
        return parentResourceId
    }

    @Override
    void move(String newLocation) {
        EntityValue dbr = getDbResource()
        // if the current resource doesn't exist, nothing to move
        if (!dbr) {
            logger.warn("Could not find dbresource at [${getPath()}]")
            return
        }

        if (!newLocation) throw new IllegalArgumentException("No location specified, not moving resource at ${getLocation()}")
        // ResourceReference newRr = ecf.resource.getLocationReference(newLocation)
        if (!newLocation.startsWith(locationPrefix))
            throw new IllegalArgumentException("Location [${newLocation}] is not a dbresource location, not moving resource at ${getLocation()}")

        List<String> filenameList = newLocation.substring(locationPrefix.length()).split("/")
        if (filenameList) {
            String newFilename = filenameList.get(filenameList.size()-1)
            filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            dbr.parentResourceId = parentResourceId
            dbr.filename = newFilename
            dbr.update()
        }
    }

    @Override
    ResourceReference makeDirectory(String name) {
        findDirectoryId([name], true)
        return new DbResourceReference().init("${location}/${name}", ecf)
    }
    @Override
    ResourceReference makeFile(String name) {
        DbResourceReference newRef = (DbResourceReference) new DbResourceReference().init("${location}/${name}", ecf)
        newRef.putObject(null)
        return newRef
    }
    @Override
    boolean delete() {
        EntityValue dbr = getDbResource()
        if (dbr == null) return false
        if (dbr.isFile == "Y") {
            EntityValue dbrf = getDbResourceFile()
            if (dbrf != null) dbrf.delete()
        }
        dbr.delete()
        return true
    }

    EntityValue getDbResource() {
        if (dbResource != null) return dbResource

        List<String> filenameList = getPath().split("/")
        String parentResourceId = null
        EntityValue lastValue = null
        for (String filename in filenameList) {
            // NOTE: using .useCache(true).list().getFirst() because .useCache(true).one() tries to use the one cache
            // and that doesn't auto-clear correctly for non-pk queries
            lastValue = ecf.entity.find("moqui.resource.DbResource").condition([parentResourceId:parentResourceId, filename:filename])
                    .useCache(true).list().getFirst()
            if (lastValue == null) continue
            parentResourceId = lastValue.resourceId
        }

        dbResource = lastValue
        return lastValue
    }
    EntityValue getDbResourceFile() {
        if (dbResourceFile != null) return dbResourceFile

        EntityValue dbr = getDbResource()
        if (dbr == null) return null

        // don't cache this, can be big and will be cached below this as text if needed
        EntityValue dbrf = ecf.entity.find("moqui.resource.DbResourceFile").condition([resourceId:dbr.resourceId]).useCache(false).one()

        dbResourceFile = dbrf
        return dbrf
    }
}
