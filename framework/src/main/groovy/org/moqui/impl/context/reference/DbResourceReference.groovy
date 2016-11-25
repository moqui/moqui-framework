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
import org.moqui.BaseException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.resource.ResourceReference
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.rowset.serial.SerialBlob

@CompileStatic
class DbResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(DbResourceReference.class)
    public final static String locationPrefix = "dbresource://"

    String location
    String resourceId = (String) null

    DbResourceReference() { }
    
    @Override ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
        this.location = location
        return this
    }

    ResourceReference init(String location, EntityValue dbResource, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
        this.location = location
        resourceId = dbResource.resourceId
        return this
    }

    @Override ResourceReference createNew(String location) {
        DbResourceReference resRef = new DbResourceReference();
        resRef.init(location, ecf);
        return resRef;
    }
    @Override String getLocation() { location }

    String getPath() {
        if (!location) return ""
        // should have a prefix of "dbresource://"
        return location.substring(locationPrefix.length())
    }

    @Override InputStream openStream() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return null
        return dbrf.getSerialBlob("fileData")?.getBinaryStream()
    }

    @Override OutputStream getOutputStream() {
        throw new UnsupportedOperationException("The getOutputStream method is not supported for DB resources, use putStream() instead")
    }

    @Override String getText() { return ObjectUtilities.getStreamText(openStream()) }

    @Override boolean supportsAll() { true }

    @Override boolean supportsUrl() { false }
    @Override URL getUrl() { return null }

    @Override boolean supportsDirectory() { true }
    @Override boolean isFile() { return getDbResource(true)?.isFile == "Y" }
    @Override boolean isDirectory() {
        if (!getPath()) return true // consider root a directory
        EntityValue dbr = getDbResource(true)
        return dbr != null && dbr.isFile != "Y"
    }
    @Override List<ResourceReference> getDirectoryEntries() {
        List<ResourceReference> dirEntries = new LinkedList()
        EntityValue dbr = getDbResource(true)
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

    @Override boolean supportsExists() { true }
    @Override boolean getExists() { return getDbResource(true) != null }

    @Override boolean supportsLastModified() { true }
    @Override long getLastModified() {
        EntityValue dbr = getDbResource(true)
        if (dbr == null) return 0
        if (dbr.isFile == "Y") {
            EntityValue dbrf = ecf.entity.find("moqui.resource.DbResourceFile").condition("resourceId", resourceId)
                    .selectField("lastUpdatedStamp").useCache(false).one()
            if (dbrf != null) {
                return dbrf.getTimestamp("lastUpdatedStamp").getTime()
            }
        }
        return dbr.getTimestamp("lastUpdatedStamp").getTime()
    }

    @Override boolean supportsSize() { true }
    @Override long getSize() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return 0
        return dbrf.getSerialBlob("fileData")?.length() ?: 0
    }

    @Override boolean supportsWrite() { true }
    @Override void putText(String text) {
        SerialBlob sblob = text ? new SerialBlob(text.getBytes()) : null
        this.putObject(sblob)
    }
    @Override void putStream(InputStream stream) {
        if (stream == null) return
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ObjectUtilities.copyStream(stream, baos)
        SerialBlob sblob = new SerialBlob(baos.toByteArray())
        this.putObject(sblob)
    }

    protected void putObject(Object fileObj) {
        EntityValue dbrf = getDbResourceFile()

        if (dbrf != null) {
            ecf.service.sync().name("update", "moqui.resource.DbResourceFile")
                    .parameters([resourceId:dbrf.resourceId, fileData:fileObj]).call()
        } else {
            // first make sure the directory exists that this is in
            List<String> filenameList = new ArrayList<>(Arrays.asList(getPath().split("/")))
            int filenameListSize = filenameList.size()
            if (filenameListSize == 0) throw new BaseException("Cannot put file at empty location ${getPath()}")
            String filename = filenameList.get(filenameList.size()-1)
            // remove the current filename from the list, and find ID of parent directory for path
            filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            if (parentResourceId == null) throw new BaseException("Could not find directory to put new file in at ${filenameList}")

            // lock the parentResourceId
            ecf.entity.find("moqui.resource.DbResource").condition("resourceId", parentResourceId)
                    .selectField("lastUpdatedStamp").forUpdate(true).one()
            // do a query by name to see if it exists
            EntityValue existingValue = ecf.entity.find("moqui.resource.DbResource")
                    .condition("parentResourceId", parentResourceId).condition("filename", filename)
                    .useCache(false).list().getFirst()
            if (existingValue != null) {
                resourceId = existingValue.resourceId
                ecf.service.sync().name("update", "moqui.resource.DbResourceFile")
                        .parameters([resourceId:resourceId, fileData:fileObj]).call()
            } else {
                // now write the DbResource and DbResourceFile records
                Map createDbrResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                        .parameters([parentResourceId:parentResourceId, filename:filename, isFile:"Y"]).call()
                ecf.service.sync().name("create", "moqui.resource.DbResourceFile")
                        .parameters([resourceId:createDbrResult.resourceId, mimeType:getContentType(), fileData:fileObj]).call()
                // clear out the local reference to the old file record
                resourceId = createDbrResult.resourceId
            }
        }
    }
    String findDirectoryId(List<String> pathList, boolean create) {
        String finalParentResourceId = null
        if (pathList) {
            String parentResourceId = null
            boolean found = true
            for (String filename in pathList) {
                if (filename == null || filename.length() == 0) continue

                EntityValue directoryValue = ecf.entity.find("moqui.resource.DbResource")
                        .condition("parentResourceId", parentResourceId).condition("filename", filename)
                        .useCache(true).list().getFirst()
                if (directoryValue == null) {
                    if (create) {
                        // trying a create so lock the parent, then query again to make sure it doesn't exist
                        ecf.entity.find("moqui.resource.DbResource").condition("resourceId", parentResourceId)
                                .selectField("lastUpdatedStamp").forUpdate(true).one()
                        directoryValue = ecf.entity.find("moqui.resource.DbResource")
                                .condition("parentResourceId", parentResourceId).condition("filename", filename)
                                .useCache(false).list().getFirst()
                        if (directoryValue == null) {
                            Map createResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                                    .parameters([parentResourceId:parentResourceId, filename:filename, isFile:"N"]).call()
                            parentResourceId = createResult.resourceId
                            // logger.warn("=============== put text to ${location}, created dir ${filename}")
                        }
                        // else fall through, handle below
                    } else {
                        found = false
                        break
                    }
                }
                if (directoryValue != null) {
                    if (directoryValue.isFile == "Y") {
                        throw new BaseException("Tried to find a directory in a path but found file instead at ${filename} under DbResource ${parentResourceId}")
                    } else {
                        parentResourceId = directoryValue.resourceId
                        // logger.warn("=============== put text to ${location}, found existing dir ${filename}")
                    }
                }
            }
            if (found) finalParentResourceId = parentResourceId
        }
        return finalParentResourceId
    }

    @Override void move(String newLocation) {
        EntityValue dbr = getDbResource(false)
        // if the current resource doesn't exist, nothing to move
        if (!dbr) {
            logger.warn("Could not find dbresource at [${getPath()}]")
            return
        }
        if (!newLocation) throw new IllegalArgumentException("No location specified, not moving resource at ${getLocation()}")
        // ResourceReference newRr = ecf.resource.getLocationReference(newLocation)
        if (!newLocation.startsWith(locationPrefix))
            throw new IllegalArgumentException("Location [${newLocation}] is not a dbresource location, not moving resource at ${getLocation()}")

        List<String> filenameList = new ArrayList<>(Arrays.asList(newLocation.substring(locationPrefix.length()).split("/")))
        if (filenameList) {
            String newFilename = filenameList.get(filenameList.size()-1)
            filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            dbr.parentResourceId = parentResourceId
            dbr.filename = newFilename
            dbr.update()
        }
    }

    @Override ResourceReference makeDirectory(String name) {
        findDirectoryId([name], true)
        return new DbResourceReference().init("${location}/${name}", ecf)
    }
    @Override ResourceReference makeFile(String name) {
        DbResourceReference newRef = (DbResourceReference) new DbResourceReference().init("${location}/${name}", ecf)
        newRef.putObject(null)
        return newRef
    }
    @Override boolean delete() {
        EntityValue dbr = getDbResource(false)
        if (dbr == null) return false
        if (dbr.isFile == "Y") {
            EntityValue dbrf = getDbResourceFile()
            if (dbrf != null) dbrf.delete()
        }
        dbr.delete()
        resourceId = null
        return true
    }

    String getDbResourceId() {
        if (resourceId != null) return resourceId

        List<String> filenameList = new ArrayList<>(Arrays.asList(getPath().split("/")))
        String lastResourceId = null
        for (String filename in filenameList) {
            EntityValue curDbr = ecf.entity.find("moqui.resource.DbResource").condition("parentResourceId", lastResourceId)
                    .condition("filename", filename).useCache(true).one()
            if (curDbr == null) return null
            lastResourceId = curDbr.resourceId
        }

        resourceId = lastResourceId
        return resourceId
    }

    EntityValue getDbResource(boolean useCache) {
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        return ecf.entityFacade.fastFindOne("moqui.resource.DbResource", useCache, false, resourceId)
    }
    EntityValue getDbResourceFile() {
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        // don't cache this, can be big and will be cached below this as text if needed
        return ecf.entityFacade.fastFindOne("moqui.resource.DbResourceFile", false, false, resourceId)
    }
}
