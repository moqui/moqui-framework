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
import org.moqui.util.MNode
import org.slf4j.LoggerFactory
import org.slf4j.Logger

@CompileStatic
class FtlNodeWrapper implements TemplateNodeModel, TemplateSequenceModel, TemplateHashModel, AdapterTemplateModel,
        TemplateScalarModel {
    protected final static Logger logger = LoggerFactory.getLogger(FtlNodeWrapper.class)
    protected final static BeansWrapper wrapper = BeansWrapper.getDefaultInstance()

    /** Factory method for null-sensitive Node wrapping. */
    // static FtlNodeWrapper wrapNode(Node groovyNode) { return groovyNode != null ? new FtlNodeWrapper(new MNode(groovyNode)) : null }
    static FtlNodeWrapper wrapNode(MNode mNode) { return mNode != null ? new FtlNodeWrapper(mNode) : null }
    static FtlNodeWrapper makeFromText(String location, String xmlText) { return wrapNode(MNode.parseText(location, xmlText)) }

    protected MNode mNode
    protected FtlNodeWrapper parentNode = null
    protected FtlTextWrapper textNode = null
    protected FtlNodeListWrapper allChildren = null

    protected Map<String, FtlAttributeWrapper> attributeWrapperMap = new HashMap<String, FtlAttributeWrapper>()
    protected Map<String, FtlNodeListWrapper> childrenByName = new HashMap<String, FtlNodeListWrapper>()

    protected FtlNodeWrapper(MNode wrapNode) {
        this.mNode = wrapNode
    }
    protected FtlNodeWrapper(MNode wrapNode, FtlNodeWrapper parentNode) {
        this.mNode = wrapNode
        this.parentNode = parentNode
    }

    Node getGroovyNode() { throw new IllegalArgumentException("Deprecated") }
    MNode getMNode() { return mNode }

    Object getAdaptedObject(Class aClass) { return mNode }

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
                if (textNode == null) textNode = new FtlTextWrapper(mNode.text, this)
                return textNode
                // TODO: handle other special hashes? (see http://www.freemarker.org/docs/xgui_imperative_formal.html)
            }

            String key = s.substring(1)

            String attrValue = mNode.attribute(key)
            attributeWrapper = attrValue != null ? new FtlAttributeWrapper(key, attrValue, this) : null
            attributeWrapperMap.put(s, attributeWrapper)
            return attributeWrapper
        }

        // no @ prefix, looking for a child node
        // logger.info("Looking for child nodes with name [${s}] found: ${childList}")
        nodeListWrapper = new FtlNodeListWrapper(mNode.children(s), this)
        childrenByName.put(s, nodeListWrapper)
        return nodeListWrapper
    }

    boolean isEmpty() {
        return mNode.attributes.isEmpty() && mNode.children.isEmpty() && (mNode.text == null || mNode.text.length() == 0)
    }

    // TemplateNodeModel methods

    TemplateNodeModel getParentNode() {
        if (parentNode != null) return parentNode
        parentNode = wrapNode(mNode.parent)
        return parentNode
    }

    TemplateSequenceModel getChildNodes() { return this }

    String getNodeName() { return mNode.name }

    String getNodeType() { return "element" }

    /** Namespace not supported for now. */
    String getNodeNamespace() { return null }

    // TemplateSequenceModel methods
    TemplateModel get(int i) { return getSequenceList().get(i) }
    int size() { return getSequenceList().size() }
    protected FtlNodeListWrapper getSequenceList() {
        // Looks like attributes should NOT go in the FTL children list, so just use the node.children()
        if (allChildren == null) allChildren = (mNode.text != null && mNode.text.length() > 0) ?
            new FtlNodeListWrapper(mNode.text, this) : new FtlNodeListWrapper(mNode.children, this)
        return allChildren
    }

    // TemplateScalarModel methods
    String getAsString() { return mNode.text != null ? mNode.text : "" }

    @Override
    String toString() { return prettyPrintNode(mNode) }

    static String prettyPrintNode(MNode nd) {
        return nd.toString()
        /*
        StringWriter sw = new StringWriter()
        XmlNodePrinter xnp = new XmlNodePrinter(new PrintWriter(sw))
        xnp.print(nd)
        return sw.toString()
        */
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
            if (i == 0) return wrapper.wrap(getAsString())
            throw new IndexOutOfBoundsException("Text node only has 1 value. Tried to get index [${i}]")
        }
        int size() { return 1 }

        // TemplateScalarModel methods
        String getAsString() { return text != null ? text : "" }

        @Override
        String toString() { return getAsString() }
    }

    @CompileStatic
    static class FtlNodeListWrapper implements TemplateSequenceModel {
        protected ArrayList<TemplateModel> nodeList = new ArrayList<TemplateModel>()
        FtlNodeListWrapper(ArrayList<MNode> mnodeList, FtlNodeWrapper parentNode) {
            for (int i = 0; i < mnodeList.size(); i++)
                nodeList.add(new FtlNodeWrapper(mnodeList.get(i), parentNode))
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
