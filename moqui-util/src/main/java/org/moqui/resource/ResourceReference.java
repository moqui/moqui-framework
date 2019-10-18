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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.*;

public abstract class ResourceReference implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ResourceReference.class);
    private static final MimetypesFileTypeMap mimetypesFileTypeMap = new MimetypesFileTypeMap();

    protected ResourceReference childOfResource = null;
    private Map<String, ResourceReference> subContentRefByPath = null;

    public abstract ResourceReference init(String location);

    public abstract ResourceReference createNew(String location);

    public abstract String getLocation();
    public abstract InputStream openStream();
    public abstract OutputStream getOutputStream();
    public abstract String getText();

    public abstract boolean supportsAll();
    public abstract boolean supportsUrl();
    public abstract URL getUrl();

    public abstract boolean supportsDirectory();
    public abstract boolean isFile();
    public abstract boolean isDirectory();

    public abstract boolean supportsExists();
    public abstract boolean getExists();

    public abstract boolean supportsLastModified();
    public abstract long getLastModified();

    public abstract boolean supportsSize();
    public abstract long getSize();

    public abstract boolean supportsWrite();
    public abstract void putText(String text);
    public abstract void putStream(InputStream stream);
    public abstract void move(String newLocation);
    public abstract ResourceReference makeDirectory(String name);
    public abstract ResourceReference makeFile(String name);
    public abstract boolean delete();

    /** Get the entries of a directory */
    public abstract List<ResourceReference> getDirectoryEntries();

    public URI getUri() {
        try {
            if (supportsUrl()) {
                URL locUrl = getUrl();
                if (locUrl == null) return null;
                // use the multi-argument constructor to have it do character encoding and avoid an exception
                // WARNING: a String from this URI may not equal the String from the URL (ie if characters are encoded)
                // NOTE: this doesn't seem to work on Windows for local files: when protocol is plain "file" and path starts
                //     with a drive letter like "C:\moqui\..." it produces a parse error showing the URI as "file://C:/..."
                if (logger.isTraceEnabled()) logger.trace("Getting URI for URL " + locUrl.toExternalForm());
                String path = locUrl.getPath();

                // Support Windows local files.
                if ("file".equals(locUrl.getProtocol())) {
                    if (!path.startsWith("/"))
                        path = "/" + path;
                }
                return new URI(locUrl.getProtocol(), locUrl.getUserInfo(), locUrl.getHost(),
                        locUrl.getPort(), path, locUrl.getQuery(), locUrl.getRef());
            } else {
                String loc = getLocation();
                if (loc == null || loc.isEmpty()) return null;
                return new URI(loc);
            }
        } catch (URISyntaxException e) {
            throw new BaseException("Error creating URI", e);
        }
    }

    /** One part of the URI not easy to get from the URI object, basically the last part of the path. */
    public String getFileName() {
        String loc = getLocation();
        if (loc == null || loc.length() == 0) return null;
        int slashIndex = loc.lastIndexOf("/");
        return slashIndex >= 0 ? loc.substring(slashIndex + 1) : loc;
    }


    /** The content (MIME) type for this content, if known or can be determined. */
    public String getContentType() {
        String fn = getFileName();
        return fn != null && fn.length() > 0 ? getContentType(fn) : null;
    }
    public boolean isBinary() { return isBinaryContentType(getContentType()); }
    public boolean isText() { return isTextContentType(getContentType()); }

    /** Get the parent directory, null if it is the root (no parent). */
    public ResourceReference getParent() {
        String curLocation = getLocation();
        if (curLocation.endsWith("/")) curLocation = curLocation.substring(0, curLocation.length() - 1);
        String strippedLocation = stripLocationPrefix(curLocation);
        if (strippedLocation.isEmpty()) return null;
        if (strippedLocation.startsWith("/")) strippedLocation = strippedLocation.substring(1);
        if (strippedLocation.contains("/")) {
            return createNew(curLocation.substring(0, curLocation.lastIndexOf("/")));
        } else {
            String prefix = getLocationPrefix(curLocation);
            if (prefix != null && !prefix.isEmpty()) return createNew(prefix);
            return null;
        }
    }

    /** Find the directory with a name that matches the current filename (minus the extension) */
    public ResourceReference findMatchingDirectory() {
        if (this.isDirectory()) return this;
        StringBuilder dirLoc = new StringBuilder(getLocation());
        ResourceReference directoryRef = this;
        while (!(directoryRef.getExists() && directoryRef.isDirectory()) && dirLoc.lastIndexOf(".") > 0) {
            // get rid of one suffix at a time (for screens probably .xml but use .* for other files, etc)
            dirLoc.delete(dirLoc.lastIndexOf("."), dirLoc.length());
            directoryRef = createNew(dirLoc.toString());
            // directoryRef = ecf.resource.getLocationReference(dirLoc.toString())
        }
        return directoryRef;
    }

    /** Get a reference to the child of this directory or this file in the matching directory */
    public ResourceReference getChild(String childName) {
        if (childName == null || childName.length() == 0) return null;
        ResourceReference directoryRef = findMatchingDirectory();
        StringBuilder fileLoc = new StringBuilder(directoryRef.getLocation());
        if (fileLoc.charAt(fileLoc.length()-1) == '/') fileLoc.deleteCharAt(fileLoc.length()-1);
        if (childName.charAt(0) != '/') fileLoc.append('/');
        fileLoc.append(childName);

        // NOTE: don't really care if it exists or not at this point
        return createNew(fileLoc.toString());
    }

    /** Get a list of references to all files in this directory or for a file in the matching directory */
    public List<ResourceReference> getChildren() {
        List<ResourceReference> children = new LinkedList<>();
        ResourceReference directoryRef = findMatchingDirectory();
        if (directoryRef == null || !directoryRef.getExists()) return null;
        for (ResourceReference childRef : directoryRef.getDirectoryEntries()) if (childRef.isFile()) children.add(childRef);
        return children;
    }

    /** Find a file by path (can be single name) in the matching directory and child matching directories */
    public ResourceReference findChildFile(String relativePath) {
        // no path to child? that means this resource
        if (relativePath == null || relativePath.length() == 0) return this;

        if (!supportsAll()) {
            throw new BaseException("Not looking for child file at " + relativePath + " under space root page " +
                    getLocation() + " because exists, isFile, etc are not supported");
        }

        // logger.warn("============= finding child resource of [${toString()}] path [${relativePath}]")

        // check the cache first
        ResourceReference childRef = getSubContentRefByPath().get(relativePath);
        if (childRef != null && childRef.getExists()) return childRef;

        // this finds a file in a directory with the same name as this resource, unless this resource is a directory
        ResourceReference directoryRef = findMatchingDirectory();

        // logger.warn("============= finding child resource path [${relativePath}] directoryRef [${directoryRef}]")
        if (directoryRef.getExists()) {
            StringBuilder fileLoc = new StringBuilder(directoryRef.getLocation());
            if (fileLoc.charAt(fileLoc.length() - 1) == '/') fileLoc.deleteCharAt(fileLoc.length() - 1);
            if (relativePath.charAt(0) != '/') fileLoc.append('/');
            fileLoc.append(relativePath);

            ResourceReference theFile = createNew(fileLoc.toString());
            if (theFile.getExists() && theFile.isFile()) childRef = theFile;

            // logger.warn("============= finding child resource path [${relativePath}] childRef [${childRef}]")
            if (childRef == null) {
                // didn't find it at a literal path, try searching for it in all subdirectories
                int lastSlashIdx = relativePath.lastIndexOf("/");
                String directoryPath = lastSlashIdx > 0 ? relativePath.substring(0, lastSlashIdx) : "";
                String childFilename = lastSlashIdx >= 0 ? relativePath.substring(lastSlashIdx + 1) : relativePath;
                // first find the most matching directory
                ResourceReference childDirectoryRef = directoryRef.findChildDirectory(directoryPath);
                // recursively walk the directory tree and find the childFilename
                childRef = internalFindChildFile(childDirectoryRef, childFilename);
                // logger.warn("============= finding child resource path [${relativePath}] directoryRef [${directoryRef}] childFilename [${childFilename}] childRef [${childRef}]")
            }

            // logger.warn("============= finding child resource path [${relativePath}] childRef 3 [${childRef}]")
            if (childRef != null) childRef.childOfResource = directoryRef;
        }


        if (childRef == null) {
            // still nothing? treat the path to the file as a literal and return it (exists will be false)
            if (directoryRef.getExists()) {
                childRef = createNew(directoryRef.getLocation() + "/" + relativePath);
                childRef.childOfResource = directoryRef;
            } else {
                String newDirectoryLoc = getLocation();
                // pop off the extension, everything past the first dot after the last slash
                int lastSlashLoc = newDirectoryLoc.lastIndexOf("/");
                if (newDirectoryLoc.contains("."))
                    newDirectoryLoc = newDirectoryLoc.substring(0, newDirectoryLoc.indexOf(".", lastSlashLoc));
                childRef = createNew(newDirectoryLoc + "/" + relativePath);
            }
        } else {
            // put it in the cache before returning, but don't cache the literal reference
            getSubContentRefByPath().put(relativePath, childRef);
        }

        // logger.warn("============= finding child resource of [${toString()}] path [${relativePath}] got [${childRef}]")
        return childRef;
    }

    /** Find a directory by path (can be single name) in the matching directory and child matching directories */
    public ResourceReference findChildDirectory(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return this;

        if (!supportsAll()) {
            throw new BaseException("Not looking for child file at " + relativePath + " under space root page " +
                    getLocation() + " because exists, isFile, etc are not supported");
        }

        // check the cache first
        ResourceReference childRef = getSubContentRefByPath().get(relativePath);
        if (childRef != null && childRef.getExists()) return childRef;

        List<String> relativePathNameList = Arrays.asList(relativePath.split("/"));

        ResourceReference childDirectoryRef = this;
        if (this.isFile()) childDirectoryRef = this.findMatchingDirectory();

        // search remaining relativePathNameList, ie partial directories leading up to filename
        for (String relativePathName : relativePathNameList) {
            childDirectoryRef = internalFindChildDir(childDirectoryRef, relativePathName);
            if (childDirectoryRef == null) break;
        }


        if (childDirectoryRef == null) {
            // still nothing? treat the path to the file as a literal and return it (exists will be false)
            String newDirectoryLoc = getLocation();
            if (this.isFile()) {
                // pop off the extension, everything past the first dot after the last slash
                int lastSlashLoc = newDirectoryLoc.lastIndexOf("/");
                if (newDirectoryLoc.contains("."))
                    newDirectoryLoc = newDirectoryLoc.substring(0, newDirectoryLoc.indexOf(".", lastSlashLoc));
            }

            childDirectoryRef = createNew(newDirectoryLoc + "/" + relativePath);
        } else {
            // put it in the cache before returning, but don't cache the literal reference
            getSubContentRefByPath().put(relativePath, childRef);
        }

        return childDirectoryRef;
    }

    private ResourceReference internalFindChildDir(ResourceReference directoryRef, String childDirName) {
        if (directoryRef == null || !directoryRef.getExists()) return null;
        // no child dir name, means this/current dir
        if (childDirName == null || childDirName.isEmpty()) return directoryRef;

        // try a direct sub-directory, if it is there it's more efficient than a brute-force search
        StringBuilder dirLocation = new StringBuilder(directoryRef.getLocation());
        if (dirLocation.charAt(dirLocation.length() - 1) == '/') dirLocation.deleteCharAt(dirLocation.length() - 1);
        if (childDirName.charAt(0) != '/') dirLocation.append('/');
        dirLocation.append(childDirName);
        ResourceReference directRef = createNew(dirLocation.toString());
        if (directRef != null && directRef.getExists()) return directRef;

        // if no direct reference is found, try the more flexible search
        for (ResourceReference childRef : directoryRef.getDirectoryEntries()) {
            if (childRef.isDirectory() && (childRef.getFileName().equals(childDirName) || childRef.getFileName().contains(childDirName + "."))) {
                // matching directory name, use it
                return childRef;
            } else if (childRef.isDirectory()) {
                // non-matching directory name, recurse into it
                ResourceReference subRef = internalFindChildDir(childRef, childDirName);
                if (subRef != null) return subRef;
            }
        }
        return null;
    }

    private ResourceReference internalFindChildFile(ResourceReference directoryRef, String childFilename) {
        if (directoryRef == null || !directoryRef.getExists()) return null;

        // find check exact filename first
        ResourceReference exactMatchRef = directoryRef.getChild(childFilename);
        if (exactMatchRef.isFile() && exactMatchRef.getExists()) return exactMatchRef;

        List<ResourceReference> childEntries = directoryRef.getDirectoryEntries();
        // look through all files first, ie do a breadth-first search
        for (ResourceReference childRef : childEntries) {
            if (childRef.isFile() && (childRef.getFileName().equals(childFilename) || childRef.getFileName().startsWith(childFilename + "."))) {
                return childRef;
            }
        }

        for (ResourceReference childRef : childEntries) {
            if (childRef.isDirectory()) {
                ResourceReference subRef = internalFindChildFile(childRef, childFilename);
                if (subRef != null) return subRef;
            }
        }
        return null;
    }

    public String getActualChildPath() {
        if (childOfResource == null) return null;
        String parentLocation = childOfResource.getLocation();
        String childLocation = getLocation();
        // this should be true, but just in case:
        if (childLocation.startsWith(parentLocation)) {
            String childPath = childLocation.substring(parentLocation.length());
            if (childPath.startsWith("/")) return childPath.substring(1);
            else return childPath;
        }
        // if not, what to do?
        return null;
    }

    public void walkChildTree(List<Map> allChildFileFlatList, List<Map> childResourceList) {
        if (this.isFile()) walkChildFileTree(this, "", allChildFileFlatList, childResourceList);
        if (this.isDirectory()) for (ResourceReference childRef : getDirectoryEntries()) {
            childRef.walkChildFileTree(this, "", allChildFileFlatList, childResourceList);
        }
    }
    private void walkChildFileTree(ResourceReference rootResource, String pathFromRoot,
                           List<Map> allChildFileFlatList, List<Map> childResourceList) {
        // logger.warn("================ walkChildFileTree rootResource=${rootResource} pathFromRoot=${pathFromRoot} curLocation=${getLocation()}")
        String childPathBase = pathFromRoot != null && !pathFromRoot.isEmpty() ? pathFromRoot + '/' : "";

        if (this.isFile()) {
            List<Map> curChildResourceList = new LinkedList<>();

            String curFileName = getFileName();
            if (curFileName.contains(".")) curFileName = curFileName.substring(0, curFileName.lastIndexOf('.'));
            String curPath = childPathBase + curFileName;

            if (allChildFileFlatList != null) {
                Map<String, String> infoMap = new HashMap<>(3);
                infoMap.put("path", curPath); infoMap.put("name", curFileName); infoMap.put("location", getLocation());
                allChildFileFlatList.add(infoMap);
            }
            if (childResourceList != null) {
                Map<String, Object> infoMap = new HashMap<>(4);
                infoMap.put("path", curPath); infoMap.put("name", curFileName); infoMap.put("location", getLocation());
                infoMap.put("childResourceList", curChildResourceList);
                childResourceList.add(infoMap);
            }

            ResourceReference matchingDirReference = this.findMatchingDirectory();
            String childPath = childPathBase + matchingDirReference.getFileName();
            for (ResourceReference childRef : matchingDirReference.getDirectoryEntries()) {
                childRef.walkChildFileTree(rootResource, childPath, allChildFileFlatList, curChildResourceList);
            }
        }
        // TODO: walk child directories somehow or just stick with files with matching directories?
    }

    public void destroy() { }
    @Override public String toString() {
        String loc = getLocation();
        return loc != null && !loc.isEmpty() ? loc : ("[no location (" + getClass().getName() + ")]");
    }

    private Map<String, ResourceReference> getSubContentRefByPath() {
        if (subContentRefByPath == null) subContentRefByPath = new HashMap<>();
        return subContentRefByPath;
    }

    public static boolean isTextFilename(String filename) {
        String contentType = getContentType(filename);
        if (contentType == null || contentType.isEmpty()) return false;
        return isTextContentType(contentType);
    }
    public static boolean isBinaryFilename(String filename) {
        String contentType = getContentType(filename);
        if (contentType == null || contentType.isEmpty()) return false;
        return !isTextContentType(contentType);
    }
    public static String getContentType(String filename) {
        // need to check this, or type mapper handles it fine? || !filename.contains(".")
        if (filename == null || filename.length() == 0) return null;
        String type = mimetypesFileTypeMap.getContentType(filename);
        // strip any parameters, ie after the ;
        int semicolonIndex = type.indexOf(";");
        if (semicolonIndex >= 0) type = type.substring(0, semicolonIndex);
        return type;
    }
    public static boolean isTextContentType(String contentType) {
        if (contentType == null) return false;
        contentType = contentType.trim();

        int scIdx = contentType.indexOf(";");
        contentType = scIdx >= 0 ? contentType.substring(0, scIdx).trim() : contentType;
        if (contentType.length() == 0) return false;

        if (contentType.startsWith("text/")) return true;
        // aside from text/*, a few notable exceptions:
        if ("application/javascript".equals(contentType)) return true;
        if ("application/json".equals(contentType)) return true;
        if (contentType.endsWith("+json")) return true;
        if ("application/rtf".equals(contentType)) return true;
        if (contentType.startsWith("application/xml")) return true;
        if (contentType.endsWith("+xml")) return true;
        if (contentType.startsWith("application/yaml")) return true;
        if (contentType.endsWith("+yaml")) return true;

        return false;
    }
    public static boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.length() == 0) return false;
        return !isTextContentType(contentType);
    }
    public static String stripLocationPrefix(String location) {
        if (location == null || location.isEmpty()) return "";
        // first remove colon (:) and everything before it
        StringBuilder strippedLocation = new StringBuilder(location);
        int colonIndex = strippedLocation.indexOf(":");
        if (colonIndex == 0) {
            strippedLocation.deleteCharAt(0);
        } else if (colonIndex > 0) {
            strippedLocation.delete(0, colonIndex+1);
        }
        // delete all leading forward slashes
        while (strippedLocation.length() > 0 && strippedLocation.charAt(0) == '/') strippedLocation.deleteCharAt(0);
        return strippedLocation.toString();
    }
    public static String getLocationPrefix(String location) {
        if (location == null || location.isEmpty()) return "";
        if (location.contains("://")) {
            return location.substring(0, location.indexOf(":")) + "://";
        } else if (location.contains(":")) {
            return location.substring(0, location.indexOf(":")) + ":";
        } else {
            return "";
        }
    }

    public boolean supportsVersion() { return false; }
    public Version getVersion(String versionName) { return null; }
    public Version getCurrentVersion() { return null; }
    public Version getRootVersion() { return null; }
    public ArrayList<Version> getVersionHistory() { return new ArrayList<>(); }
    public ArrayList<Version> getNextVersions(String versionName) { return new ArrayList<>(); }
    public InputStream openStream(String versionName) { return openStream(); }
    public String getText(String versionName) { return getText(); }

    public static class Version {
        private final ResourceReference ref;
        private final String versionName, previousVersionName, userId;
        private final Timestamp versionDate;
        public Version(ResourceReference ref, String versionName, String previousVersionName, String userId, Timestamp versionDate) {
            this.ref = ref; this.versionName = versionName; this.previousVersionName = previousVersionName;
            this.userId = userId; this.versionDate = versionDate;
        }
        public ResourceReference getRef() { return ref; }
        public String getVersionName() { return versionName; }
        public String getPreviousVersionName() { return previousVersionName; }
        public Version getPreviousVersion() { return ref.getVersion(previousVersionName); }
        public ArrayList<Version> getNextVersions() { return ref.getNextVersions(versionName); }
        public String getUserId() { return userId; }
        public Timestamp getVersionDate() { return versionDate; }
        public InputStream openStream() { return ref.openStream(versionName); }
        public String getText() { return ref.getText(versionName); }
        public Map<String, Object> getMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("versionName", versionName); map.put("previousVersionName", previousVersionName);
            map.put("userId", userId); map.put("versionDate", versionDate);
            return map;
        }
    }
}
