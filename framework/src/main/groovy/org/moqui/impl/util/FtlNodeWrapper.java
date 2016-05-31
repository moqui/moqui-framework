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
package org.moqui.impl.util;

import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;

import groovy.util.Node;
import org.moqui.BaseException;
import org.moqui.impl.context.renderer.FtlTemplateRenderer;
import org.moqui.util.MNode;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class FtlNodeWrapper implements TemplateNodeModel, TemplateSequenceModel, TemplateHashModel,
        AdapterTemplateModel, TemplateScalarModel {
    protected final static Logger logger = LoggerFactory.getLogger(FtlNodeWrapper.class);
    protected final static BeansWrapper wrapper = new BeansWrapperBuilder(FtlTemplateRenderer.FTL_VERSION).build();

    /** Factory method for null-sensitive Node wrapping. */
    // public static FtlNodeWrapper wrapNode(Node groovyNode) { return groovyNode != null ? new FtlNodeWrapper(new MNode(groovyNode)) : null }
    public static FtlNodeWrapper wrapNode(MNode mNode) { return mNode != null ? new FtlNodeWrapper(mNode) : null; }
    public static FtlNodeWrapper makeFromText(String location, String xmlText) { return wrapNode(MNode.parseText(location, xmlText)); }

    protected MNode mNode;
    protected FtlNodeWrapper parentNode = null;
    protected FtlTextWrapper textNode = null;
    protected FtlNodeListWrapper allChildren = null;

    protected Map<String, TemplateModel> attrAndChildrenByName = new HashMap<>();

    protected FtlNodeWrapper(MNode wrapNode) {
        this.mNode = wrapNode;
    }
    protected FtlNodeWrapper(MNode wrapNode, FtlNodeWrapper parentNode) {
        this.mNode = wrapNode;
        this.parentNode = parentNode;
    }

    public MNode getMNode() { return mNode; }

    public Object getAdaptedObject(Class aClass) { return mNode; }

    // TemplateHashModel methods

    public TemplateModel get(String s) {
        // first try the attribute and children caches, then if not found in either pick it apart and create what is needed
        TemplateModel attrOrChildWrapper = attrAndChildrenByName.get(s);
        if (attrOrChildWrapper != null) return attrOrChildWrapper;
        if (attrAndChildrenByName.containsKey(s)) return null;

        // odd performance note: String.startsWith and String.charAt both take nearly as long as a HashMap.get
        if (s.startsWith("@")) {
            // check for @@text
            if (s.equals("@@text")) {
                if (textNode == null) textNode = new FtlTextWrapper(mNode.getText(), this);
                return textNode;
                // TODO: handle other special hashes? (see http://www.freemarker.org/docs/xgui_imperative_formal.html)
            }

            String key = s.substring(1);

            String attrValue = mNode.attribute(key);
            FtlAttributeWrapper attributeWrapper = attrValue != null ? new FtlAttributeWrapper(key, attrValue, this) : null;
            attrAndChildrenByName.put(s, attributeWrapper);
            return attributeWrapper;
        } else {
            // no @ prefix, looking for a child node
            // logger.info("Looking for child nodes with name [${s}] found: ${childList}")
            FtlNodeListWrapper nodeListWrapper = new FtlNodeListWrapper(mNode.children(s), this);
            attrAndChildrenByName.put(s, nodeListWrapper);
            return nodeListWrapper;
        }
    }

    public boolean isEmpty() {
        return mNode.getAttributes().isEmpty() && mNode.getChildren().isEmpty() && (mNode.getText() == null || mNode.getText().length() == 0);
    }

    // TemplateNodeModel methods

    public TemplateNodeModel getParentNode() {
        if (parentNode != null) return parentNode;
        parentNode = wrapNode(mNode.getParent());
        return parentNode;
    }

    public TemplateSequenceModel getChildNodes() { return this; }

    public String getNodeName() { return mNode.getName(); }

    public String getNodeType() { return "element"; }

    /** Namespace not supported for now. */
    public String getNodeNamespace() { return null; }

    // TemplateSequenceModel methods
    public TemplateModel get(int i) { return getSequenceList().get(i); }
    public int size() { return getSequenceList().size(); }
    protected FtlNodeListWrapper getSequenceList() {
        // Looks like attributes should NOT go in the FTL children list, so just use the node.children()
        if (allChildren == null) allChildren = (mNode.getText() != null && mNode.getText().length() > 0) ?
            new FtlNodeListWrapper(mNode.getText(), this) : new FtlNodeListWrapper(mNode.getChildren(), this);
        return allChildren;
    }

    // TemplateScalarModel methods
    public String getAsString() { return mNode.getText() != null ? mNode.getText() : ""; }

    @Override
    public String toString() { return prettyPrintNode(mNode); }

    public static String prettyPrintNode(MNode nd) {
        return nd.toString();
        /*
        StringWriter sw = new StringWriter()
        XmlNodePrinter xnp = new XmlNodePrinter(new PrintWriter(sw))
        xnp.print(nd)
        return sw.toString()
        */
    }

    public static class FtlAttributeWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String key;
        protected Object value;
        protected String valueStr;
        protected FtlNodeWrapper parentNode;
        FtlAttributeWrapper(Object key, Object value, FtlNodeWrapper parentNode) {
            this.key = key.toString();
            this.value = value;
            valueStr = value != null ? value.toString() : null;
            this.parentNode = parentNode;
        }

        public Object getAdaptedObject(Class aClass) { return value; }

        // TemplateNodeModel methods
        public TemplateNodeModel getParentNode() { return parentNode; }
        public TemplateSequenceModel getChildNodes() { return this; }
        public String getNodeName() { return key; }
        public String getNodeType() { return "attribute"; }
        /** Namespace not supported for now. */
        public String getNodeNamespace() { return null; }

        // TemplateSequenceModel methods
        public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(value);
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Attribute node only has 1 value. Tried to get index [${i}] for attribute [${key}]");
        }
        public int size() { return 1; }

        // TemplateScalarModel methods
        public String getAsString() { return valueStr; }

        @Override
        public String toString() { return getAsString(); }
    }


    public static class FtlTextWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String text;
        protected FtlNodeWrapper parentNode;
        FtlTextWrapper(String text, FtlNodeWrapper parentNode) {
            this.text = text;
            this.parentNode = parentNode;
        }

        public Object getAdaptedObject(Class aClass) { return text; }

        // TemplateNodeModel methods
        public TemplateNodeModel getParentNode() { return parentNode; }
        public TemplateSequenceModel getChildNodes() { return this; }
        public String getNodeName() { return "@text"; }
        public String getNodeType() { return "text"; }
        /** Namespace not supported for now. */
        public String getNodeNamespace() { return null; }

        // TemplateSequenceModel methods
        public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(getAsString());
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Text node only has 1 value. Tried to get index [${i}]");
        }
        public int size() { return 1; }

        // TemplateScalarModel methods
        public String getAsString() { return text != null ? text : ""; }

        @Override
        public String toString() { return getAsString(); }
    }

    public static class FtlNodeListWrapper implements TemplateSequenceModel {
        protected ArrayList<TemplateModel> nodeList = new ArrayList<>();
        FtlNodeListWrapper(ArrayList<MNode> mnodeList, FtlNodeWrapper parentNode) {
            for (int i = 0; i < mnodeList.size(); i++)
                nodeList.add(new FtlNodeWrapper(mnodeList.get(i), parentNode));
        }

        FtlNodeListWrapper(String text, FtlNodeWrapper parentNode) {
            nodeList.add(new FtlTextWrapper(text, parentNode));
        }

        public TemplateModel get(int i) { return nodeList.get(i); }
        public int size() { return nodeList.size(); }

        @Override
        public String toString() { return nodeList.toString(); }
    }
}
