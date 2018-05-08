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
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.ObjectUtilities

import javax.jcr.NodeIterator
import javax.jcr.PathNotFoundException
import javax.jcr.Session
import javax.jcr.Property

import org.moqui.resource.ResourceReference
import org.moqui.impl.context.ResourceFacadeImpl

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ContentResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(ContentResourceReference.class)
    public final static String locationPrefix = "content://"

    String location
    String repositoryName
    String nodePath

    protected javax.jcr.Node theNode = null

    ContentResourceReference() { }
    
    @Override ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf

        this.location = location
        // TODO: change to not rely on URI, or to encode properly
        URI locationUri = new URI(location)
        repositoryName = locationUri.host
        nodePath = locationUri.path

        return this
    }

    ResourceReference init(String repositoryName, javax.jcr.Node node, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf

        this.repositoryName = repositoryName
        this.nodePath = node.path
        this.location = "${locationPrefix}${repositoryName}${nodePath}"
        this.theNode = node
        return this
    }

    @Override ResourceReference createNew(String location) {
        ContentResourceReference resRef = new ContentResourceReference();
        resRef.init(location, ecf);
        return resRef;
    }
    @Override String getLocation() { location }

    @Override InputStream openStream() {
        javax.jcr.Node node = getNode()
        if (node == null) return null
        javax.jcr.Node contentNode = node.getNode("jcr:content")
        if (contentNode == null) throw new IllegalArgumentException("Cannot get stream for content at [${repositoryName}][${nodePath}], has no jcr:content child node")
        Property dataProperty = contentNode.getProperty("jcr:data")
        if (dataProperty == null) throw new IllegalArgumentException("Cannot get stream for content at [${repositoryName}][${nodePath}], has no jcr:content.jcr:data property")
        return dataProperty.binary.stream
    }

    @Override OutputStream getOutputStream() {
        throw new UnsupportedOperationException("The getOutputStream method is not supported for JCR, use putStream() instead")
    }

    @Override String getText() { return ObjectUtilities.getStreamText(openStream()) }

    @Override boolean supportsAll() { true }

    @Override boolean supportsUrl() { false }
    @Override URL getUrl() { return null }

    @Override boolean supportsDirectory() { true }
    @Override boolean isFile() {
        javax.jcr.Node node = getNode()
        if (node == null) return false
        return node.isNodeType("nt:file")
    }
    @Override boolean isDirectory() {
        javax.jcr.Node node = getNode()
        if (node == null) return false
        return node.isNodeType("nt:folder")
    }
    @Override List<ResourceReference> getDirectoryEntries() {
        List<ResourceReference> dirEntries = new LinkedList()
        javax.jcr.Node node = getNode()
        if (node == null) return dirEntries

        NodeIterator childNodes = node.getNodes()
        while (childNodes.hasNext()) {
            javax.jcr.Node childNode = childNodes.nextNode()
            dirEntries.add(new ContentResourceReference().init(repositoryName, childNode, ecf))
        }
        return dirEntries
    }
    // TODO: consider overriding findChildFile() to let the JCR impl do the query
    // ResourceReference findChildFile(String relativePath)

    @Override boolean supportsExists() { true }
    @Override boolean getExists() {
        if (theNode != null) return true
        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)
        return session.nodeExists(nodePath)
    }

    @Override boolean supportsLastModified() { true }
    @Override long getLastModified() {
        try {
            return getNode()?.getProperty("jcr:lastModified")?.getDate()?.getTimeInMillis()
        } catch (PathNotFoundException e) {
            return System.currentTimeMillis()
        }
    }

    @Override boolean supportsSize() { true }
    @Override long getSize() {
        try {
            return getNode()?.getProperty("jcr:content/jcr:data")?.getLength()
        } catch (PathNotFoundException e) {
            return 0
        }
    }

    @Override boolean supportsWrite() { true }

    @Override void putText(String text) { putObject(text) }
    @Override void putStream(InputStream stream) { putObject(stream) }
    protected void putObject(Object obj) {
        if (obj == null) {
            logger.warn("Data was null, not saving to resource [${getLocation()}]")
            return
        }
        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)
        javax.jcr.Node fileNode = getNode()
        javax.jcr.Node fileContent
        if (fileNode != null) {
            fileContent = fileNode.getNode("jcr:content")
        } else {
            // first make sure the directory exists that this is in
            List<String> nodePathList = new ArrayList<>(Arrays.asList(nodePath.split('/')))
            // if nodePath started with a '/' the first element will be empty
            if (nodePathList && nodePathList[0] == "") nodePathList.remove(0)
            // remove the filename to just get the directory
            if (nodePathList) nodePathList.remove(nodePathList.size()-1)
            javax.jcr.Node folderNode = findDirectoryNode(session, nodePathList, true)

            // now create the node
            fileNode = folderNode.addNode(fileName, "nt:file")
            fileContent = fileNode.addNode("jcr:content", "nt:resource")
        }
        fileContent.setProperty("jcr:mimeType", contentType)
        // fileContent.setProperty("jcr:encoding", ?)
        Calendar lastModified = Calendar.getInstance(); lastModified.setTimeInMillis(System.currentTimeMillis())
        fileContent.setProperty("jcr:lastModified", lastModified)
        if (obj instanceof CharSequence) {
            fileContent.setProperty("jcr:data", session.valueFactory.createValue(obj.toString()))
        } else if (obj instanceof InputStream) {
            fileContent.setProperty("jcr:data", session.valueFactory.createBinary((InputStream) obj))
        } else if (obj == null) {
            fileContent.setProperty("jcr:data", session.valueFactory.createValue(""))
        } else {
            throw new IllegalArgumentException("Cannot save content for obj with type ${obj.class.name}")
        }

        session.save()
    }

    static javax.jcr.Node findDirectoryNode(Session session, List<String> pathList, boolean create) {
        javax.jcr.Node rootNode = session.getRootNode()
        javax.jcr.Node folderNode = rootNode
        if (pathList) {
            for (String nodePathElement in pathList) {
                if (folderNode.hasNode(nodePathElement)) {
                    folderNode = folderNode.getNode(nodePathElement)
                } else {
                    if (create) {
                        folderNode = folderNode.addNode(nodePathElement, "nt:folder")
                    } else {
                        folderNode = null
                        break
                    }
                }
            }
        }
        return folderNode
    }

    void move(String newLocation) {
        if (!newLocation.startsWith(locationPrefix))
            throw new IllegalArgumentException("New location [${newLocation}] is not a content location, not moving resource at ${getLocation()}")

        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)

        ResourceReference newRr = ecf.resource.getLocationReference(newLocation)
        if (!newRr instanceof ContentResourceReference)
            throw new IllegalArgumentException("New location [${newLocation}] is not a content location, not moving resource at ${getLocation()}")
        ContentResourceReference newCrr = (ContentResourceReference) newRr

        // make sure the target folder exists
        List<String> nodePathList = new ArrayList<>(Arrays.asList(newCrr.getNodePath().split('/')))
        if (nodePathList && nodePathList[0] == "") nodePathList.remove(0)
        if (nodePathList) nodePathList.remove(nodePathList.size()-1)
        findDirectoryNode(session, nodePathList, true)

        session.move(this.getNodePath(), newCrr.getNodePath())
        session.save()

        this.theNode = null
    }

    @Override ResourceReference makeDirectory(String name) {
        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)
        findDirectoryNode(session, [name], true)
        return new ContentResourceReference().init("${location}/${name}", ecf)
    }
    @Override ResourceReference makeFile(String name) {
        ContentResourceReference newRef = (ContentResourceReference) new ContentResourceReference().init("${location}/${name}", ecf)
        newRef.putObject(null)
        return newRef
    }
    @Override boolean delete() {
        javax.jcr.Node curNode = getNode()
        if (curNode == null) return false

        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)
        session.removeItem(nodePath)
        session.save()

        this.theNode = null
        return true
    }

    javax.jcr.Node getNode() {
        if (theNode != null) return theNode
        Session session = ((ResourceFacadeImpl) ecf.resource).getContentRepositorySession(repositoryName)
        return session.nodeExists(nodePath) ? session.getNode(nodePath) : null
    }
}
