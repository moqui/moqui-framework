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

import freemarker.ext.beans.BeansWrapper
import freemarker.template.TemplateNodeModel
import freemarker.template.TemplateSequenceModel
import freemarker.template.TemplateHashModel
import freemarker.template.AdapterTemplateModel
import freemarker.template.TemplateModel
import freemarker.template.TemplateScalarModel
import groovy.transform.CompileStatic
import org.moqui.impl.StupidUtilities
import org.slf4j.LoggerFactory
import org.slf4j.Logger

@CompileStatic
class FtlNodeWrapper implements TemplateNodeModel, TemplateSequenceModel, TemplateHashModel, AdapterTemplateModel,
        TemplateScalarModel {
    protected final static Logger logger = LoggerFactory.getLogger(FtlNodeWrapper.class)
    protected final static BeansWrapper wrapper = BeansWrapper.getDefaultInstance()

    /** Factory method for null-sensitive Groovy Node wrapping. */
    static FtlNodeWrapper wrapNode(Node groovyNode) { return groovyNode != null ? new FtlNodeWrapper(groovyNode) : null }
    static FtlNodeWrapper makeFromText(String xmlText) { return wrapNode(new XmlParser().parseText(xmlText)) }

    protected Node groovyNode
    protected FtlNodeWrapper parentNode = null
    protected FtlTextWrapper textNode = null
    protected FtlNodeListWrapper allChildren = null

    protected Map<String, FtlAttributeWrapper> attributeWrapperMap = new HashMap<String, FtlAttributeWrapper>()
    protected Map<String, FtlNodeListWrapper> childrenByName = new HashMap<String, FtlNodeListWrapper>()

    protected FtlNodeWrapper(Node groovyNode) {
        this.groovyNode = groovyNode
    }
    
    protected FtlNodeWrapper(Node groovyNode, FtlNodeWrapper parentNode) {
        this.groovyNode = groovyNode
        this.parentNode = parentNode
    }

    Node getGroovyNode() { return groovyNode }

    Object getAdaptedObject(Class aClass) { return groovyNode }

    // TemplateHashModel methods

    TemplateModel get(String s) {
        // first try the attribute and children caches, then if not found in either pick it apart and create what is needed

        FtlAttributeWrapper attributeWrapper = attributeWrapperMap.get(s)
        if (attributeWrapper != null) return attributeWrapper
        if (attributeWrapperMap.containsKey(s)) return null

        FtlNodeListWrapper nodeListWrapper = childrenByName.get(s)
        if (nodeListWrapper != null) return nodeListWrapper
        if (childrenByName.containsKey(s)) return null

        if (s.startsWith("@")) {
            // check for @@text
            if (s == "@@text") {
                return textNode != null ? textNode : (textNode = new FtlTextWrapper(StupidUtilities.nodeText(groovyNode), this))
                // TODO: handle other special hashes? (see http://www.freemarker.org/docs/xgui_imperative_formal.html)
            }

            String key = s.substring(1)

            String attrValue = groovyNode.attribute(key)
            attributeWrapper = attrValue != null ? new FtlAttributeWrapper(key, attrValue, this) : null
            attributeWrapperMap.put(s, attributeWrapper)
            return attributeWrapper
        }

        // no @ prefix, looking for a child node
        List childList = groovyNode.children().findAll({ it instanceof Node && it.name() == s })
        // logger.info("Looking for child nodes with name [${s}] found: ${childList}")
        nodeListWrapper = new FtlNodeListWrapper(childList, this)
        childrenByName.put(s, nodeListWrapper)
        return nodeListWrapper
    }

    boolean isEmpty() {
        return groovyNode.attributes().isEmpty() && groovyNode.children().isEmpty() && groovyNode.localText().isEmpty()
    }

    // TemplateNodeModel methods

    TemplateNodeModel getParentNode() {
        if (parentNode != null) return parentNode
        parentNode = wrapNode(groovyNode.parent())
        return parentNode
    }

    TemplateSequenceModel getChildNodes() { return this }

    String getNodeName() { return groovyNode.name() }

    String getNodeType() { return "element" }

    /** Namespace not supported for now. */
    String getNodeNamespace() { return null }

    // TemplateSequenceModel methods
    TemplateModel get(int i) { return getSequenceList().get(i) }
    int size() { return getSequenceList().size() }
    protected FtlNodeListWrapper getSequenceList() {
        // Looks like attributes should NOT go in the FTL children list, so just use the node.children()
        if (allChildren == null) allChildren = groovyNode.localText() ?
            new FtlNodeListWrapper(groovyNode.localText(), this) : new FtlNodeListWrapper(groovyNode.children(), this)
        return allChildren
    }

    // TemplateScalarModel methods
    String getAsString() { return StupidUtilities.nodeText(groovyNode) }

    @Override
    String toString() { return prettyPrintNode(groovyNode) }

    static String prettyPrintNode(Node nd) {
        StringWriter sw = new StringWriter()
        XmlNodePrinter xnp = new XmlNodePrinter(new PrintWriter(sw))
        xnp.print(nd)
        return sw.toString()
    }

    @CompileStatic
    static class FtlAttributeWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected Object key
        protected Object value
        protected String valueStr
        protected FtlNodeWrapper parentNode
        FtlAttributeWrapper(Object key, Object value, FtlNodeWrapper parentNode) {
            this.key = key
            this.value = value
            valueStr = value != null ? value as String : null
            this.parentNode = parentNode
        }

        Object getAdaptedObject(Class aClass) { return value }

        // TemplateNodeModel methods
        TemplateNodeModel getParentNode() { return parentNode }
        TemplateSequenceModel getChildNodes() { return this }
        String getNodeName() { return key }
        String getNodeType() { return "attribute" }
        /** Namespace not supported for now. */
        String getNodeNamespace() { return null }

        // TemplateSequenceModel methods
        TemplateModel get(int i) {
            if (i == 0) return wrapper.wrap(value)
            throw new IndexOutOfBoundsException("Attribute node only has 1 value. Tried to get index [${i}] for attribute [${key}]")
        }
        int size() { return 1 }

        // TemplateScalarModel methods
        String getAsString() { return valueStr }

        @Override
        String toString() { return getAsString() }
    }


    @CompileStatic
    static class FtlTextWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String text
        protected FtlNodeWrapper parentNode
        FtlTextWrapper(String text, FtlNodeWrapper parentNode) {
            this.text = text
            this.parentNode = parentNode
        }

        Object getAdaptedObject(Class aClass) { return text }

        // TemplateNodeModel methods
        TemplateNodeModel getParentNode() { return parentNode }
        TemplateSequenceModel getChildNodes() { return this }
        String getNodeName() { return "@text" }
        String getNodeType() { return "text" }
        /** Namespace not supported for now. */
        String getNodeNamespace() { return null }

        // TemplateSequenceModel methods
        TemplateModel get(int i) {
            if (i == 0) return wrapper.wrap(text)
            throw new IndexOutOfBoundsException("Text node only has 1 value. Tried to get index [${i}]")
        }
        int size() { return 1 }

        // TemplateScalarModel methods
        String getAsString() { return text }

        @Override
        String toString() { return getAsString() }
    }

    @CompileStatic
    static class FtlNodeListWrapper implements TemplateSequenceModel {
        protected List<TemplateModel> nodeList = new ArrayList<TemplateModel>()
        FtlNodeListWrapper(List groovyNodes, FtlNodeWrapper parentNode) {
            for (Object childNode in groovyNodes) {
                if (childNode instanceof Node) {
                    nodeList.add(new FtlNodeWrapper((Node) childNode, parentNode))
                } else {
                    nodeList.add(new FtlTextWrapper(childNode as String, parentNode))
                }
            }
        }

        FtlNodeListWrapper(String text, FtlNodeWrapper parentNode) {
            nodeList.add(new FtlTextWrapper(text, parentNode))
        }

        TemplateModel get(int i) { return nodeList.get(i) }
        int size() { return nodeList.size() }

        @Override
        String toString() { return nodeList.toString() }
    }
}
