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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** An alternative to groovy.util.Node with methods more type safe and generally useful in Moqui. */
public class MNode {
    protected final static Logger logger = LoggerFactory.getLogger(MNode.class);

    public static MNode parse(ResourceReference rr) throws BaseException {
        if (rr == null || !rr.getExists()) return null;
        return parse(rr.getLocation(), rr.openStream());
    }
    public static MNode parse(String location, InputStream is) throws BaseException {
        if (is == null) return null;
        try {
            Node node = new XmlParser().parse(is);
            return new MNode(node);
        } catch (Exception e) {
            throw new BaseException("Error parsing XML stream from " + location, e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                throw new BaseException("Error closing XML stream from " + location, e);
            }
        }
    }
    public static MNode parse(File fl) throws BaseException {
        if (fl == null || !fl.exists()) return null;
        try {
            Node node = new XmlParser().parse(fl);
            return new MNode(node);
        } catch (Exception e) {
            throw new BaseException("Error parsing XML file at " + fl.getPath(), e);
        }
    }
    public static MNode parseText(String text) throws BaseException {
        if (text == null || text.length() == 0) return null;
        try {
            Node node = new XmlParser().parseText(text);
            return new MNode(node);
        } catch (Exception e) {
            throw new BaseException("Error parsing XML text", e);
        }
    }

    protected final String nodeName;
    protected final Map<String, String> attributeMap = new LinkedHashMap<>();
    protected MNode parentNode = null;
    protected final ArrayList<MNode> childList = new ArrayList<>();
    protected String childText = null;

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

    public String getName() { return nodeName; }
    public Map<String, String> getAttributes() { return attributeMap; }
    public String attribute(String attrName) { return attributeMap.get(attrName); }

    public MNode getParent() { return parentNode; }
    public ArrayList<MNode> getChildren() { return childList; }
    public ArrayList<MNode> children(String name) {
        ArrayList<MNode> curList = new ArrayList<>();
        if (name == null) return curList;
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) curList.add(curChild);
        }
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
        if (name == null) return null;
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) return curChild;
        }
        return null;
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

    public void append(MNode child) {
        childList.add(child);
        child.parentNode = this;
    }
    public MNode append(Node child) {
        MNode newNode = new MNode(child);
        childList.add(newNode);
        newNode.parentNode = this;
        return newNode;
    }
    public MNode append(String name, Map<String, String> attributes, List<MNode> children, String text) {
        MNode newNode = new MNode(name, attributes, this, children, text);
        childList.add(newNode);
        return newNode;
    }
    public MNode append(String name, Map<String, String> attributes) {
        MNode newNode = new MNode(name, attributes, this, null, null);
        childList.add(newNode);
        return newNode;
    }
    public MNode replace(int index, String name, Map<String, String> attributes) {
        MNode newNode = new MNode(name, attributes, this, null, null);
        childList.set(index, newNode);
        return newNode;
    }

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

    public boolean remove(String name) {
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
                childList.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }

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
}
