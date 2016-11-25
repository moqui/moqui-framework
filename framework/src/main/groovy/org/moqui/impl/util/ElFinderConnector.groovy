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
package org.moqui.impl.util

import org.apache.commons.fileupload.FileItem
import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Used by the org.moqui.impl.ElFinderServices.run#Command service. */
class ElFinderConnector {
    protected final static Logger logger = LoggerFactory.getLogger(ElFinderConnector.class)

    ExecutionContext ec
    String volumeId
    String resourceRoot

    ElFinderConnector(ExecutionContext ec, String resourceRoot, String volumeId) {
        this.ec = ec
        this.resourceRoot = resourceRoot
        this.volumeId = volumeId
    }

    String hash(String str) {
        String hashed = str.bytes.encodeBase64().toString()
        hashed = hashed.replace("=", "")
        hashed = hashed.replace("+", "-")
        hashed = hashed.replace("/", "_")
        hashed = volumeId + hashed
        return hashed
    }

    static String unhash(String hashed) {
        if (!hashed) return ""
        // NOTE: assumes a volume ID prefix with 3 characters
        hashed = hashed.substring(3)
        hashed = hashed.replace(".", "=")
        hashed = hashed.replace("-", "+")
        hashed = hashed.replace("_", "/")
        return new String(hashed.decodeBase64())
    }

    String getLocation(String hashed) {
        if (hashed) {
            String unhashedPath = unhash(hashed)
            if (unhashedPath == "/" || unhashedPath == "root") return resourceRoot
            if (unhashedPath.startsWith("/")) unhashedPath = unhashedPath.substring(1)
            return resourceRoot + (resourceRoot.endsWith("/") ? "" : "/") + unhashedPath
        }
        return resourceRoot
    }

    String getPathRelativeToRoot(String location) {
        String path = location.trim()
        if (!location.startsWith(resourceRoot)) {
            logger.warn("Location [${location}] does not start resourceRoot [${resourceRoot}]! Returning full location as relative path to root")
            return location
        }
        path = path.substring(resourceRoot.length())
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1)
        if (path.startsWith("/")) path = path.substring(1)
        if (path == "") return "root"
        return path
    }

    boolean isRoot(String location) { return getPathRelativeToRoot(location) == "root" }

    Map getLocationInfo(String location) { return getResourceInfo(ec.resource.getLocationReference(location)) }

    Map getResourceInfo(ResourceReference ref) {
        Map info = [:]
        boolean curRoot = isRoot(ref.getLocation())
        info.name = curRoot ? (resourceRoot.endsWith("/") ? resourceRoot.substring(0, resourceRoot.length() - 1) : resourceRoot) : ref.getFileName()
        String location = ref.getLocation()
        String relativePath = getPathRelativeToRoot(location)
        info.hash = hash(relativePath)

        if (curRoot) {
            info.volumeid = volumeId
        } else {
            String parentPath = relativePath.contains("/") ? relativePath.substring(0, relativePath.lastIndexOf("/")) : "root"
            // logger.warn("======= phash: location=${location}, relativePath=${relativePath}, parentPath=${parentPath}")
            info.phash = hash(parentPath)
        }
        info.mime = curRoot || ref.isDirectory() ? "directory" : ref.getContentType()
        if (ref.supportsLastModified()) info.ts = ref.getLastModified()
        if (ref.supportsSize()) info.size = ref.getSize()
        info.dirs = hasChildDirectories(ref) ? 1 : 0
        info.read = 1
        info.write = ref.supportsWrite() ? 1 : 0
        info.locked = 0

        return info
    }

    static boolean hasChildDirectories(ResourceReference ref) {
        if (!ref.isDirectory()) return false
        List<ResourceReference> childList = ref.getDirectoryEntries()
        for (ResourceReference child in childList) if (child.isDirectory()) return true
        return false
    }

    List<Map> getFiles(String target, boolean tree) {
        List<Map> files = []
        ResourceReference currentRef = ec.resource.getLocationReference(getLocation(target))
        if (currentRef.isDirectory()) files.add(getResourceInfo(currentRef))

        if (tree) files.addAll(getTree(resourceRoot, 0))

        for (ResourceReference childRef in currentRef.getDirectoryEntries()) {
            Map resourceInfo = getResourceInfo(childRef)
            if (!files.contains(resourceInfo)) files.add(resourceInfo)
        }
        return files
    }


    List<Map> getTree(String location, int deep) { return getTree(ec.resource.getLocationReference(location), deep) }
    List<Map> getTree(ResourceReference ref, int deep) {
        List<Map> dirs = []
        for (ResourceReference child in ref.getDirectoryEntries()) {
            if (child.isDirectory()) {
                Map info = getResourceInfo(child)
                dirs.add(info)
                if (deep > 0) dirs.addAll(getTree(child, deep - 1))
            }
        }
        return dirs
    }

    List<Map> getParents(String location) { return getParents(ec.resource.getLocationReference(location)) }
    List<Map> getParents(ResourceReference ref) {
        List<Map> tree = []
        ResourceReference dir = ref
        while (!isRoot(dir.getLocation())) {
            ResourceReference parent = dir.getParent()
            if (parent == null) {
                logger.warn("Got null parent for [${dir.getLocation()}], starting location [${ref.getLocation()}]")
                break
            }
            dir = parent
            tree.add(0, getResourceInfo(dir))
            getTree(dir, 0).each { if (!tree.contains(it)) tree.add(it) }
        }
        return tree ?: [getResourceInfo(ref)]
    }

    Map getOptions(String target) {
        Map options = [seperator:"/", path:getLocation(target)]
        // if we ever have a direct URL to get a file: options.url = "http://localhost/files/..."
        options.disabled = [ 'tmb', 'size', 'dim', 'duplicate', 'paste', 'archive', 'extract', 'search', 'resize', 'netmount' ]
        return options
    }

    List delete(String location) {
        List<String> deleted = []
        ResourceReference ref = ec.resource.getLocationReference(location)
        if (!ref.isDirectory()) if (ref.delete()) deleted.add(hash(getPathRelativeToRoot(location)))
        else deleted.addAll(deleteDir(ref))
        return deleted
    }

    List deleteDir(ResourceReference dir) {
        List deleted = []
        for (ResourceReference child in dir.getDirectoryEntries()) {
            if (child.isDirectory()) {
                deleted.addAll(deleteDir(child))
            } else {
                if (child.delete()) deleted.add(hash(getPathRelativeToRoot(child.getLocation())))
            }
        }
        if (dir.delete()) deleted.add(hash(getPathRelativeToRoot(dir.getLocation())))
        return deleted
    }

    void runCommand() {
        String cmd = ec.context.cmd
        String target = ec.context.target
        Map otherParameters = (Map) ec.context.otherParameters

        Map responseMap = [:]
        ec.context.responseMap = responseMap

        if (cmd == "file") {
            ec.context.fileLocation = getLocation(target)
            ec.context.fileInline = otherParameters.download != "1"
        } else if (cmd == "open") {
            boolean init = otherParameters.init == "1"
            boolean tree = otherParameters.tree == "1"
            if (init) {
                responseMap.api = "2.0"
                responseMap.netDrivers = []
                if (!target) target = hash("root")
            }

            if (!target) {
                responseMap.clear()
                responseMap.error = "File not found"
                return
            }

            // TODO: make this a setting somewhere? leave out altogether?
            responseMap.uplMaxSize = "32M"

            responseMap.cwd = getLocationInfo(getLocation(target))
            responseMap.files = getFiles(target, tree)
            responseMap.options = getOptions(unhash(target))
        } else if (cmd == "tree") {
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }

            String location = getLocation(target)
            List<Map> tree = [getLocationInfo(location)]
            tree.addAll(getTree(location, 0))
            responseMap.tree = tree
        } else if (cmd == "parents") {
            // if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            responseMap.tree = getParents(getLocation(target))
        } else if (cmd == "ls") {
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            List<String> fileList = []
            ResourceReference curDir = ec.resource.getLocationReference(getLocation(target))
            for (ResourceReference child in curDir.getDirectoryEntries()) fileList.add(child.getFileName())
            responseMap.list = fileList
        } else if (cmd == "mkdir") {
            String name = otherParameters.name
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            if (!name) { responseMap.clear(); responseMap.error = "No name specified for new directory"; return }
            String curLocation = getLocation(target)
            ResourceReference curDir = ec.resource.getLocationReference(curLocation)
            if (!curDir.supportsWrite()) { responseMap.clear(); responseMap.error = "Resource does not support write"; return }
            ResourceReference newRef  = curDir.makeDirectory(name)
            responseMap.added = [getResourceInfo(newRef)]
        } else if (cmd == "mkfile") {
            String name = otherParameters.name
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            if (!name) { responseMap.clear(); responseMap.error = "No name specified for new file"; return }
            String curLocation = getLocation(target)
            ResourceReference curDir = ec.resource.getLocationReference(curLocation)
            if (!curDir.supportsWrite()) { responseMap.clear(); responseMap.error = "Resource does not support write"; return }
            ResourceReference newRef  = curDir.makeFile(name)
            responseMap.added = [getResourceInfo(newRef)]
        } else if (cmd == "rm") {
            Object targetsObj = otherParameters.targets
            if (!targetsObj) targetsObj = otherParameters.'targets[]'
            List<String> targets = targetsObj instanceof List ? targetsObj : [targetsObj as String]
            List<String> removed = []
            for (String curTarget in targets) {
                String rmLocation = getLocation(curTarget)
                logger.info("ElFinder rm ${rmLocation}")
                removed.addAll(delete(rmLocation))
            }
            responseMap.removed = removed
        } else if (cmd == "rename") {
            String name = otherParameters.name
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            if (!name) { responseMap.clear(); responseMap.error = "No name specified for new directory"; return }

            String location = getLocation(target)
            String newLocation = location.substring(0, location.lastIndexOf("/") + 1) + name

            ResourceReference curRef = ec.resource.getLocationReference(location)
            curRef.move(newLocation)

            responseMap.added = [getLocationInfo(newLocation)]
            responseMap.removed = [target]
        } else if (cmd == "upload") {
            if (!target) { responseMap.clear(); responseMap.error = "errOpen"; return }
            String location = getLocation(target)
            // logger.info("ElFinder upload to ${location}, _fileUploadList: ${otherParameters._fileUploadList}")
            List<Map> added = []
            for (FileItem item in otherParameters._fileUploadList) {
                logger.info("ElFinder upload ${item.getName()} to ${location}")
                ResourceReference newRef = ec.resource.getLocationReference("${location}/${item.getName()}")
                newRef.putStream(item.getInputStream())
                added.add(getResourceInfo(newRef))
            }
            responseMap.added = added
        } else if (cmd == "get") {
            String location = getLocation(target)
            ResourceReference curRef = ec.resource.getLocationReference(location)
            responseMap.content = curRef.getText()
        } else if (cmd == "put") {
            String content = otherParameters.content
            String location = getLocation(target)
            ResourceReference curRef = ec.resource.getLocationReference(location)
            curRef.putText(content)
            responseMap.changed = [getResourceInfo(curRef)]
        }
    }
}
