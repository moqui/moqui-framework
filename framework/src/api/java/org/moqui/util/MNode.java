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
package org.moqui.util;

import groovy.lang.Closure;
import groovy.util.Node;
import groovy.util.NodeList;
import groovy.util.XmlParser;
import org.moqui.BaseException;
import org.moqui.context.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.util.*;

/** An alternative to groovy.util.Node with methods more type safe and generally useful in Moqui. */
public class MNode {
    protected final static Logger logger = LoggerFactory.getLogger(MNode.class);

    final static Map<String, MNode> parsedNodeCache = new HashMap<>();

    /* ========== Factories (XML Parsing) ========== */

    public static MNode parse(ResourceReference rr) throws BaseException {
        if (rr == null || !rr.getExists()) return null;
        String location = rr.getLocation();
        MNode cached = parsedNodeCache.get(location);
        if (cached != null && cached.lastModified >= rr.getLastModified()) return cached;

        MNode node = parse(location, rr.openStream());
        node.lastModified = rr.getLastModified();
        if (node.lastModified > 0) parsedNodeCache.put(location, node);
        return node;
    }
    /** Parse from an InputStream and close the stream */
    public static MNode parse(String location, InputStream is) throws BaseException {
        if (is == null) return null;
        try {
            return parse(location, new InputSource(is));
        } finally {
            try { is.close(); }
            catch (IOException e) { throw new BaseException("Error closing XML stream from " + location, e); }
        }
    }
    public static MNode parse(File fl) throws BaseException {
        if (fl == null || !fl.exists()) return null;

        String location = fl.getPath();
        MNode cached = parsedNodeCache.get(location);
        if (cached != null && cached.lastModified >= fl.lastModified()) return cached;

        FileReader fr = null;
        try {
            fr = new FileReader(fl);
            MNode node = parse(fl.getPath(), new InputSource(fr));
            node.lastModified = fl.lastModified();
            if (node.lastModified > 0) parsedNodeCache.put(location, node);
            return node;
        } catch (Exception e) {
            throw new BaseException("Error parsing XML file at " + fl.getPath(), e);
        } finally {
            try { if (fr != null) fr.close(); }
            catch (IOException e) { throw new BaseException("Error closing XML file at " + fl.getPath(), e); }
        }
    }
    public static MNode parseText(String location, String text) throws BaseException {
        if (text == null || text.length() == 0) return null;
        return parse(location, new InputSource(new StringReader(text)));
    }

    public static MNode parse(String location, InputSource isrc) {
        try {
            MNodeXmlHandler xmlHandler = new MNodeXmlHandler();
            XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(xmlHandler);
            reader.parse(isrc);
            return xmlHandler.getRootNode();
        } catch (Exception e) {
            throw new BaseException("Error parsing XML from " + location, e);
        }
    }

    /* ========== Fields ========== */

    protected final String nodeName;
    protected final Map<String, String> attributeMap = new LinkedHashMap<>();
    protected MNode parentNode = null;
    protected final ArrayList<MNode> childList = new ArrayList<>();
    protected final Map<String, ArrayList<MNode>> childrenByName = new HashMap<>();
    protected String childText = null;
    protected long lastModified = 0;

    /* ========== Constructors ========== */

    public MNode(Node node) {
        nodeName = (String) node.name();
        Set attrEntries = node.attributes().entrySet();
        for (Object entryObj : attrEntries) if (entryObj instanceof Map.Entry) {
            Map.Entry entry = (Map.Entry) entryObj;
            if (entry.getKey() != null)
                attributeMap.put(entry.getKey().toString(), entry.getValue() != null ? entry.getValue().toString() : null);
        }
        for (Object childObj : node.children()) {
            if (childObj instanceof Node) {
                append((Node) childObj);
            } else if (childObj instanceof NodeList) {
                NodeList nl = (NodeList) childObj;
                for (Object nlEntry : nl) {
                    if (nlEntry instanceof Node) {
                        append((Node) nlEntry);
                    }
                }
            }
        }
        childText = gnodeText(node);
        if (childText != null && childText.trim().length() == 0) childText = null;

        // if ("entity".equals(nodeName)) logger.info("Groovy Node:\n" + node + "\n MNode:\n" + toString());
    }
    public MNode(String name, Map<String, String> attributes, MNode parent, List<MNode> children, String text) {
        nodeName = name;
        if (attributes != null) attributeMap.putAll(attributes);
        parentNode = parent;
        if (children != null) childList.addAll(children);
        if (text != null && text.trim().length() > 0) childText = text;
    }
    public MNode(String name, Map<String, String> attributes) {
        nodeName = name;
        if (attributes != null) attributeMap.putAll(attributes);
    }

    /* ========== Get Methods ========== */

    /** If name starts with an ampersand (@) then get an attribute, otherwise get a list of child nodes with the given name. */
    public Object get(String name) {
        if (name != null && name.length() > 0 && name.charAt(0) == '@') {
            return attribute(name.substring(1));
        } else {
            return children(name);
        }
    }
    /** Groovy specific method for square brace syntax */
    public Object getAt(String name) { return get(name); }

    public String getName() { return nodeName; }
    public Map<String, String> getAttributes() { return attributeMap; }
    public String attribute(String attrName) { return attributeMap.get(attrName); }

    public MNode getParent() { return parentNode; }
    public ArrayList<MNode> getChildren() { return childList; }
    public ArrayList<MNode> children(String name) {
        if (name == null) return childList;
        ArrayList<MNode> curList = childrenByName.get(name);
        if (curList != null) return curList;

        curList = new ArrayList<>();
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) curList.add(curChild);
        }
        childrenByName.put(name, curList);
        return curList;
    }
    public ArrayList<MNode> children(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
        return curList;
    }
    public boolean hasChild(String name) {
        if (name == null) return false;
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) return true;
        }
        return false;
    }

    /** Search all descendants for nodes matching any of the names, return a Map with a List for each name with nodes
     * found or empty List if no nodes found */
    public Map<String, ArrayList<MNode>> descendants(Set<String> names) {
        Map<String, ArrayList<MNode>> nodes = new HashMap<>();
        for (String name : names) nodes.put(name, new ArrayList<MNode>());
        descendantsInternal(names, nodes);
        return nodes;
    }
    protected void descendantsInternal(Set<String> names, Map<String, ArrayList<MNode>> nodes) {
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (names == null || names.contains(curChild.nodeName)) {
                ArrayList<MNode> curList = nodes.get(curChild.nodeName);
                curList.add(curChild);
            }
            curChild.descendantsInternal(names, nodes);
        }
    }

    public ArrayList<MNode> depthFirst(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        depthFirstInternal(condition, curList);
        return curList;
    }
    protected void depthFirstInternal(Closure<Boolean> condition, ArrayList<MNode> curList) {
        int childListSize = childList.size();
        // all grand-children first
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            curChild.depthFirstInternal(condition, curList);
        }
        // then children
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
    }
    public ArrayList<MNode> breadthFirst(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        breadthFirstInternal(condition, curList);
        return curList;
    }
    protected void breadthFirstInternal(Closure<Boolean> condition, ArrayList<MNode> curList) {
        int childListSize = childList.size();
        // direct children first
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
        // then grand-children
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            curChild.breadthFirstInternal(condition, curList);
        }
    }

    /** Get the first child node */
    public MNode first() { return childList.size() > 0 ? childList.get(0) : null; }
    /** Get the first child node with the given name */
    public MNode first(String name) {
        if (name == null) return first();

        ArrayList<MNode> nameChildren = children(name);
        if (nameChildren.size() > 0) return nameChildren.get(0);
        return null;

        /* with cache in children(name) that is faster than searching every time here:
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) return curChild;
        }
        return null;
        */
    }
    public MNode first(Closure<Boolean> condition) {
        if (condition == null) return first();
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (condition.call(curChild)) return curChild;
        }
        return null;
    }

    public String getText() { return childText; }

    public MNode deepCopy(MNode parent) {
        MNode newNode = new MNode(nodeName, attributeMap, parent, null, childText);
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            newNode.childList.add(curChild.deepCopy(newNode));
        }
        // if ("entity".equals(nodeName)) logger.info("Original MNode:\n" + this.toString() + "\n Clone MNode:\n" + newNode.toString());
        return newNode;
    }

    /* ========== Child Modify Methods ========== */

    public void append(MNode child) {
        childrenByName.remove(child.nodeName);
        childList.add(child);
        child.parentNode = this;
    }
    public MNode append(Node child) {
        MNode newNode = new MNode(child);
        childrenByName.remove(newNode.nodeName);
        childList.add(newNode);
        newNode.parentNode = this;
        return newNode;
    }
    public MNode append(String name, Map<String, String> attributes, List<MNode> children, String text) {
        childrenByName.remove(name);
        MNode newNode = new MNode(name, attributes, this, children, text);
        childList.add(newNode);
        return newNode;
    }
    public MNode append(String name, Map<String, String> attributes) {
        childrenByName.remove(name);
        MNode newNode = new MNode(name, attributes, this, null, null);
        childList.add(newNode);
        return newNode;
    }
    public MNode replace(int index, String name, Map<String, String> attributes) {
        childrenByName.remove(name);
        MNode newNode = new MNode(name, attributes, this, null, null);
        childList.set(index, newNode);
        return newNode;
    }

    public boolean remove(String name) {
        childrenByName.remove(name);
        boolean removed = false;
        for (int i = 0; i < childList.size(); ) {
            MNode curChild = childList.get(i);
            if (curChild.nodeName.equals(name)) {
                childList.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }
    public boolean remove(Closure<Boolean> condition) {
        boolean removed = false;
        for (int i = 0; i < childList.size(); ) {
            MNode curChild = childList.get(i);
            if (condition.call(curChild)) {
                childrenByName.remove(curChild.nodeName);
                childList.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }

    /* ========== String Methods ========== */

    public String toString() {
        StringBuilder sb = new StringBuilder();
        addToSb(sb, 0);
        return sb.toString();
    }
    protected void addToSb(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("    ");
        sb.append('<').append(nodeName);
        for (Map.Entry<String, String> entry : attributeMap.entrySet())
            sb.append(' ').append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        if ((childText != null && childText.length() > 0) || childList.size() > 0) {
            sb.append(">");
            if (childText != null) sb.append("<![CDATA[").append(childText).append("]]>");
            if (childList.size() > 0) {
                for (MNode child : childList) {
                    sb.append('\n');
                    child.addToSb(sb, level + 1);
                }
                sb.append("\n");
                for (int i = 0; i < level; i++) sb.append("    ");
            }

            sb.append("</").append(nodeName).append('>');
        } else {
            sb.append("/>");
        }
    }

    public static String gnodeText(Object nodeObj) {
        if (nodeObj == null) return "";
        Node theNode = null;
        if (nodeObj instanceof Node) {
            theNode = (Node) nodeObj;
        } else if (nodeObj instanceof NodeList) {
            NodeList nl = (NodeList) nodeObj;
            if (nl.size() > 0) theNode = (Node) nl.get(0);
        }
        if (theNode == null) return "";
        List<String> textList = theNode.localText();
        if (textList != null) {
            if (textList.size() == 1) {
                return textList.get(0);
            } else {
                StringBuilder sb = new StringBuilder();
                for (String txt : textList) sb.append(txt).append("\n");
                return sb.toString();
            }
        } else {
            return "";
        }
    }

    static class MNodeXmlHandler extends DefaultHandler {
        protected Locator locator = null;
        protected long nodesRead = 0;

        protected MNode rootNode = null;
        protected MNode curNode = null;
        protected StringBuilder curText = null;

        MNodeXmlHandler() { }
        MNode getRootNode() { return rootNode; }
        long getNodesRead() { return nodesRead; }

        public void startElement(String ns, String localName, String qName, Attributes attributes) {
            // logger.info("startElement ns [${ns}], localName [${localName}] qName [${qName}]")
            if (curNode == null) {
                curNode = new MNode(qName, null);
                if (rootNode == null) rootNode = curNode;
            } else {
                curNode = curNode.append(qName, null);
            }

            int length = attributes.getLength();
            for (int i = 0; i < length; i++) {
                String name = attributes.getLocalName(i);
                String value = attributes.getValue(i);
                if (name == null || name.length() == 0) name = attributes.getQName(i);
                curNode.attributeMap.put(name, value);
            }
        }

        public void characters(char[] chars, int offset, int length) {
            if (curText == null) curText = new StringBuilder();
            curText.append(chars, offset, length);
        }
        public void endElement(String ns, String localName, String qName) {
            if (!qName.equals(curNode.nodeName)) throw new IllegalStateException("Invalid close element " + qName + ", was expecting " + curNode.nodeName);
            if (curText != null) {
                String curString = curText.toString().trim();
                if (curString.length() > 0) curNode.childText = curString;
            }
            curNode = curNode.parentNode;
            curText = null;
        }

        public void setDocumentLocator(Locator locator) { this.locator = locator; }
    }
}
