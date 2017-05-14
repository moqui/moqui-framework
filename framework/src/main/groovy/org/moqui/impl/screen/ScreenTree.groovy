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
package org.moqui.impl.screen

import groovy.transform.CompileStatic
import org.moqui.util.ContextStack
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenTree {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenTree.class)

    protected ExecutionContextFactoryImpl ecfi
    protected ScreenDefinition sd
    protected MNode treeNode
    protected String location

    // protected Map<String, ScreenDefinition.ParameterItem> parameterByName = [:]
    protected Map<String, TreeNode> nodeByName = [:]
    protected List<TreeSubNode> subNodeList = []

    ScreenTree(ExecutionContextFactoryImpl ecfi, ScreenDefinition sd, MNode treeNode, String location) {
        this.ecfi = ecfi
        this.sd = sd
        this.treeNode = treeNode
        this.location = location

        // prep tree-node
        for (MNode treeNodeNode in treeNode.children("tree-node"))
            nodeByName.put(treeNodeNode.attribute("name"), new TreeNode(this, treeNodeNode, location + ".node." + treeNodeNode.attribute("name")))

        // prep tree-sub-node
        for (MNode treeSubNodeNode in treeNode.children("tree-sub-node"))
            subNodeList.add(new TreeSubNode(this, treeSubNodeNode, location + ".subnode." + treeSubNodeNode.attribute("node-name")))
    }

    void sendSubNodeJson() {
        // NOTE: This method is very specific to jstree

        ExecutionContextImpl eci = ecfi.getEci()
        ContextStack cs = eci.getContext()

        // logger.warn("========= treeNodeId = ${cs.get("treeNodeId")}")
        // if this is the root node get the main tree sub-nodes, otherwise find the node and use its sub-nodes
        List<TreeSubNode> currentSubNodeList = null
        if (cs.get("treeNodeId") == "#") {
            currentSubNodeList = subNodeList
        } else {
            // logger.warn("======== treeNodeName = ${cs.get("treeNodeName")}")
            if (cs.get("treeNodeName")) currentSubNodeList = nodeByName.get(cs.get("treeNodeName"))?.subNodeList
            if (currentSubNodeList == null) {
                // if no treeNodeName passed through just use the first defined node, though this shouldn't happen
                logger.warn("No treeNodeName passed in request for child nodes for node [${cs.get("treeNodeId")}] in tree [${this.location}], using first node in tree definition.")
                currentSubNodeList = nodeByName.values().first().subNodeList
            }
        }

        List outputNodeList = getChildNodes(currentSubNodeList, eci, cs, true)

        // logger.warn("========= outputNodeList = ${outputNodeList}")
        eci.getWeb().sendJsonResponse(outputNodeList)
    }

    List<Map> getChildNodes(List<TreeSubNode> currentSubNodeList, ExecutionContextImpl eci, ContextStack cs, boolean recurse) {
        List<Map> outputNodeList = []

        for (TreeSubNode tsn in currentSubNodeList) {
            // check condition
            if (tsn.condition != null && !tsn.condition.checkCondition(eci)) continue
            // run actions
            if (tsn.actions != null) tsn.actions.run(eci)

            TreeNode tn = nodeByName.get(tsn.treeSubNodeNode.attribute("node-name"))

            // iterate over the list and add a response node for each entry
            String nodeListName = tsn.treeSubNodeNode.attribute("list") ?: "nodeList"
            List nodeList = (List) eci.getResource().expression(nodeListName, "")
            // logger.warn("======= nodeList named [${nodeListName}]: ${nodeList}")
            Iterator i = nodeList?.iterator()
            int index = 0
            while (i?.hasNext()) {
                Object nodeListEntry = i.next()

                cs.push()
                try {
                    cs.put("nodeList_entry", nodeListEntry)
                    cs.put("nodeList_index", index)
                    cs.put("nodeList_has_next", i.hasNext())

                    // check condition
                    if (tn.condition != null && !tn.condition.checkCondition(eci)) continue
                    // run actions
                    if (tn.actions != null) tn.actions.run(eci)

                    MNode showNode = tn.linkNode != null ? tn.linkNode : tn.labelNode
                    String id = eci.getResource().expand((String) showNode.attribute("id"), tn.location + ".id")
                    String text = eci.getResource().expand((String) showNode.attribute("text"), tn.location + ".text")
                    Map aAttrMap = (Map) null
                    if (tn.linkNode != null) {
                        ScreenUrlInfo.UrlInstance urlInstance = ((ScreenRenderImpl) cs.get("sri")).makeUrlByType((String) tn.linkNode.attribute("url"),
                                (String) tn.linkNode.attribute("url-type") ?: "transition",
                                tn.linkNode, (String) tn.linkNode.attribute("expand-transition-url") ?: "true")

                        boolean noParam = tn.linkNode.attribute("url-noparam") == "true"
                        String urlText = noParam ? urlInstance.getPath() : urlInstance.getPathWithParams()
                        String hrefText = urlText
                        String loadId = tn.linkNode.attribute("dynamic-load-id")
                        if (loadId) {
                            // NOTE: the void(0) is needed for Firefox and other browsers that render the result of the JS expression
                            hrefText = "javascript:{\$('#${loadId}').load('${urlText}'); void(0);}"
                        }
                        aAttrMap = [href:hrefText, loadId:loadId, urlText:urlText]
                    }

                    boolean isOpen = ((String) cs.get("treeOpenPath"))?.startsWith(id)

                    // now get children to check if has some, and if in treeOpenPath include them
                    List<Map> childNodeList = null
                    if (recurse) {
                        cs.push()
                        try {
                            cs.put("treeNodeId", id)
                            childNodeList = getChildNodes(tn.subNodeList, eci, cs, isOpen)
                        } finally {
                            cs.pop()
                        }
                    }

                    // NOTE: passing href as either URL or JS to load (for static rendering with jstree), plus plain loadId and urlText for more dynamic stuff
                    Map<String, Object> subNodeMap = [id:id, text:text,
                            li_attr:["treeNodeName":tn.treeNodeNode.attribute("name")]] as Map<String, Object>
                    if (aAttrMap != null) subNodeMap.a_attr = aAttrMap
                    if (isOpen) {
                        subNodeMap.state = [opened:true, selected:(cs.get("treeOpenPath") == id)] as Map<String, Object>
                        subNodeMap.children = childNodeList
                    } else {
                        subNodeMap.children = childNodeList as boolean
                    }
                    outputNodeList.add(subNodeMap)
                    /* structure of JSON object from jstree docs:
                        {
                          id          : "string" // will be autogenerated if omitted
                          text        : "string" // node text
                          icon        : "string" // string for custom
                          state       : {
                            opened    : boolean  // is the node open
                            disabled  : boolean  // is the node disabled
                            selected  : boolean  // is the node selected
                          },
                          children    : []  // array of strings or objects
                          li_attr     : {}  // attributes for the generated LI node
                          a_attr      : {}  // attributes for the generated A node
                        }
                     */
                } finally {
                    cs.pop()
                }
            }
        }

        // logger.warn("========= outputNodeList: ${outputNodeList}")
        return outputNodeList
    }

    static class TreeNode {
        protected ScreenTree screenTree
        protected MNode treeNodeNode
        protected String location

        protected XmlAction condition = null
        protected XmlAction actions = null
        protected MNode linkNode = null
        protected MNode labelNode = null
        protected List<TreeSubNode> subNodeList = []

        TreeNode(ScreenTree screenTree, MNode treeNodeNode, String location) {
            this.screenTree = screenTree
            this.treeNodeNode = treeNodeNode
            this.location = location
            this.linkNode = treeNodeNode.first("link")
            this.labelNode = treeNodeNode.first("label")

            // prep condition
            if (treeNodeNode.hasChild("condition") && treeNodeNode.first("condition").children) {
                // the script is effectively the first child of the condition element
                condition = new XmlAction(screenTree.ecfi, treeNodeNode.first("condition").children[0], location + ".condition")
            }
            // prep actions
            if (treeNodeNode.hasChild("actions")) actions = new XmlAction(screenTree.ecfi, treeNodeNode.first("actions"), location + ".actions")

            // prep tree-sub-node
            for (MNode treeSubNodeNode in treeNodeNode.children("tree-sub-node"))
                subNodeList.add(new TreeSubNode(screenTree, treeSubNodeNode, location + ".subnode." + treeSubNodeNode.attribute("node-name")))
        }
    }

    static class TreeSubNode {
        protected ScreenTree screenTree
        protected MNode treeSubNodeNode
        protected String location

        protected XmlAction condition = null
        protected XmlAction actions = null

        TreeSubNode(ScreenTree screenTree, MNode treeSubNodeNode, String location) {
            this.screenTree = screenTree
            this.treeSubNodeNode = treeSubNodeNode
            this.location = location

            // prep condition
            if (treeSubNodeNode.hasChild("condition") && treeSubNodeNode.first("condition").children) {
                // the script is effectively the first child of the condition element
                condition = new XmlAction(screenTree.ecfi, treeSubNodeNode.first("condition").children[0], location + ".condition")
            }
            // prep actions
            if (treeSubNodeNode.hasChild("actions")) actions =
                    new XmlAction(screenTree.ecfi, treeSubNodeNode.first("actions"), location + ".actions")
        }
    }
}
