<#--
This software is in the public domain under CC0 1.0 Universal plus a
Grant of Patent License.

To the extent possible under law, the author(s) have dedicated all
copyright and related and neighboring rights to this software to the
public domain worldwide. This software is distributed without any
warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software (see the LICENSE.md file). If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.
-->
<#-- NOTE: no empty lines before the first #macro otherwise FTL outputs empty lines -->
<#include "DefaultScreenMacros.any.ftl"/>
<#macro @element><p>=== Doing nothing for element ${.node?node_name}, not yet implemented. ===</p></#macro>
<#macro screen><#recurse></#macro>
<#macro widgets><#t>
    <#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
    <#recurse>
    <#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
</#macro>
<#macro "fail-widgets"><#t>
    <#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
    <#recurse>
    <#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
</#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"><#if hideNav! != "true">
    <#assign displayMenu = sri.activeInCurrentMenu!>
    <#assign menuId = .node["@id"]!"subscreensMenu">
    <#assign menuTitle = .node["@title"]!sri.getActiveScreenDef().getDefaultMenuName()!"Menu">
    <#if .node["@type"]! == "popup">
        <li id="${menuId}" class="dropdown">
            <a href="#" class="dropdown-toggle" data-toggle="dropdown">${ec.getResource().expand(menuTitle, "")} <i class="fa fa-chevron-right"></i></a>
            <ul class="dropdown-menu">
                <#list sri.getActiveScreenDef().getMenuSubscreensItems() as subscreensItem>
                    <#assign urlInstance = sri.buildUrl(subscreensItem.name)>
                    <#if urlInstance.isPermitted()>
                        <li<#if urlInstance.inCurrentScreenPath> class="active"</#if>><a href="<#if urlInstance.disableLink>#<#else>${urlInstance.minimalPathUrlWithParams}</#if>"><#rt>
                            <#assign expandedMenuTitle = ec.getResource().expand(subscreensItem.menuTitle, "")>
                            <#if urlInstance.sui.menuImage?has_content>
                                <#if urlInstance.sui.menuImageType == "icon">
                                    <#t><i class="${urlInstance.sui.menuImage}" style="padding-right: 8px;"></i>
                                <#elseif urlInstance.sui.menuImageType == "url-plain">
                                    <#t><img src="${urlInstance.sui.menuImage}" alt="${expandedMenuTitle}" width="18" style="padding-right: 4px;"/>
                                <#else><#rt>
                                    <#t><img src="${sri.buildUrl(urlInstance.sui.menuImage).url}" alt="${expandedMenuTitle}" height="18" style="padding-right: 4px;"/>
                                </#if><#rt>
                            <#else><#rt>
                                <#t><i class="fa fa-link" style="padding-right: 8px;"></i>
                            </#if><#rt>
                            <#t>${expandedMenuTitle}
                        <#lt></a></li>
                    </#if>
                </#list>
            </ul>
        </li>
        <#-- move the menu to the header-menus container -->
        <script>$("#${.node["@header-menus-id"]!"header-menus"}").append($("#${menuId}"));</script>
    <#elseif .node["@type"]! == "popup-tree">
    <#else>
        <#-- default to type=tab -->
        <#if displayMenu!>
            <ul<#if .node["@id"]?has_content> id="${.node["@id"]}"</#if> class="nav nav-tabs" role="tablist">
                <#list sri.getActiveScreenDef().getMenuSubscreensItems() as subscreensItem>
                    <#assign urlInstance = sri.buildUrl(subscreensItem.name)>
                    <#if urlInstance.isPermitted()>
                        <li class="<#if urlInstance.inCurrentScreenPath>active</#if><#if urlInstance.disableLink> disabled</#if>"><#if urlInstance.disableLink>${ec.getResource().expand(subscreensItem.menuTitle, "")}<#else><a href="${urlInstance.minimalPathUrlWithParams}">${ec.getL10n().localize(subscreensItem.menuTitle)}</a></#if></li>
                    </#if>
                </#list>
            </ul>
        </#if>
        <#-- add to navbar bread crumbs too -->
        <a id="${menuId}-crumb" class="navbar-text" href="${sri.buildUrl(".")}">${ec.getResource().expand(menuTitle, "")} <i class="fa fa-chevron-right"></i></a>
        <script>$("#navbar-menu-crumbs").append($("#${menuId}-crumb"));</script>
    </#if>
</#if></#macro>

<#macro "subscreens-active">
    ${sri.renderSubscreen()}
</#macro>

<#macro "subscreens-panel">
    <#assign dynamic = .node["@dynamic"]! == "true" && .node["@id"]?has_content>
    <#assign dynamicActive = 0>
    <#assign displayMenu = sri.activeInCurrentMenu!true && hideNav! != "true">
    <#assign menuId><#if .node["@id"]?has_content>${.node["@id"]}-menu<#else>subscreensPanelMenu</#if></#assign>
    <#assign menuTitle = .node["@title"]!sri.getActiveScreenDef().getDefaultMenuName()!"Menu">
    <#if .node["@type"]! == "popup">
        <#if hideNav! != "true">
        <li id="${menuId}" class="dropdown">
            <a href="#" class="dropdown-toggle" data-toggle="dropdown">${ec.getResource().expand(menuTitle, "")} <i class="fa fa-chevron-right"></i></a>
            <ul class="dropdown-menu">
                <#list sri.getActiveScreenDef().getMenuSubscreensItems() as subscreensItem>
                    <#assign urlInstance = sri.buildUrl(subscreensItem.name)>
                    <#if urlInstance.isPermitted()>
                        <li<#if urlInstance.inCurrentScreenPath> class="active"</#if>><a href="<#if urlInstance.disableLink>#<#else>${urlInstance.minimalPathUrlWithParams}</#if>"><#rt>
                            <#assign expandedMenuTitle = ec.getResource().expand(subscreensItem.menuTitle, "")>
                            <#if urlInstance.sui.menuImage?has_content>
                                <#if urlInstance.sui.menuImageType == "icon">
                                    <#t><i class="${urlInstance.sui.menuImage}" style="padding-right: 8px;"></i>
                                <#elseif urlInstance.sui.menuImageType == "url-plain">
                                    <#t><img src="${urlInstance.sui.menuImage}" alt="${expandedMenuTitle}" width="18" style="padding-right: 4px;"/>
                                <#else><#rt>
                                    <#t><img src="${sri.buildUrl(urlInstance.sui.menuImage).url}" alt="${expandedMenuTitle}" height="18" style="padding-right: 4px;"/>
                                </#if><#rt>
                            <#else><#rt>
                                <#t><i class="fa fa-link" style="padding-right: 8px;"></i>
                            </#if><#rt>
                            <#t>${expandedMenuTitle}
                        <#lt></a></li>
                    </#if>
                </#list>
            </ul>
        </li>
        <#-- move the menu to the header menus section -->
        <script>$("#${.node["@header-menus-id"]!"header-menus"}").append($("#${menuId}"));</script>
        </#if>
        ${sri.renderSubscreen()}
    <#elseif .node["@type"]! == "stack">
        <h1>LATER stack type subscreens-panel not yet supported.</h1>
    <#elseif .node["@type"]! == "wizard">
        <h1>LATER wizard type subscreens-panel not yet supported.</h1>
    <#else>
        <#-- default to type=tab -->
        <div<#if .node["@id"]?has_content> id="${.node["@id"]}-tabpanel"</#if>>
            <#assign menuSubscreensItems=sri.getActiveScreenDef().getMenuSubscreensItems()>
            <#if menuSubscreensItems?has_content && (menuSubscreensItems?size > 1)>
                <#if displayMenu>
                    <ul<#if .node["@id"]?has_content> id="${.node["@id"]}-menu"</#if> class="nav nav-tabs" role="tablist">
                    <#list menuSubscreensItems as subscreensItem>
                        <#assign urlInstance = sri.buildUrl(subscreensItem.name)>
                        <#if urlInstance.isPermitted()>
                            <#if dynamic>
                                <#assign urlInstance = urlInstance.addParameter("lastStandalone", "true")>
                                <#if urlInstance.inCurrentScreenPath>
                                    <#assign dynamicActive = subscreensItem_index>
                                    <#assign urlInstance = urlInstance.addParameters(ec.getWeb().requestParameters)>
                                </#if>
                            </#if>
                            <li class="<#if urlInstance.disableLink>disabled<#elseif urlInstance.inCurrentScreenPath>active</#if>"><a href="<#if urlInstance.disableLink>#<#else>${urlInstance.minimalPathUrlWithParams}</#if>">${ec.getResource().expand(subscreensItem.menuTitle, "")}</a></li>
                        </#if>
                    </#list>
                    </ul>
                </#if>
            </#if>
            <#if hideNav! != "true">
                <#-- add to navbar bread crumbs too -->
                <a id="${menuId}-crumb" class="navbar-text" href="${sri.buildUrl(".")}">${ec.getResource().expand(menuTitle, "")} <i class="fa fa-chevron-right"></i></a>
                <script>$("#navbar-menu-crumbs").append($("#${menuId}-crumb"));</script>
            </#if>
            <#if !dynamic || !displayMenu>
            <#-- these make it more similar to the HTML produced when dynamic, but not needed: <div<#if .node["@id"]?has_content> id="${.node["@id"]}-active"</#if> class="ui-tabs-panel"> -->
            ${sri.renderSubscreen()}
            <#-- </div> -->
            </#if>
        </div>
        <#if dynamic && displayMenu>
            <#assign afterScreenScript>
                $("#${.node["@id"]}").tabs({ collapsible: true, selected: ${dynamicActive},
                    spinner: '<span class="ui-loading">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</span>',
                    ajaxOptions: { error: function(xhr, status, index, anchor) { $(anchor.hash).html("Error loading screen..."); } },
                    load: function(event, ui) { <#-- activateAllButtons(); --> }
                });
            </#assign>
            <#t>${sri.appendToScriptWriter(afterScreenScript)}
        </#if>
    </#if>
</#macro>

<#-- ================ Section ================ -->
<#macro section>
    <#if sri.doBoundaryComments()><!-- BEGIN section[@name=${.node["@name"]}] --></#if>
    ${sri.renderSection(.node["@name"])}
    <#if sri.doBoundaryComments()><!-- END   section[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro "section-iterate">
    <#if sri.doBoundaryComments()><!-- BEGIN section-iterate[@name=${.node["@name"]}] --></#if>
    ${sri.renderSection(.node["@name"])}
    <#if sri.doBoundaryComments()><!-- END   section-iterate[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro "section-include">
    <#if sri.doBoundaryComments()><!-- BEGIN section-include[@name=${.node["@name"]}] --></#if>
${sri.renderSection(.node["@name"])}
    <#if sri.doBoundaryComments()><!-- END   section-include[@name=${.node["@name"]}] --></#if>
</#macro>

<#-- ================ Containers ================ -->
<#macro nodeId widgetNode><#if .node["@id"]?has_content>${ec.getResource().expandNoL10n(widgetNode["@id"], "")}<#if listEntryIndex?has_content>_${listEntryIndex}</#if><#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#if></#macro>

<#macro container>
    <#assign contDivId><@nodeId .node/></#assign>
    <${.node["@type"]!"div"}<#if contDivId?has_content> id="${contDivId}"</#if><#if .node["@style"]?has_content> class="${ec.getResource().expandNoL10n(.node["@style"], "")}"</#if>>
    <#recurse>
    </${.node["@type"]!"div"}>
</#macro>

<#macro "container-box">
    <#assign contBoxDivId><@nodeId .node/></#assign>
    <#assign boxHeader = .node["box-header"][0]>
    <#assign boxType = ec.resource.expandNoL10n(.node["@type"], "")!>
    <#if !boxType?has_content><#assign boxType = "default"></#if>
    <div class="panel panel-${boxType}"<#if contBoxDivId?has_content> id="${contBoxDivId}"</#if>>
        <div class="panel-heading">
            <#if boxHeader["@title"]?has_content><h5>${ec.getResource().expand(boxHeader["@title"]!"", "")?html}</h5></#if>
            <#recurse boxHeader>
            <#if .node["box-toolbar"]?has_content>
                <div class="panel-toolbar">
                    <#recurse .node["box-toolbar"][0]>
                </div>
            </#if>
        </div>
        <#if .node["box-body"]?has_content>
            <div class="panel-body"<#if .node["box-body"][0]["@height"]?has_content> style="max-height: ${.node["box-body"][0]["@height"]}px; overflow-y: auto;"</#if>>
                <#recurse .node["box-body"][0]>
            </div>
        </#if>
        <#if .node["box-body-nopad"]?has_content>
            <#recurse .node["box-body-nopad"][0]>
        </#if>
    </div>
</#macro>

<#macro "container-row">
    <#assign contRowDivId><@nodeId .node/></#assign>
    <div class="row<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if contRowDivId?has_content> id="${contRowDivId}"</#if>>
        <#list .node["row-col"] as rowColNode>
            <div class="<#if rowColNode["@lg"]?has_content> col-lg-${rowColNode["@lg"]}</#if><#if rowColNode["@md"]?has_content> col-md-${rowColNode["@md"]}</#if><#if rowColNode["@sm"]?has_content> col-sm-${rowColNode["@sm"]}</#if><#if rowColNode["@xs"]?has_content> col-xs-${rowColNode["@xs"]}</#if><#if rowColNode["@style"]?has_content> ${ec.getResource().expandNoL10n(rowColNode["@style"], "")}</#if>">
                <#recurse rowColNode>
            </div>
        </#list>
    </div>
</#macro>

<#macro "container-panel">
    <#assign panelId><@nodeId .node/></#assign>
    <div<#if panelId?has_content> id="${panelId}"</#if> class="container-panel-outer">
        <#if .node["panel-header"]?has_content>
            <div<#if panelId?has_content> id="${panelId}-header"</#if> class="container-panel-header"><#recurse .node["panel-header"][0]>
            </div>
        </#if>
        <div class="container-panel-middle">
            <#if .node["panel-left"]?has_content>
                <div<#if panelId?has_content> id="${panelId}-left"</#if> class="container-panel-left" style="width: ${.node["panel-left"][0]["@size"]!"180"}px;"><#recurse .node["panel-left"][0]>
                </div>
            </#if>
            <#assign centerClass><#if .node["panel-left"]?has_content><#if .node["panel-right"]?has_content>container-panel-center-both<#else>container-panel-center-left</#if><#else><#if .node["panel-right"]?has_content>container-panel-center-right<#else>container-panel-center-only</#if></#if></#assign>
            <div<#if panelId?has_content> id="${panelId}-center"</#if> class="${centerClass}"><#recurse .node["panel-center"][0]>
        </div>
        <#if .node["panel-right"]?has_content>
            <div<#if panelId?has_content> id="${panelId}-right"</#if> class="container-panel-right" style="width: ${.node["panel-right"][0]["@size"]!"180"}px;"><#recurse .node["panel-right"][0]>
            </div>
        </#if>
        </div>
        <#if .node["panel-footer"]?has_content>
            <div<#if panelId?has_content> id="${panelId}-footer"</#if> class="container-panel-footer"><#recurse .node["panel-footer"][0]>
            </div>
        </#if>
    </div>
</#macro>

<#macro "container-dialog">
    <#assign iconClass = "fa fa-share">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign cdDivId><@nodeId .node/></#assign>
        <button id="${cdDivId}-button" type="button" data-toggle="modal" data-target="#${cdDivId}" data-original-title="${buttonText}" data-placement="bottom" class="btn btn-${ec.getResource().expandNoL10n(.node["@type"]!"primary", "")} btn-sm ${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}"><i class="${iconClass}"></i> ${buttonText}</button>
        <#if _openDialog! == cdDivId><#assign afterScreenScript>$('#${cdDivId}').modal('show'); </#assign><#t>${sri.appendToScriptWriter(afterScreenScript)}</#if>
        <div id="${cdDivId}" class="modal container-dialog" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: ${.node["@width"]!"760"}px;">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                        <h4 class="modal-title">${buttonText}</h4>
                    </div>
                    <div class="modal-body">
                        <#recurse>
                    </div>
                    <#-- <div class="modal-footer"><button type="button" class="btn btn-primary" data-dismiss="modal">Close</button></div> -->
                </div>
            </div>
        </div>
        <script>$('#${cdDivId}').on('shown.bs.modal', function() {
            $("#${cdDivId} :not(.noResetSelect2)>select:not(.noResetSelect2)").select2({ });
            var defFocus = $("#${cdDivId} .default-focus");
            if (defFocus.length) { defFocus.focus(); } else { $("#${cdDivId} form :input:visible:first").focus(); }
        });</script>
    </#if>
</#macro>

<#macro "dynamic-container">
    <#assign dcDivId><@nodeId .node/></#assign>
    <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true").addParameter("_dynamic_container_id", dcDivId)>
    <div id="${dcDivId}"><img src="/images/wait_anim_16x16.gif" alt="Loading..."></div>
    <script>
        function load${dcDivId}() { $("#${dcDivId}").load("${urlInstance.passThroughSpecialParameters().urlWithParams}", function() { <#-- activateAllButtons() --> }); }
        load${dcDivId}();
    </script>
</#macro>

<#macro "dynamic-dialog">
    <#assign iconClass = "fa fa-share">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
        <#assign ddDivId><@nodeId .node/></#assign>

        <button id="${ddDivId}-button" type="button" data-toggle="modal" data-target="#${ddDivId}" data-original-title="${buttonText}" data-placement="bottom" class="btn btn-${.node["@type"]!"primary"} btn-sm"><i class="${iconClass}"></i> ${buttonText}</button>
        <#assign afterFormText>
        <div id="${ddDivId}" class="modal dynamic-dialog" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: ${.node["@width"]!"760"}px;">
                <div class="modal-content">
                    <div class="modal-header">
                        <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                        <h4 class="modal-title">${buttonText}</h4>
                    </div>
                    <div class="modal-body" id="${ddDivId}-body">
                        <img src="/images/wait_anim_16x16.gif" alt="Loading...">
                    </div>
                    <#-- <div class="modal-footer"><button type="button" class="btn btn-primary" data-dismiss="modal">Close</button></div> -->
                </div>
            </div>
        </div>
        <script>
            $("#${ddDivId}").on("show.bs.modal", function () {
                $("#${ddDivId}-body").load('${urlInstance.urlWithParams}', function() {
                    $("#${ddDivId} :not(.noResetSelect2)>select:not(.noResetSelect2)").select2({ });
                    var defFocus = $("#${ddDivId} .default-focus");
                    if (defFocus.length) { defFocus.focus(); } else { $("#${ddDivId} form :input:visible:first").focus(); }
                });
            });
            $("#${ddDivId}").on("hidden.bs.modal", function () { $("#${ddDivId}-body").empty(); $("#${ddDivId}-body").append('<img src="/images/wait_anim_16x16.gif" alt="Loading...">'); });
            <#if _openDialog! == ddDivId>$('#${ddDivId}').modal('show');</#if>
        </script>
        </#assign>
        <#t>${sri.appendToAfterScreenWriter(afterFormText)}
    </#if>
</#macro>

<#-- ==================== Includes ==================== -->
<#macro "include-screen">
<#if sri.doBoundaryComments()><!-- BEGIN include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]!}] --></#if>
${sri.renderIncludeScreen(.node["@location"], .node["@share-scope"]!)}
<#if sri.doBoundaryComments()><!-- END   include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]!}] --></#if>
</#macro>

<#-- ============== Tree ============== -->
<#macro tree>
    <#if .node["@transition"]?has_content>
        <#assign ajaxUrlInfo = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
        <#assign itemsUrl = ajaxUrlInfo.url>
    <#else>
        <#assign ajaxUrlInfo = sri.makeUrlByType("actions", "transition", .node, "true")>
        <#assign itemsUrl = ajaxUrlInfo.url + "/" + .node["@name"]>
    </#if>
    <#assign ajaxParms = ajaxUrlInfo.getParameterMap()>

    <div id="${.node["@name"]}"></div>
    <script>
    $("#${.node["@name"]}").bind('select_node.jstree', function(e,data) {window.location.href = data.node.a_attr.href;}).jstree({
        "core" : { "themes" : { "url" : false, "dots" : true, "icons" : false }, "multiple" : false,
            'data' : {
                dataType: 'json', type: 'POST',
                url: function (node) { return '${itemsUrl}'; },
                data: function (node) { return { treeNodeId: node.id,
                    treeNodeName: (node.li_attr && node.li_attr.treeNodeName ? node.li_attr.treeNodeName : ''),
                    moquiSessionToken: "${(ec.getWeb().sessionToken)!}"
                    <#if .node["@open-path"]??>, treeOpenPath: "${ec.getResource().expandNoL10n(.node["@open-path"], "")}"</#if>
                    <#list ajaxParms.keySet() as pKey>, "${pKey}": "${ajaxParms.get(pKey)!""}"</#list> }; }
            }
        }
    });
    </script>
</#macro>
<#macro "tree-node"><#-- shouldn't be called directly, but just in case --></#macro>
<#macro "tree-sub-node"><#-- shouldn't be called directly, but just in case --></#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link>
    <#assign linkNode = .node>
    <#if linkNode["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(linkNode["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#if linkNode["@entity-name"]?has_content>
            <#assign linkText = ""><#assign linkText = sri.getFieldEntityValue(linkNode)>
        <#else>
            <#assign textMap = "">
            <#if linkNode["@text-map"]?has_content><#assign textMap = ec.getResource().expression(linkNode["@text-map"], "")!></#if>
            <#if textMap?has_content><#assign linkText = ec.getResource().expand(linkNode["@text"], "", textMap)>
                <#else><#assign linkText = ec.getResource().expand(linkNode["@text"]!"", "")></#if>
        </#if>
        <#if linkText == "null"><#assign linkText = ""></#if>
        <#if linkText?has_content || linkNode["image"]?has_content || linkNode["@icon"]?has_content>
            <#if linkNode["@encode"]! != "false"><#assign linkText = linkText?html></#if>
            <#assign urlInstance = sri.makeUrlByType(linkNode["@url"], linkNode["@url-type"]!"transition", linkNode, linkNode["@expand-transition-url"]!"true")>
            <#assign linkDivId><@nodeId .node/></#assign>
            <@linkFormForm linkNode linkDivId linkText urlInstance/>
            <@linkFormLink linkNode linkDivId linkText urlInstance/>
        </#if>
    </#if>
</#macro>
<#macro linkFormLink linkNode linkFormId linkText urlInstance>
    <#assign iconClass = linkNode["@icon"]!>
    <#if !iconClass?has_content && linkNode["@text"]?has_content><#assign iconClass = sri.getThemeIconClass(linkNode["@text"])!></#if>
    <#assign iconClass = ec.getResource().expandNoL10n(iconClass!, "")/>
    <#assign badgeMessage = ec.getResource().expand(linkNode["@badge"]!, "")/>
    <#if urlInstance.disableLink>
        <a href="#"<#if linkFormId?has_content> id="${linkFormId}"</#if> class="disabled text-muted <#if linkNode["@link-type"]! != "anchor" && linkNode["@link-type"]! != "hidden-form-link">btn btn-${linkNode["@btn-type"]!"primary"} btn-sm</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"><#if iconClass?has_content><i class="${iconClass}"></i></#if><#if linkNode["image"]?has_content><#visit linkNode["image"][0]><#else>${linkText}</#if></a>
    <#else>
        <#assign confirmationMessage = ec.getResource().expand(linkNode["@confirmation"]!, "")/>
        <#if (linkNode["@link-type"]! == "anchor" || linkNode["@link-type"]! == "anchor-button") ||
            ((!linkNode["@link-type"]?has_content || linkNode["@link-type"] == "auto") &&
             ((linkNode["@url-type"]?has_content && linkNode["@url-type"] != "transition") || (!urlInstance.hasActions)))>
            <#if linkNode["@dynamic-load-id"]?has_content>
                <#-- NOTE: the void(0) is needed for Firefox and other browsers that render the result of the JS expression -->
                <#assign urlText>javascript:{$('#${linkNode["@dynamic-load-id"]}').load('${urlInstance.urlWithParams}'); void(0);}</#assign>
            <#else>
                <#if linkNode["@url-noparam"]! == "true"><#assign urlText = urlInstance.url/>
                    <#else><#assign urlText = urlInstance.urlWithParams/></#if>
            </#if>
            <#rt><a href="${urlText}"<#if linkFormId?has_content> id="${linkFormId}"</#if><#if linkNode["@target-window"]?has_content> target="${linkNode["@target-window"]}"</#if><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if> class="<#if linkNode["@link-type"]! != "anchor">btn btn-${linkNode["@btn-type"]!"primary"} btn-sm</#if><#if linkNode["@style"]?has_content> ${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>"<#if linkNode["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(linkNode["@tooltip"], "")}"</#if>><#if iconClass?has_content><i class="${iconClass}"></i> </#if>
            <#t><#if linkNode["image"]?has_content><#visit linkNode["image"][0]><#else>${linkText}</#if>
            <#t><#if badgeMessage?has_content> <span class="badge">${badgeMessage}</span></#if>
            <#t></a>
        <#else>
            <#if linkFormId?has_content>
            <#rt><button type="submit" form="${linkFormId}" id="${linkFormId}_button" class="btn btn-${linkNode["@btn-type"]!"primary"} btn-sm<#if linkNode["@style"]?has_content> ${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>"
                    <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if>
                    <#t><#if linkNode["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(linkNode["@tooltip"], "")}"</#if>>
                <#t><#if iconClass?has_content><i class="${iconClass}"></i> </#if>
                <#if linkNode["image"]?has_content>
                    <#t><img src="${sri.makeUrlByType(imageNode["@url"],imageNode["@url-type"]!"content",null,"true")}"<#if imageNode["@alt"]?has_content> alt="${imageNode["@alt"]}"</#if>/>
                <#else>
                    <#t>${linkText}
                </#if>
            <#t><#if badgeMessage?has_content> <span class="badge">${badgeMessage}</span></#if>
            <#t></button>
            </#if>
        </#if>
    </#if>
</#macro>
<#macro linkFormForm linkNode linkFormId linkText urlInstance>
    <#if urlInstance.disableLink>
        <#-- do nothing -->
    <#else>
        <#if (linkNode["@link-type"]! == "anchor" || linkNode["@link-type"]! == "anchor-button") ||
            ((!linkNode["@link-type"]?has_content || linkNode["@link-type"] == "auto") &&
             ((linkNode["@url-type"]?has_content && linkNode["@url-type"] != "transition") || (!urlInstance.hasActions)))>
            <#-- do nothing -->
        <#else>
            <form method="post" action="${urlInstance.url}" name="${linkFormId!""}"<#if linkFormId?has_content> id="${linkFormId}"</#if><#if linkNode["@target-window"]?has_content> target="${linkNode["@target-window"]}"</#if>>
                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                <#assign targetParameters = urlInstance.getParameterMap()>
                <#-- NOTE: using .keySet() here instead of ?keys because ?keys returns all method names with the other keys, not sure why -->
                <#if targetParameters?has_content><#list targetParameters.keySet() as pKey>
                    <input type="hidden" name="${pKey?html}" value="${targetParameters.get(pKey)?default("")?html}"/>
                </#list></#if>
                <#if !linkFormId?has_content>
                    <#assign confirmationMessage = ec.getResource().expand(linkNode["@confirmation"]!, "")/>
                    <#if linkNode["image"]?has_content><#assign imageNode = linkNode["image"][0]/>
                        <input type="image" src="${sri.makeUrlByType(imageNode["@url"],imageNode["@url-type"]!"content",null,"true")}"<#if imageNode["@alt"]?has_content> alt="${imageNode["@alt"]}"</#if><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if>>
                    <#else>
                        <#assign iconClass = linkNode["@icon"]!>
                        <#if !iconClass?has_content && linkNode["@text"]?has_content><#assign iconClass = sri.getThemeIconClass(linkNode["@text"])!></#if>
                        <#assign badgeMessage = ec.getResource().expand(linkNode["@badge"]!, "")/>
                        <#rt><button type="submit" class="<#if linkNode["@link-type"]! == "hidden-form-link">button-plain<#else>btn btn-${linkNode["@btn-type"]!"primary"} btn-sm</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"
                            <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if>
                            <#t><#if linkNode["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(linkNode["@tooltip"], "")}"</#if>>
                            <#t><#if iconClass?has_content><i class="${iconClass}"></i> </#if>${linkText}<#if badgeMessage?has_content> <span class="badge">${badgeMessage}</span></#if></button>
                    </#if>
                </#if>
            </form>
        </#if>
    </#if>
</#macro>

<#macro image>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")>
        <#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#if .node["@hover"]! == "true"><span class="hover-image-container"></#if>
        <img src="${sri.makeUrlByType(.node["@url"], .node["@url-type"]!"content", .node, "true").getUrlWithParams()}" alt="${ec.resource.expand(.node["@alt"]!"image", "")}"<#if .node["@id"]?has_content> id="${.node["@id"]}"</#if><#if .node["@width"]?has_content> width="${.node["@width"]}"</#if><#if .node["@height"]?has_content>height="${.node["@height"]}"</#if><#if .node["@style"]?has_content> class="${ec.getResource().expandNoL10n(.node["@style"], "")}"</#if>/>
        <#if .node["@hover"]! == "true"><img src="${sri.makeUrlByType(.node["@url"], .node["@url-type"]!"content", .node, "true").getUrlWithParams()}" class="hover-image" alt="${.node["@alt"]!"image"}"/></span></#if>
    </#if>
</#macro>
<#macro label>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign labelType = .node["@type"]!"span">
        <#assign labelDivId><@nodeId .node/></#assign>
        <#assign textMap = "">
        <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], "")!></#if>
        <#if textMap?has_content><#assign labelValue = ec.getResource().expand(.node["@text"], "", textMap)>
        <#else><#assign labelValue = ec.getResource().expand(.node["@text"], "")/></#if>
        <#if labelValue?trim?has_content || .node["@condition"]?has_content>
            <#if .node["@encode"]! != "false"><#assign labelValue = labelValue?html>
                <#if labelType != 'code' && labelType != 'pre'><#assign labelValue = labelValue?replace("\n", "<br>")></#if></#if>
<${labelType}<#if labelDivId?has_content> id="${labelDivId}"</#if> class="text-inline<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if .node["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node["@tooltip"], "")}"</#if>>${labelValue}</${labelType}>
        </#if>
    </#if>
</#macro>
<#macro editable>
    <#-- for docs on JS usage see: http://www.appelsiini.net/projects/jeditable -->
    <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
    <#assign urlParms = urlInstance.getParameterMap()>
    <#assign editableDivId><@nodeId .node/></#assign>
    <#assign labelType = .node["@type"]?default("span")>
    <#assign labelValue = ec.getResource().expand(.node["@text"], "")>
    <#assign parameterName = .node["@parameter-name"]!"value">
    <#if labelValue?trim?has_content>
        <${labelType} id="${editableDivId}" class="editable-label"><#if .node["@encode"]! == "true">${labelValue?html?replace("\n", "<br>")}<#else>${labelValue}</#if></${labelType}>
        <script>
        $("#${editableDivId}").editable("${urlInstance.url}", { indicator:"${ec.getL10n().localize("Saving")}",
            tooltip:"${ec.getL10n().localize("Click to edit")}", cancel:"${ec.getL10n().localize("Cancel")}",
            submit:"${ec.getL10n().localize("Save")}", name:"${parameterName}",
            type:"${.node["@widget-type"]!"textarea"}", cssclass:"editable-form",
            submitdata:{<#list urlParms.keySet() as parameterKey>${parameterKey}:"${urlParms[parameterKey]}", </#list>parameterName:"${parameterName}", moquiSessionToken:"${(ec.getWeb().sessionToken)!}"}
            <#if .node["editable-load"]?has_content>
                <#assign loadNode = .node["editable-load"][0]>
                <#assign loadUrlInfo = sri.makeUrlByType(loadNode["@transition"], "transition", loadNode, "true")>
                <#assign loadUrlParms = loadUrlInfo.getParameterMap()>
            , loadurl:"${loadUrlInfo.url}", loadtype:"POST", loaddata:function(value, settings) { return {<#list loadUrlParms.keySet() as parameterKey>${parameterKey}:"${loadUrlParms[parameterKey]}", </#list>currentValue:value, moquiSessionToken:"${(ec.getWeb().sessionToken)!}"}; }
            </#if>});
        </script>
    </#if>
</#macro>
<#macro parameter><#-- do nothing, used directly in other elements --></#macro>

<#macro "button-menu">
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if !conditionResult><#return></#if>

    <#assign textMap = "">
    <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], "")!></#if>
    <#if textMap?has_content><#assign linkText = ec.getResource().expand(.node["@text"], "", textMap)>
        <#else><#assign linkText = ec.getResource().expand(.node["@text"]!"", "")></#if>

    <#if linkText == "null"><#assign linkText = ""></#if>
    <#if linkText?has_content || .node["image"]?has_content || .node["@icon"]?has_content>
        <#if .node["@encode"]! != "false"><#assign linkText = linkText?html></#if>
        <#assign iconClass = .node["@icon"]!>
        <#if !iconClass?has_content && linkText?has_content><#assign iconClass = sri.getThemeIconClass(linkText)!></#if>
        <#assign badgeMessage = ec.getResource().expand(.node["@badge"]!, "")/>

        <#-- TODO: tooltip, doesn't work when using data-toggle for dropdown! <#if .node["@tooltip"]?has_content>${ec.getResource().expand(.node["@tooltip"], "")}</#if> -->
        <div class="btn-group">
            <button type="button" class="btn btn-${.node["@btn-type"]!"primary"} dropdown-toggle<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
                <#if iconClass?has_content><i class="${iconClass}"></i></#if>
                <#if .node["image"]?has_content><#visit .node["image"][0]><#else>${linkText}</#if>
                <#if badgeMessage?has_content> <span class="badge">${badgeMessage}</span></#if>
                <span class="caret"></span>
            </button>
            <ul class="dropdown-menu" style="padding:8px;">
            <#list .node?children as childNode>
                <li>
                    <#visit childNode>
                </li>
            </#list>
            </ul>
        </div>
    </#if>
</#macro>

<#-- ============================================================= -->
<#-- ======================= Form Single ========================= -->
<#-- ============================================================= -->

<#macro "form-single">
    <#if sri.doBoundaryComments()><!-- BEGIN form-single[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formNode = formInstance.getFormNode()>
    <#t>${sri.pushSingleFormMapContext(formNode["@map"]!"fieldValues")}
    <#assign skipStart = formNode["@skip-start"]! == "true">
    <#assign skipEnd = formNode["@skip-end"]! == "true">
    <#assign ownerForm = formNode["@owner-form"]!>
    <#if ownerForm?has_content><#assign skipStart = true><#assign skipEnd = true></#if>
    <#assign urlInstance = sri.makeUrlByType(formNode["@transition"], "transition", null, "true")>
    <#assign formId>${ec.getResource().expandNoL10n(formNode["@name"], "")}<#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#assign>
    <#if !skipStart>
    <form name="${formId}" id="${formId}" method="post" action="${urlInstance.url}"<#if formInstance.isUpload()> enctype="multipart/form-data"</#if>>
        <input type="hidden" name="moquiFormName" value="${formNode["@name"]}">
        <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
        <#assign lastUpdatedString = sri.getNamedValuePlain("lastUpdatedStamp", formNode)>
        <#if lastUpdatedString?has_content><input type="hidden" name="lastUpdatedStamp" value="${lastUpdatedString}"></#if>
    </#if>
    <#if formNode["@pass-through-parameters"]! == "true">
        <#assign currentFindUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("moquiFormName").removeParameter("moquiSessionToken").removeParameter("lastStandalone").removeParameter("formListFindId")>
        <#assign currentFindUrlParms = currentFindUrl.getParameterMap()>
        <#list currentFindUrlParms.keySet() as parmName><#if !formInstance.getFieldNode(parmName)??>
            <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
        </#if></#list>
    </#if>
        <fieldset class="form-horizontal"<#if urlInstance.disableLink> disabled="disabled"</#if>>
        <#if formNode["field-layout"]?has_content>
            <#recurse formNode["field-layout"][0]/>
        <#else>
            <#list formNode["field"] as fieldNode><@formSingleSubField fieldNode formId/></#list>
        </#if>
        </fieldset>
    <#if !skipEnd></form></#if>
    <#if !skipStart>
        <script>
            $("#${formId}").validate({ errorClass: 'help-block', errorElement: 'span',
                highlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-success').addClass('has-error'); },
                unhighlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-error').addClass('has-success'); }
            });
            $('#${formId} [data-toggle="tooltip"]').tooltip({placement:'auto top'});

            <#-- if background-submit=true init ajaxForm; for examples see http://www.malsup.com/jquery/form/#ajaxForm -->
            <#if formNode["@background-submit"]! == "true">
            function backgroundSuccess${formId}(responseText, statusText, xhr, $form) {
                <#if formNode["@background-reload-id"]?has_content>
                    load${formNode["@background-reload-id"]}();
                </#if>
                <#if formNode["@background-message"]?has_content>
                    alert("${formNode["@background-message"]}");
                </#if>
                <#if formNode["@background-hide-id"]?has_content>
                    $('#${formNode["@background-hide-id"]}').modal('hide');
                </#if>
            }
            $("#${formId}").ajaxForm({ success: backgroundSuccess${formId}, resetForm: false });
            </#if>
        </script>
    </#if>
    <#if formNode["@focus-field"]?has_content>
        <script>$("#${formId}").find('[name^="${formNode["@focus-field"]}"]').addClass('default-focus').focus();</script>
    </#if>
    <#t>${sri.popContext()}<#-- context was pushed for the form-single so pop here at the end -->
    <#if sri.doBoundaryComments()><!-- END   form-single[@name=${.node["@name"]}] --></#if>
    <#assign ownerForm = ""><#-- clear ownerForm so later form fields don't pick it up -->
</#macro>
<#macro "field-ref">
    <#assign fieldRef = .node["@name"]>
    <#assign fieldNode = formInstance.getFieldNode(fieldRef)!>
    <#if fieldNode?has_content>
        <@formSingleSubField fieldNode formId/>
    <#else>
        <div>Error: could not find field with name ${fieldRef} referred to in a field-ref.@name attribute.</div>
    </#if>
</#macro>
<#macro "fields-not-referenced">
    <#assign nonReferencedFieldList = formInstance.getFieldLayoutNonReferencedFieldList()>
    <#list nonReferencedFieldList as nonReferencedField>
        <@formSingleSubField nonReferencedField formId/></#list>
</#macro>
<#macro "field-row">
    <#assign fsFieldRow = true>
    <div class="row">
        <#list .node?children as rowChildNode>
            <div class="col-sm-6">
                <#visit rowChildNode/>
            </div><!-- /col-sm-6 not bigRow -->
        </#list>
    </div><#-- /row -->
    <#assign fsFieldRow = false>
</#macro>
<#macro "field-row-big">
    <#-- funny assign here to not render row if there is no content -->
    <#assign fsFieldRow = true><#assign fsBigRow = true>
    <#assign rowContent>
        <#recurse .node/>
    </#assign>
    <#assign rowContent = rowContent?trim>
    <#assign fsFieldRow = false><#assign fsBigRow = false>
    <#if rowContent?has_content>
    <div class="form-group">
    <#if .node["@title"]?has_content>
        <label class="control-label col-sm-2">${ec.getResource().expand(.node["@title"], "")}</label>
        <div class="col-sm-10">
    <#else>
        <div class="col-sm-12">
    </#if>
            ${rowContent}
        </div><#-- /col-sm-12 bigRow -->
    </div><#-- /row -->
    </#if>
</#macro>
<#macro "field-group">
    <#assign fgTitle = ec.getL10n().localize(.node["@title"]!)!>
    <#if isAccordion!false>
        <#assign accIsActive = accordionIndex?string == accordionActive>
        <div class="panel panel-default">
            <div class="panel-heading" role="tab" id="${accordionId}_heading${accordionIndex}"><h5 class="panel-title">
                <a <#if !accIsActive>class="collapsed" </#if>role="button" data-toggle="collapse" data-parent="#${accordionId}" href="#${accordionId}_collapse${accordionIndex}" aria-expanded="true" aria-controls="${accordionId}_collapse${accordionIndex}">${fgTitle!"Fields"}</a>
            </h5></div>
            <div id="${accordionId}_collapse${accordionIndex}" class="panel-collapse collapse<#if accIsActive> in</#if>" role="tabpanel" aria-labelledby="${accordionId}_heading${accordionIndex}">
                <div class="panel-body<#if .node["@style"]?has_content> ${.node["@style"]}</#if>">
                    <#recurse .node/>
                </div>
            </div>
        </div>
        <#assign accordionIndex = accordionIndex + 1>
    <#elseif .node["@box"]! == "true">
        <div class="panel panel-default">
            <div class="panel-heading" role="tab"><h5 class="panel-title">${fgTitle!"Fields"}</h5></div>
            <div class="panel-collapse collapse in" role="tabpanel">
                <div class="panel-body<#if .node["@style"]?has_content> ${.node["@style"]}</#if>">
                    <#recurse .node/>
                </div>
            </div>
        </div>
    <#else>
        <div class="form-single-field-group<#if .node["@style"]?has_content> ${.node["@style"]}</#if>">
            <#if fgTitle?has_content><h5>${fgTitle}</h5></#if>
            <#recurse .node/>
        </div>
    </#if>
</#macro>
<#macro "field-accordion">
    <#assign isAccordion = true>
    <#assign accordionIndex = 1>
    <#assign accordionId = .node["@id"]!(formId + "_accordion")>
    <#assign accordionActive = .node["@active"]!"1">
    <div class="panel-group" id="${accordionId}" role="tablist" aria-multiselectable="true">
        <#recurse .node/>
    </div><!-- accordionId ${accordionId} -->
    <#assign isAccordion = false>
</#macro>
<#macro "field-col-row">
    <div class="row<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>">
        <#list .node["field-col"] as rowColNode>
            <div class="<#if rowColNode["@lg"]?has_content> col-lg-${rowColNode["@lg"]}</#if><#if rowColNode["@md"]?has_content> col-md-${rowColNode["@md"]}</#if><#if rowColNode["@sm"]?has_content> col-sm-${rowColNode["@sm"]}</#if><#if rowColNode["@xs"]?has_content> col-xs-${rowColNode["@xs"]}</#if><#if rowColNode["@style"]?has_content> ${ec.getResource().expandNoL10n(rowColNode["@style"], "")}</#if>">
                <#if rowColNode["@label-cols"]?has_content><#assign labelCols = rowColNode["@label-cols"]></#if>
                <#recurse rowColNode>
                <#assign labelCols = "">
            </div>
        </#list>
    </div>
</#macro>

<#macro formSingleSubField fieldNode formId>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.getResource().condition(fieldSubNode["@condition"], "")>
            <@formSingleWidget fieldSubNode formId "col-sm" fsFieldRow!false fsBigRow!false/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formSingleWidget fieldNode["default-field"][0] formId "col-sm" fsFieldRow!false fsBigRow!false/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode formId colPrefix inFieldRow bigRow>
    <#assign fieldSubParent = fieldSubNode?parent>
    <#if fieldSubNode["ignored"]?has_content><#return></#if>
    <#if ec.getResource().condition(fieldSubParent["@hide"]!, "")><#return></#if>
    <#if fieldSubNode["hidden"]?has_content><#recurse fieldSubNode/><#return></#if>
    <#assign containerStyle = ec.getResource().expandNoL10n(fieldSubNode["@container-style"]!, "")>
    <#assign curFieldTitle><@fieldTitle fieldSubNode/></#assign>
    <#if bigRow>
        <div class="big-row-item">
            <div class="form-group">
                <#if curFieldTitle?has_content && !fieldSubNode["submit"]?has_content>
                    <label class="control-label" for="${formId}_${fieldSubParent["@name"]}">${curFieldTitle}</label><#-- was form-title -->
                </#if>
    <#else>
        <#if labelCols?has_content>
            <#assign labelClass = colPrefix + "-" + labelCols>
            <#assign widgetClass = colPrefix + "-" + (12 - labelCols?number)?c>
        <#else>
            <#assign labelClass><#if inFieldRow>${colPrefix}-4<#else>${colPrefix}-2</#if></#assign>
            <#assign widgetClass><#if inFieldRow>${colPrefix}-8<#else>${colPrefix}-10</#if></#assign>
        </#if>
        <#if fieldSubNode["submit"]?has_content>
        <div class="form-group">
            <div class="${labelClass} hidden-xs">&nbsp;</div>
            <div class="${widgetClass}<#if containerStyle?has_content> ${containerStyle}</#if>">
        <#elseif curFieldTitle?has_content>
        <div class="form-group">
            <label class="control-label ${labelClass}" for="${formId}_${fieldSubParent["@name"]}">${curFieldTitle}</label><#-- was form-title -->
            <div class="${widgetClass}<#if containerStyle?has_content> ${containerStyle}</#if>">
        </#if>
    </#if>
    <#-- NOTE: this style is only good for 2 fields in a field-row! in field-row cols are double size because are inside a ${colPrefix}-6 element -->
    <#t>${sri.pushContext()}
    <#assign fieldFormId = formId><#-- set this globally so fieldId macro picks up the proper formId, clear after -->
    <#list fieldSubNode?children as widgetNode><#if widgetNode?node_name == "set">${sri.setInContext(widgetNode)}</#if></#list>
    <#list fieldSubNode?children as widgetNode>
        <#if widgetNode?node_name == "link">
            <#assign linkNode = widgetNode>
            <#if linkNode["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(linkNode["@condition"], "")><#else><#assign conditionResult = true></#if>
            <#if conditionResult>
                <#if linkNode["@entity-name"]?has_content>
                    <#assign linkText = ""><#assign linkText = sri.getFieldEntityValue(linkNode)>
                <#else>
                    <#assign textMap = "">
                    <#if linkNode["@text-map"]?has_content><#assign textMap = ec.getResource().expression(linkNode["@text-map"], "")!></#if>
                    <#if textMap?has_content><#assign linkText = ec.getResource().expand(linkNode["@text"], "", textMap)>
                        <#else><#assign linkText = ec.getResource().expand(linkNode["@text"]!"", "")></#if>
                </#if>
                <#if linkText == "null"><#assign linkText = ""></#if>
                <#if linkText?has_content || linkNode["image"]?has_content || linkNode["@icon"]?has_content>
                    <#if linkNode["@encode"]! != "false"><#assign linkText = linkText?html></#if>
                    <#assign linkUrlInfo = sri.makeUrlByType(linkNode["@url"], linkNode["@url-type"]!"transition", linkNode, linkNode["@expand-transition-url"]!"true")>
                    <#assign linkFormId><@fieldId linkNode/></#assign>
                    <#assign afterFormText><@linkFormForm linkNode linkFormId linkText linkUrlInfo/></#assign>
                    <#t>${sri.appendToAfterScreenWriter(afterFormText)}
                    <#t><@linkFormLink linkNode linkFormId linkText linkUrlInfo/>
                </#if>
            </#if>
        <#elseif widgetNode?node_name == "set"><#-- do nothing, handled above -->
        <#else><#t><#visit widgetNode>
        </#if>
    </#list>
    <#assign fieldFormId = ""><#-- clear after field so nothing else picks it up -->
    <#t>${sri.popContext()}
    <#if bigRow>
        <#-- <#if curFieldTitle?has_content></#if> -->
            </div><!-- /form-group -->
        </div><!-- /field-row-item -->
    <#else>
        <#if fieldSubNode["submit"]?has_content>
            </div><!-- /col -->
        </div><!-- /form-group -->
        <#elseif curFieldTitle?has_content>
            </div><!-- /col -->
        </div><!-- /form-group -->
        </#if>
    </#if>
</#macro>

<#-- =========================================================== -->
<#-- ======================= Form List ========================= -->
<#-- =========================================================== -->

<#macro paginationHeaderModals formListInfo formId isHeaderDialog>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign allColInfoList = formListInfo.getAllColInfo()>
    <#assign isSavedFinds = formNode["@saved-finds"]! == "true">
    <#assign isSelectColumns = formNode["@select-columns"]! == "true">
    <#assign currentFindUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageIndex").removeParameter("moquiFormName").removeParameter("moquiSessionToken").removeParameter("formListFindId")>
    <#assign currentFindUrlParms = currentFindUrl.getParameterMap()>
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
    <#if isSavedFinds || isHeaderDialog>
        <#assign headerFormDialogId = formId + "_hdialog">
        <#assign headerFormId = formId + "_header">
        <#assign headerFormButtonText = ec.getL10n().localize("Find Options")>
        <div id="${headerFormDialogId}" class="modal" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: 800px;"><div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                    <h4 class="modal-title">${headerFormButtonText}</h4>
                </div>
                <div class="modal-body">
                <#-- Saved Finds -->
                <#if isSavedFinds && isHeaderDialog><h4 style="margin-top: 0;">${ec.getL10n().localize("Saved Finds")}</h4></#if>
                <#if isSavedFinds>
                    <#assign activeFormListFind = formListInfo.getFormInstance().getActiveFormListFind(ec)!>
                    <#assign formSaveFindUrl = sri.buildUrl("formSaveFind").url>
                    <#assign descLabel = ec.getL10n().localize("Description")>
                    <#if activeFormListFind?has_content>
                        <h5>${ec.getL10n().localize("Active Saved Find:")} ${activeFormListFind.description?html}</h5>
                    </#if>
                    <#if currentFindUrlParms?has_content>
                        <div><form class="form-inline" id="${formId}_NewFind" method="post" action="${formSaveFindUrl}">
                            <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                            <input type="hidden" name="formLocation" value="${formListInfo.getSavedFindFullLocation()}">
                            <#list currentFindUrlParms.keySet() as parmName>
                                <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                            </#list>
                            <div class="form-group">
                                <label class="sr-only" for="${formId}_NewFind_description">${descLabel}</label>
                                <input type="text" class="form-control" size="40" name="_findDescription" id="${formId}_NewFind_description" placeholder="${descLabel}">
                            </div>
                            <button type="submit" class="btn btn-primary btn-sm">${ec.getL10n().localize("Save New Find")}</button>
                        </form></div>
                    <#else>
                        <p>${ec.getL10n().localize("No find parameters, choose some to save a new find or update existing")}</p>
                    </#if>
                    <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                    <#list userFindInfoList as userFindInfo>
                        <#assign formListFind = userFindInfo.formListFind>
                        <#assign findParameters = userFindInfo.findParameters>
                        <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)>
                        <#assign saveFindFormId = formId + "_SaveFind" + userFindInfo_index>
                        <div>
                        <#if currentFindUrlParms?has_content>
                            <form class="form-inline" id="${saveFindFormId}" method="post" action="${formSaveFindUrl}">
                                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                                <input type="hidden" name="formLocation" value="${formListInfo.getSavedFindFullLocation()}">
                                <input type="hidden" name="formListFindId" value="${formListFind.formListFindId}">
                                <#list currentFindUrlParms.keySet() as parmName>
                                    <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                                </#list>
                                <div class="form-group">
                                    <label class="sr-only" for="${saveFindFormId}_description">${descLabel}</label>
                                    <input type="text" class="form-control" size="40" name="_findDescription" id="${saveFindFormId}_description" value="${formListFind.description?html}">
                                </div>
                                <button type="submit" name="UpdateFind" class="btn btn-primary btn-sm">${ec.getL10n().localize("Update to Current")}</button>
                                <#if userFindInfo.isByUserId == "true"><button type="submit" name="DeleteFind" class="btn btn-danger btn-sm" onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');">&times;</button></#if>
                            </form>
                            <a href="${doFindUrl.urlWithParams}" class="btn btn-success btn-sm">${ec.getL10n().localize("Do Find")}</a>
                        <#else>
                            <a href="${doFindUrl.urlWithParams}" class="btn btn-success btn-sm">${ec.getL10n().localize("Do Find")}</a>
                            <#if userFindInfo.isByUserId == "true">
                            <form class="form-inline" id="${saveFindFormId}" method="post" action="${formSaveFindUrl}">
                                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                                <input type="hidden" name="formListFindId" value="${formListFind.formListFindId}">
                                <button type="submit" name="DeleteFind" class="btn btn-danger btn-sm" onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');">&times;</button>
                            </form>
                            </#if>
                            <strong>${formListFind.description?html}</strong>
                        </#if>
                        </div>
                    </#list>
                </#if>
                <#if isSavedFinds && isHeaderDialog><h4>${ec.getL10n().localize("Find Parameters")}</h4></#if>
                <#if isHeaderDialog>
                    <#-- Find Parameters Form -->
                    <#assign curUrlInstance = sri.getCurrentScreenUrl()>
                    <form name="${headerFormId}" id="${headerFormId}" method="post" action="${curUrlInstance.url}">
                        <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                        <#if formListFindId?has_content><input type="hidden" name="formListFindId" value="${formListFindId}"></#if>
                        <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                        <fieldset class="form-horizontal">
                            <div class="form-group"><div class="col-sm-2">&nbsp;</div><div class="col-sm-10">
                                <button type="button" class="btn btn-primary btn-sm" onclick="${headerFormId}_clearForm()">${ec.getL10n().localize("Clear Parameters")}</button></div></div>

                            <#-- Always add an orderByField to select one or more columns to order by -->
                            <div class="form-group">
                                <label class="control-label col-sm-2" for="${headerFormId}_orderByField">${ec.getL10n().localize("Order By")}</label>
                                <div class="col-sm-10">
                                    <select name="orderBySelect" id="${headerFormId}_orderBySelect" multiple="multiple" style="width: 100%;" class="noResetSelect2">
                                        <#list formNode["field"] as fieldNode><#if fieldNode["header-field"]?has_content>
                                            <#assign headerFieldNode = fieldNode["header-field"][0]>
                                            <#assign showOrderBy = (headerFieldNode["@show-order-by"])!>
                                            <#if showOrderBy?has_content && showOrderBy != "false">
                                                <#assign caseInsensitive = showOrderBy == "case-insensitive">
                                                <#assign orderFieldName = fieldNode["@name"]>
                                                <#assign orderFieldTitle><@fieldTitle headerFieldNode/></#assign>
                                                <option value="${caseInsensitive?string("^", "") + orderFieldName}">${orderFieldTitle} ${ec.getL10n().localize("(Asc)")}</option>
                                                <option value="${"-" + caseInsensitive?string("^", "") + orderFieldName}">${orderFieldTitle} ${ec.getL10n().localize("(Desc)")}</option>
                                            </#if>
                                        </#if></#list>
                                    </select>
                                    <input type="hidden" id="${headerFormId}_orderByField" name="orderByField" value="${orderByField!""}">
                                </div>
                            </div>
                            <script>
                                function ${headerFormId}_clearForm() {
                                    var jqEl = $("#${headerFormId}");
                                    jqEl.find(':radio, :checkbox').removeAttr('checked');
                                    jqEl.find('textarea, :text, select').val('').trigger('change');
                                    return false;
                                }
                                $("#${headerFormId}_orderBySelect").selectivity({ positionDropdown: function(dropdownEl, selectEl) { dropdownEl.css("width", "300px"); } })[0].selectivity.filterResults = function(results) {
                                    // Filter out asc and desc options if anyone selected.
                                    return results.filter(function(item){return !this._data.some(function(data_item) {return data_item.id.substring(1) === item.id.substring(1);});}, this);
                                };
                                    <#assign orderByJsValue = formListInfo.getOrderByActualJsString(ec.getContext().orderByField)>
                                    <#if orderByJsValue?has_content>$("#${headerFormId}_orderBySelect").selectivity("value", ${orderByJsValue});</#if>
                                $("div#${headerFormId}_orderBySelect").on("change", function(evt) {
                                    if (evt.value) $("#${headerFormId}_orderByField").val(evt.value.join(","));
                                });
                            </script>
                            <#list formNode["field"] as fieldNode><#if fieldNode["header-field"]?has_content && fieldNode["header-field"][0]?children?has_content>
                                <#assign headerFieldNode = fieldNode["header-field"][0]>
                                <#assign defaultFieldNode = (fieldNode["default-field"][0])!>
                                <#assign allHidden = true>
                                <#list fieldNode?children as fieldSubNode>
                                    <#if !(fieldSubNode["hidden"]?has_content || fieldSubNode["ignored"]?has_content)><#assign allHidden = false></#if>
                                </#list>

                                <#if !(ec.getResource().condition(fieldNode["@hide"]!, "") || allHidden ||
                                        ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                                        ((fieldNode["header-field"][0]["hidden"])?has_content || (fieldNode["header-field"][0]["ignored"])?has_content)))>
                                    <@formSingleWidget headerFieldNode headerFormId "col-sm" false false/>
                                <#elseif (headerFieldNode["hidden"])?has_content>
                                    <#recurse headerFieldNode/>
                                </#if>
                            </#if></#list>
                        </fieldset>
                    </form>
                </#if>
                </div>
            </div></div>
        </div>
        <script>$('#${headerFormDialogId}').on('shown.bs.modal', function() {
            $("#${headerFormDialogId} :not(.noResetSelect2)>select:not(.noResetSelect2)").select2({ });
        })</script>
    </#if>
    <#if isSelectColumns>
        <#assign selectColumnsDialogId = formId + "_SelColsDialog">
        <#assign selectColumnsSortableId = formId + "_SelColsSortable">
        <#assign fieldsNotInColumns = formListInfo.getFieldsNotReferencedInFormListColumn()>
        <div id="${selectColumnsDialogId}" class="modal" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: 600px;"><div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                    <h4 class="modal-title">${ec.getL10n().localize("Column Fields")}</h4>
                </div>
                <div class="modal-body">
                    <p>${ec.getL10n().localize("Drag fields to the desired column or do not display")}</p>
                    <ul id="${selectColumnsSortableId}">
                        <li id="hidden"><div>${ec.getL10n().localize("Do Not Display")}</div>
                            <#if fieldsNotInColumns?has_content>
                            <ul>
                            <#list fieldsNotInColumns as fieldNode>
                                <#assign fieldSubNode = (fieldNode["header-field"][0])!(fieldNode["default-field"][0])!>
                                <li id="${fieldNode["@name"]}"><div><@fieldTitle fieldSubNode/></div></li>
                            </#list>
                            </ul>
                            </#if>
                        </li>
                        <#list allColInfoList as columnFieldList>
                            <li id="column_${columnFieldList_index}"><div>${ec.getL10n().localize("Column")} ${columnFieldList_index + 1}</div><ul>
                            <#list columnFieldList as fieldNode>
                                <#assign fieldSubNode = (fieldNode["header-field"][0])!(fieldNode["default-field"][0])!>
                                <li id="${fieldNode["@name"]}"><div><@fieldTitle fieldSubNode/></div></li>
                            </#list>
                            </ul></li>
                        </#list>
                        <#list allColInfoList?size..(allColInfoList?size + 2) as ind><#-- always add 3 more columns for flexibility -->
                            <li id="column_${ind}"><div>${ec.getL10n().localize("Column")} ${ind + 1}</div></li>
                        </#list>
                    </ul>
                    <form class="form-inline" id="${formId}_SelColsForm" method="post" action="${sri.buildUrl("formSelectColumns").url}">
                        <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                        <input type="hidden" name="formLocation" value="${formListInfo.getFormLocation()}">
                        <input type="hidden" id="${formId}_SelColsForm_columnsTree" name="columnsTree" value="">
                        <#if currentFindUrlParms?has_content><#list currentFindUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                        </#list></#if>
                        <input type="submit" name="SaveColumns" value="${ec.getL10n().localize("Save Columns")}" class="btn btn-primary btn-sm"/>
                        <input type="submit" name="ResetColumns" value="${ec.getL10n().localize("Reset to Default")}" class="btn btn-primary btn-sm"/>
                    </form>
                </div>
            </div></div>
        </div>
        <script>$('#${selectColumnsDialogId}').on('shown.bs.modal', function() {
            $("#${selectColumnsSortableId}").sortableLists({
                isAllowed: function(currEl, hint, target) {
                    <#-- don't allow hidden and column items to be moved; only allow others to be under hidden or column items -->
                    if (currEl.attr('id') === 'hidden' || currEl.attr('id').startsWith('column_')) {
                        if (!target.attr('id') || (target.attr('id') != 'hidden' && !currEl.attr('id').startsWith('column_'))) { hint.css('background-color', '#99ff99'); return true; }
                        else { hint.css('background-color', '#ff9999'); return false; }
                    }
                    if (target.attr('id') && (target.attr('id') === 'hidden' || target.attr('id').startsWith('column_'))) { hint.css('background-color', '#99ff99'); return true; }
                    else { hint.css('background-color', '#ff9999'); return false; }
                },
                placeholderCss: {'background-color':'#999999'}, insertZone: 50,
                <#-- jquery-sortable-lists currently logs an error if opener.as is not set to html or class -->
                opener: { active:false, as:'html', close:'', open:'' },
                onChange: function(cEl) {
                    var sortableHierarchy = $('#${selectColumnsSortableId}').sortableListsToHierarchy();
                    // console.log(sortableHierarchy); console.log(JSON.stringify(sortableHierarchy));
                    $("#${formId}_SelColsForm_columnsTree").val(JSON.stringify(sortableHierarchy));
                }
            });
            $("#${formId}_SelColsForm_columnsTree").val(JSON.stringify($('#${selectColumnsSortableId}').sortableListsToHierarchy()));
        })</script>
    </#if>
    <#if formNode["@show-text-button"]! == "true">
        <#assign showTextDialogId = formId + "_TextDialog">
        <#assign textLinkUrl = sri.getScreenUrlInstance()>
        <#assign textLinkUrlParms = textLinkUrl.getParameterMap()>
        <div id="${showTextDialogId}" class="modal" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: 600px;"><div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                    <h4 class="modal-title">${ec.getL10n().localize("Export Fixed-Width Plain Text")}</h4>
                </div>
                <div class="modal-body">
                    <form id="${formId}_Text" method="post" action="${textLinkUrl.getUrl()}">
                        <input type="hidden" name="renderMode" value="text">
                        <input type="hidden" name="pageNoLimit" value="true">
                        <input type="hidden" name="lastStandalone" value="true">
                        <#list textLinkUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${textLinkUrlParms.get(parmName)!?html}"></#list>
                        <fieldset class="form-horizontal">
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Text_lineCharacters">${ec.getL10n().localize("Line Characters")}</label>
                                <div class="col-sm-9">
                                    <input type="text" class="form-control" size="4" name="lineCharacters" id="${formId}_Text_lineCharacters" value="132">
                                </div>
                            </div>
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Text_pageLines">${ec.getL10n().localize("Page Lines")}</label>
                                <div class="col-sm-9">
                                    <input type="text" class="form-control" size="4" name="pageLines" id="${formId}_Text_pageLines" value="88">
                                </div>
                            </div>
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Text_lineWrap">${ec.getL10n().localize("Line Wrap?")}</label>
                                <div class="col-sm-9">
                                    <input type="checkbox" class="form-control" name="lineWrap" id="${formId}_Text_lineWrap" value="true">
                                </div>
                            </div>
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Text_saveFilename">${ec.getL10n().localize("Save to Filename")}</label>
                                <div class="col-sm-9">
                                    <input type="text" class="form-control" size="40" name="saveFilename" id="${formId}_Text_saveFilename" value="${formNode["@name"] + ".txt"}">
                                </div>
                            </div>
                            <button type="submit" class="btn btn-default">${ec.getL10n().localize("Export Text")}</button>
                        </fieldset>
                    </form>
                </div>
            </div></div>
        </div>
    </#if>
    <#if formNode["@show-pdf-button"]! == "true">
        <#assign showPdfDialogId = formId + "_PdfDialog">
        <#assign pdfLinkUrl = sri.getScreenUrlInstance()>
        <#assign pdfLinkUrlParms = pdfLinkUrl.getParameterMap()>
        <div id="${showPdfDialogId}" class="modal" aria-hidden="true" style="display: none;" tabindex="-1">
            <div class="modal-dialog" style="width: 600px;"><div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                    <h4 class="modal-title">${ec.getL10n().localize("Generate PDF")}</h4>
                </div>
                <div class="modal-body">
                    <form id="${formId}_Pdf" method="post" action="${ec.web.getWebappRootUrl(false, null)}/fop${pdfLinkUrl.getScreenPath()}">
                        <input type="hidden" name="pageNoLimit" value="true">
                        <#list pdfLinkUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${pdfLinkUrlParms.get(parmName)!?html}"></#list>
                        <fieldset class="form-horizontal">
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Pdf_layoutMaster">${ec.getL10n().localize("Page Layout")}</label>
                                <div class="col-sm-9">
                                    <select name="layoutMaster"  id="${formId}_Pdf_layoutMaster" class="form-control">
                                        <option value="letter-landscape">US Letter - Landscape (11x8.5)</option>
                                        <option value="letter-portrait">US Letter - Portrait (8.5x11)</option>
                                        <option value="legal-landscape">US Legal - Landscape (14x8.5)</option>
                                        <option value="legal-portrait">US Legal - Portrait (8.5x14)</option>
                                        <option value="tabloid-landscape">US Tabloid - Landscape (17x11)</option>
                                        <option value="tabloid-portrait">US Tabloid - Portrait (11x17)</option>
                                        <option value="a4-landscape">A4 - Landscape (297x210)</option>
                                        <option value="a4-portrait">A4 - Portrait (210x297)</option>
                                        <option value="a3-landscape">A3 - Landscape (420x297)</option>
                                        <option value="a3-portrait">A3 - Portrait (297x420)</option>
                                    </select>
                                </div>
                            </div>
                            <div class="form-group">
                                <label class="control-label col-sm-3" for="${formId}_Pdf_saveFilename">${ec.getL10n().localize("Save to Filename")}</label>
                                <div class="col-sm-9">
                                    <input type="text" class="form-control" size="40" name="saveFilename" id="${formId}_Pdf_saveFilename" value="${formNode["@name"] + ".pdf"}">
                                </div>
                            </div>
                            <button type="submit" class="btn btn-default">${ec.getL10n().localize("Generate PDF")}</button>
                        </fieldset>
                    </form>
                    <script>$("#${formId}_Pdf_layoutMaster").select2({ });</script>
                </div>
            </div></div>
        </div>
    </#if>
</#macro>
<#macro paginationHeader formListInfo formId isHeaderDialog>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign mainColInfoList = formListInfo.getMainColInfo()>
    <#assign numColumns = (mainColInfoList?size)!100>
    <#if numColumns == 0><#assign numColumns = 100></#if>
    <#assign isSavedFinds = formNode["@saved-finds"]! == "true">
    <#assign isSelectColumns = formNode["@select-columns"]! == "true">
    <#assign isPaginated = !(formNode["@paginate"]! == "false") && context[listName + "Count"]?? && (context[listName + "Count"]! > 0) &&
            (!formNode["@paginate-always-show"]?has_content || formNode["@paginate-always-show"]! == "true" || (context[listName + "PageMaxIndex"] > 0))>
    <#if (isHeaderDialog || isSavedFinds || isSelectColumns || isPaginated) && hideNav! != "true">
        <tr class="form-list-nav-row"><th colspan="${numColumns}">
        <nav class="form-list-nav">
            <#if isSavedFinds>
                <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                <#if userFindInfoList?has_content>
                    <#assign quickSavedFindId = formId + "_QuickSavedFind">
                    <select id="${quickSavedFindId}">
                        <option></option><#-- empty option for placeholder -->
                        <option value="_clear" data-action="${sri.getScreenUrlInstance().url}">${ec.getL10n().localize("Clear Current Find")}</option>
                        <#list userFindInfoList as userFindInfo>
                            <#assign formListFind = userFindInfo.formListFind>
                            <#assign findParameters = userFindInfo.findParameters>
                            <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)>
                            <option value="${formListFind.formListFindId}"<#if formListFind.formListFindId == ec.getContext().formListFindId!> selected="selected"</#if> data-action="${doFindUrl.urlWithParams}">${userFindInfo.description?html}</option>
                        </#list>
                    </select>
                    <script>
                        $("#${quickSavedFindId}").select2({ placeholder:'${ec.getL10n().localize("Saved Finds")}' });
                        $("#${quickSavedFindId}").on('select2:select', function(evt) {
                            var dataAction = $(evt.params.data.element).attr("data-action");
                            if (dataAction) window.open(dataAction, "_self");
                        } );
                    </script>
                </#if>
            </#if>
            <#if isSavedFinds || isHeaderDialog><button id="${headerFormDialogId}_button" type="button" data-toggle="modal" data-target="#${headerFormDialogId}" data-original-title="${headerFormButtonText}" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> ${headerFormButtonText}</button></#if>
            <#if isSelectColumns><button id="${selectColumnsDialogId}_button" type="button" data-toggle="modal" data-target="#${selectColumnsDialogId}" data-original-title="${ec.getL10n().localize("Columns")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> ${ec.getL10n().localize("Columns")}</button></#if>

            <#if isPaginated>
                <#assign curPageIndex = context[listName + "PageIndex"]>
                <#assign curPageMaxIndex = context[listName + "PageMaxIndex"]>
                <#assign prevPageIndexMin = curPageIndex - 3><#if (prevPageIndexMin < 0)><#assign prevPageIndexMin = 0></#if>
                <#assign prevPageIndexMax = curPageIndex - 1><#assign nextPageIndexMin = curPageIndex + 1>
                <#assign nextPageIndexMax = curPageIndex + 3><#if (nextPageIndexMax > curPageMaxIndex)><#assign nextPageIndexMax = curPageMaxIndex></#if>
                <ul class="pagination">
                <#if (curPageIndex > 0)>
                    <#assign firstUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", 0)>
                    <#assign previousUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", (curPageIndex - 1))>
                    <li><a href="${firstUrlInfo.getUrlWithParams()}"><i class="fa fa-fast-backward"></i></a></li>
                    <li><a href="${previousUrlInfo.getUrlWithParams()}"><i class="fa fa-backward"></i></a></li>
                <#else>
                    <li><span><i class="fa fa-fast-backward"></i></span></li>
                    <li><span><i class="fa fa-backward"></i></span></li>
                </#if>

                <#if (prevPageIndexMax >= 0)><#list prevPageIndexMin..prevPageIndexMax as pageLinkIndex>
                    <#assign pageLinkUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", pageLinkIndex)>
                    <li><a href="${pageLinkUrlInfo.getUrlWithParams()}">${pageLinkIndex + 1}</a></li>
                </#list></#if>
                <#assign paginationTemplate = ec.getL10n().localize("PaginationTemplate")?interpret>
                <li><a href="${sri.getScreenUrlInstance().getUrlWithParams()}"><@paginationTemplate /></a></li>

                <#if (nextPageIndexMin <= curPageMaxIndex)><#list nextPageIndexMin..nextPageIndexMax as pageLinkIndex>
                    <#assign pageLinkUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", pageLinkIndex)>
                    <li><a href="${pageLinkUrlInfo.getUrlWithParams()}">${pageLinkIndex + 1}</a></li>
                </#list></#if>

                <#if (curPageIndex < curPageMaxIndex)>
                    <#assign lastUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", curPageMaxIndex)>
                    <#assign nextUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageIndex", curPageIndex + 1)>
                    <li><a href="${nextUrlInfo.getUrlWithParams()}"><i class="fa fa-forward"></i></a></li>
                    <li><a href="${lastUrlInfo.getUrlWithParams()}"><i class="fa fa-fast-forward"></i></a></li>
                <#else>
                    <li><span><i class="fa fa-forward"></i></span></li>
                    <li><span><i class="fa fa-fast-forward"></i></span></li>
                </#if>
                </ul>
                <#if (curPageMaxIndex > 4)>
                    <#assign goPageUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageIndex").removeParameter("moquiFormName").removeParameter("moquiSessionToken")>
                    <#assign goPageUrlParms = goPageUrl.getParameterMap()>
                    <form class="form-inline" id="${formId}_GoPage" method="post" action="${goPageUrl.getUrl()}">
                        <#list goPageUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${goPageUrlParms.get(parmName)!?html}"></#list>
                        <div class="form-group">
                            <label class="sr-only" for="${formId}_GoPage_pageIndex">Page Number</label>
                            <input type="text" class="form-control" size="6" name="pageIndex" id="${formId}_GoPage_pageIndex" placeholder="${ec.getL10n().localize("Page #")}">
                        </div>
                        <button type="submit" class="btn btn-default">${ec.getL10n().localize("Go##Page")}</button>
                    </form>
                    <script>
                        $("#${formId}_GoPage").validate({ errorClass: 'help-block', errorElement: 'span',
                            rules: { pageIndex: { required:true, min:1, max:${(curPageMaxIndex + 1)?c} } },
                            highlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-success').addClass('has-error'); },
                            unhighlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-error').addClass('has-success'); },
                            <#-- show 1-based index to user but server expects 0-based index -->
                            submitHandler: function(form) { $("#${formId}_GoPage_pageIndex").val($("#${formId}_GoPage_pageIndex").val() - 1); form.submit(); }
                        });
                    </script>
                </#if>
                <#if formNode["@show-all-button"]! == "true" && (context[listName + 'Count'] < 500)>
                    <#if context["pageNoLimit"]?has_content>
                        <#assign allLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageNoLimit")>
                        <a href="${allLinkUrl.getUrlWithParams()}" class="btn btn-default">${ec.getL10n().localize("Paginate")}</a>
                    <#else>
                        <#assign allLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageNoLimit", "true")>
                        <a href="${allLinkUrl.getUrlWithParams()}" class="btn btn-default">${ec.getL10n().localize("Show All")}</a>
                    </#if>
                </#if>
            </#if>

            <#if formNode["@show-csv-button"]! == "true">
                <#assign csvLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("renderMode", "csv")
                        .addParameter("pageNoLimit", "true").addParameter("lastStandalone", "true").addParameter("saveFilename", formNode["@name"] + ".csv")>
                <a href="${csvLinkUrl.getUrlWithParams()}" class="btn btn-default">${ec.getL10n().localize("CSV")}</a>
            </#if>
            <#if formNode["@show-xlsx-button"]! == "true" && ec.screen.isRenderModeValid("xlsx")>
                <#assign xlsxLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("renderMode", "xlsx")
                        .addParameter("pageNoLimit", "true").addParameter("lastStandalone", "true").addParameter("saveFilename", formNode["@name"] + ".xlsx")>
                <a href="${xlsxLinkUrl.getUrlWithParams()}" class="btn btn-default">${ec.getL10n().localize("XLS")}</a>
            </#if>
            <#if formNode["@show-text-button"]! == "true">
                <#assign showTextDialogId = formId + "_TextDialog">
                <button id="${showTextDialogId}_button" type="button" data-toggle="modal" data-target="#${showTextDialogId}" data-original-title="${ec.getL10n().localize("Text")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> ${ec.getL10n().localize("Text")}</button>
            </#if>
            <#if formNode["@show-pdf-button"]! == "true">
                <#assign showPdfDialogId = formId + "_PdfDialog">
                <button id="${showPdfDialogId}_button" type="button" data-toggle="modal" data-target="#${showPdfDialogId}" data-original-title="${ec.getL10n().localize("PDF")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> ${ec.getL10n().localize("PDF")}</button>
            </#if>
        </nav>
        </th></tr>

        <#if isHeaderDialog>
            <tr><th colspan="${numColumns}" style="font-weight: normal">
                <#assign haveFilters = false>
                <#list formNode["field"] as fieldNode><#if fieldNode["header-field"]?has_content && fieldNode["header-field"][0]?children?has_content>
                    <#assign headerFieldNode = fieldNode["header-field"][0]>
                    <#assign allHidden = true>
                    <#list fieldNode?children as fieldSubNode>
                        <#if !(fieldSubNode["hidden"]?has_content || fieldSubNode["ignored"]?has_content)><#assign allHidden = false></#if>
                    </#list>
                    <#if !(ec.getResource().condition(fieldNode["@hide"]!, "") || allHidden ||
                            ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                            (headerFieldNode["hidden"]?has_content || headerFieldNode["ignored"]?has_content)))>
                        <#t>${sri.pushContext()}
                        <#list headerFieldNode?children as widgetNode><#if widgetNode?node_name == "set">${sri.setInContext(widgetNode)}</#if></#list>
                        <#list headerFieldNode?children as widgetNode><#if widgetNode?node_name != "set">
                            <#assign fieldValue><@widgetTextValue widgetNode/></#assign>
                            <#if fieldValue?has_content>
                                <span style="white-space:nowrap;"><strong><@fieldTitle headerFieldNode/>:</strong> <span class="text-success">${fieldValue}</span></span>
                                <#assign haveFilters = true>
                            </#if>
                        </#if></#list>
                        <#t>${sri.popContext()}
                    </#if>
                </#if></#list>
                <#if haveFilters>
                    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
                    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
                    <#assign curUrlInstance = sri.getCurrentScreenUrl()>
                    <form name="${headerFormId}" id="${headerFormId}" method="post" action="${curUrlInstance.url}">
                        <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                        <button id="${headerFormId}-quick-clear" type="submit" name="clearParameters" style="float:left; padding: 0 5px 0 5px; margin: 0 4px 0 0;" class="btn btn-primary btn-sm"><i class="fa fa-remove"></i></button>
                    </form>
                </#if>
            </th></tr>
            </#if>
    </#if>
</#macro>

<#macro "form-list">
    <#if sri.doBoundaryComments()><!-- BEGIN form-list[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formListInfo = formInstance.makeFormListRenderInfo()>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign mainColInfoList = formListInfo.getMainColInfo()>
    <#assign subColInfoList = formListInfo.getSubColInfo()!>
    <#assign hasSubColumns = subColInfoList?has_content>
    <#assign tableStyle><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if></#assign>
    <#assign numColumns = (mainColInfoList?size)!100>
    <#if numColumns == 0><#assign numColumns = 100></#if>
    <#assign formId>${ec.getResource().expandNoL10n(formNode["@name"], "")}<#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#assign>
    <#assign headerFormId = formId + "_header">
    <#assign skipStart = (formNode["@skip-start"]! == "true")>
    <#assign skipEnd = (formNode["@skip-end"]! == "true")>
    <#assign skipForm = (formNode["@skip-form"]! == "true")>
    <#assign skipHeader = !skipStart && (formNode["@skip-header"]! == "true")>
    <#assign needHeaderForm = !skipHeader && formListInfo.isHeaderForm()>
    <#assign isHeaderDialog = needHeaderForm && formNode["@header-dialog"]! == "true">
    <#assign isMulti = !skipForm && formNode["@multi"]! == "true">
    <#assign formListUrlInfo = sri.makeUrlByType(formNode["@transition"], "transition", null, "false")>
    <#assign listName = formNode["@list"]>
    <#assign listObject = formListInfo.getListObject(true)!>
    <#assign listHasContent = listObject?has_content>
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>

    <#-- all form elements outside table element and referred to with input/etc.@form attribute for proper HTML -->
    <#if !(isMulti || skipForm) && listHasContent><#list listObject as listEntry>
        ${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
        <form name="${formId}_${listEntry_index}" id="${formId}_${listEntry_index}" method="post" action="${formListUrlInfo.url}">
            <#assign listEntryIndex = listEntry_index>
            <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#-- hidden fields -->
            <#assign hiddenFieldList = formListInfo.getListHiddenFieldList()>
            <#list hiddenFieldList as hiddenField><@formListSubField hiddenField true false isMulti false/></#list>
            <#assign listEntryIndex = "">
        </form>
        ${sri.endFormListRow()}
    </#list></#if>
    <#if !skipStart>
        <#if needHeaderForm && !isHeaderDialog>
            <#assign headerUrlInstance = sri.getCurrentScreenUrl()>
            <form name="${headerFormId}" id="${headerFormId}" method="post" action="${headerUrlInstance.url}">
                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListHeaderHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["header-field"][0]/></#list>
            </form>
        </#if>
        <#if formListInfo.isFirstRowForm()>
            <#t>${sri.pushSingleFormMapContext(formNode["@map-first-row"]!"")}
            <#assign listEntryIndex = "first">
            <#assign firstUrlInstance = sri.makeUrlByType(formNode["@transition-first-row"], "transition", null, "false")>
            <form name="${formId}_first" id="${formId}_first" method="post" action="${firstUrlInstance.url}">
                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListFirstRowHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["first-row-field"][0]/></#list>
            </form>
            <#assign listEntryIndex = "">
            <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if formListInfo.isSecondRowForm()>
          <#t>${sri.pushSingleFormMapContext(formNode["@map-second-row"]!"")}
          <#assign listEntryIndex = "last">
          <#assign lastUrlInstance = sri.makeUrlByType(formNode["@transition-second-row"], "transition", null, "false")>
          <form name="${formId}_last" id="${formId}_last" method="post" action="${lastUrlInstance.url}">
              <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
              <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
              <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
              <#assign hiddenFieldList = formListInfo.getListSecondRowHiddenFieldList()>
              <#list hiddenFieldList as hiddenField><#recurse hiddenField["second-row-field"][0]/></#list>
          </form>
          <#assign listEntryIndex = "">
          <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if formListInfo.isLastRowForm()>
            <#t>${sri.pushSingleFormMapContext(formNode["@map-last-row"]!"")}
            <#assign listEntryIndex = "last">
            <#assign lastUrlInstance = sri.makeUrlByType(formNode["@transition-last-row"], "transition", null, "false")>
            <form name="${formId}_last" id="${formId}_last" method="post" action="${lastUrlInstance.url}">
                <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListLastRowHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["last-row-field"][0]/></#list>
            </form>
            <#assign listEntryIndex = "">
            <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if isMulti>
        <form name="${formId}" id="${formId}" method="post" action="${formListUrlInfo.url}">
            <input type="hidden" name="moquiFormName" value="${formNode["@name"]}">
            <input type="hidden" name="moquiSessionToken" value="${(ec.getWeb().sessionToken)!}">
            <input type="hidden" name="_isMulti" value="true">
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#if listHasContent><#list listObject as listEntry>
                <#assign listEntryIndex = listEntry_index>
                ${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
                <#-- hidden fields -->
                <#assign hiddenFieldList = formListInfo.getListHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><@formListSubField hiddenField true false isMulti false/></#list>
                ${sri.endFormListRow()}
                <#assign listEntryIndex = "">
            </#list></#if>
        </form>
        </#if>

        <#if !skipHeader><@paginationHeaderModals formListInfo formId isHeaderDialog/></#if>
        <div class="table-scroll-wrapper"><table class="table table-striped table-hover table-condensed${tableStyle}" id="${formId}_table">
        <#if !skipHeader>
            <thead>
                <@paginationHeader formListInfo formId isHeaderDialog/>

                <#assign ownerForm = headerFormId>
                <tr>
                <#list mainColInfoList as columnFieldList>
                    <#-- TODO: how to handle column style? <th<#if fieldListColumn["@style"]?has_content> class="${fieldListColumn["@style"]}"</#if>> -->
                    <th>
                    <#list columnFieldList as fieldNode>
                        <#-- <#if !(ec.getResource().condition(fieldNode["@hide"]!, "") ||
                                ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                                (fieldNode?children[0]["hidden"]?has_content || fieldNode?children[0]["ignored"]?has_content)))> -->
                        <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                    </#list>
                    </th>
                </#list>
                </tr>
                <#if hasSubColumns>
                    <tr><td colspan="${numColumns}" class="form-list-sub-row-cell"><div class="form-list-sub-rows"><table class="table table-striped table-hover table-condensed${tableStyle}"><thead>
                        <#list subColInfoList as subColFieldList><th>
                            <#list subColFieldList as fieldNode>
                                <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                            </#list>
                        </th></#list>
                    </thead></table></div></td></tr>
                </#if>
                <#if needHeaderForm>
                    <#if _dynamic_container_id?has_content>
                        <#-- if we have an _dynamic_container_id this was loaded in a dynamic-container so init ajaxForm; for examples see http://www.malsup.com/jquery/form/#ajaxForm -->
                        <script>$("#${headerFormId}").ajaxForm({ target: '#${_dynamic_container_id}', <#-- success: activateAllButtons, --> resetForm: false });</script>
                    </#if>
                </#if>
                <#assign ownerForm = "">
            </thead>
        </#if>
            <tbody>
            <#assign ownerForm = formId>
    </#if>
    <#-- first-row fields -->
    <#if formListInfo.hasFirstRow()>
        <#t>${sri.pushSingleFormMapContext(formNode["@map-first-row"]!"")}
        <#assign ownerForm = formId + "_first">
        <#assign listEntryIndex = "first">
        <tr class="first">
        <#list mainColInfoList as columnFieldList>
            <td>
                <#list columnFieldList as fieldNode>
                    <@formListSubFirst fieldNode true/>
                </#list>
            </td>
        </#list>
        </tr>
        <#assign ownerForm = formId>
        <#assign listEntryIndex = "">
        <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
    </#if>
    <#-- second-row fields -->
    <#if formListInfo.hasSecondRow()>
        <#t>${sri.pushSingleFormMapContext(formNode["@map-second-row"]!"")}
        <#assign ownerForm = formId + "_second">
        <#assign listEntryIndex = "second">
        <tr class="second">
        <#list mainColInfoList as columnFieldList>
            <td>
                <#list columnFieldList as fieldNode>
                    <@formListSubSecond fieldNode true/>
                </#list>
            </td>
        </#list>
        </tr>
        <#assign ownerForm = formId>
        <#assign listEntryIndex = "">
        <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
    </#if>
    <#if listHasContent><#list listObject as listEntry>
        <#assign listEntryIndex = listEntry_index>
        <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
        ${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
        <tr>
        <#if !(isMulti || skipForm)><#assign ownerForm = formId + "_" + listEntry_index></#if>
        <#-- actual columns -->
        <#list mainColInfoList as columnFieldList>
            <td>
            <#list columnFieldList as fieldNode>
                <@formListSubField fieldNode true false isMulti false/>
            </#list>
            </td>
        </#list>
        <#if hasSubColumns><#assign aggregateSubList = listEntry["aggregateSubList"]!><#if aggregateSubList?has_content>
            </tr>
            <tr><td colspan="${numColumns}" class="form-list-sub-row-cell"><div class="form-list-sub-rows"><table class="table table-striped table-hover table-condensed${tableStyle}">
                <#list aggregateSubList as subListEntry><tr>
                    ${sri.startFormListSubRow(formListInfo, subListEntry, subListEntry_index, subListEntry_has_next)}
                    <#list subColInfoList as subColFieldList><td>
                        <#list subColFieldList as fieldNode>
                            <@formListSubField fieldNode true false isMulti false/>
                        </#list>
                    </td></#list>
                    ${sri.endFormListSubRow()}
                </tr></#list>
            </table></div></td><#-- note no /tr, let following blocks handle it -->
        </#if></#if>
        </tr>
        <#if !(isMulti || skipForm)>
            <script>
                $("#${formId}_${listEntryIndex}").validate({ errorClass: 'help-block', errorElement: 'span',
                    highlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-success').addClass('has-error'); },
                    unhighlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-error').addClass('has-success'); }
                });
            </script>
            <#assign ownerForm = "">
        </#if>
        ${sri.endFormListRow()}
    </#list></#if>
    <#assign listEntryIndex = "">
    ${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
    <#-- last-row fields -->
    <#if formListInfo.hasLastRow()>
        <#t>${sri.pushSingleFormMapContext(formNode["@map-last-row"]!"")}
        <#assign ownerForm = formId + "_last">
        <#assign listEntryIndex = "last">
        <tr class="last">
            <#list mainColInfoList as columnFieldList>
                <td>
                    <#list columnFieldList as fieldNode>
                        <@formListSubLast fieldNode true/>
                    </#list>
                </td>
            </#list>
        </tr>
        <#assign listEntryIndex = "">
        <#assign ownerForm = formId>
        <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
    </#if>
    <#if !skipEnd>
        <#if isMulti && listHasContent>
            <tr><td colspan="${numColumns}">
                <#list formNode["field"] as fieldNode><@formListSubField fieldNode false false true true/></#list>
            </td></tr>
        </#if>
            </tbody>
            <#assign ownerForm = "">
        </table></div>
    </#if>
    <#if isMulti && !skipStart>
        <script>
            $("#${formId}").validate({ errorClass: 'help-block', errorElement: 'span',
                highlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-success').addClass('has-error'); },
                unhighlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-error').addClass('has-success'); }
            });
            $('#${formId} [data-toggle="tooltip"]').tooltip({placement:'auto top'});
        </script>
    </#if>
    <#if formNode["@focus-field"]?has_content>
        <script>$("#${formId}_table").find('[name="${formNode["@focus-field"]}<#if isMulti && !formListInfo.hasFirstRow()>_0</#if>"][form="${formId}<#if formListInfo.hasFirstRow()>_first<#elseif !isMulti>_0</#if>"]').addClass('default-focus').focus();</script>
    </#if>
    <#if hasSubColumns><script>moqui.makeColumnsConsistent('${formId}_table');</script></#if>
    <#if sri.doBoundaryComments()><!-- END   form-list[@name=${.node["@name"]}] --></#if>
    <#assign skipForm = false>
</#macro>
<#macro formListHeaderField fieldNode isHeaderDialog>
    <#if fieldNode["header-field"]?has_content>
        <#assign fieldSubNode = fieldNode["header-field"][0]>
    <#elseif fieldNode["default-field"]?has_content>
        <#assign fieldSubNode = fieldNode["default-field"][0]>
    <#else>
        <#-- this only makes sense for fields with a single conditional -->
        <#assign fieldSubNode = fieldNode["conditional-field"][0]>
    </#if>
    <#assign headerFieldNode = fieldNode["header-field"][0]!>
    <#assign defaultFieldNode = fieldNode["default-field"][0]!>
    <#assign containerStyle = ec.getResource().expandNoL10n(headerFieldNode["@container-style"]!, "")>
    <#assign headerAlign = fieldNode["@align"]!"left">
    <#t><div class="form-title<#if containerStyle?has_content> ${containerStyle}</#if><#if headerAlign == "center"> text-center<#elseif headerAlign == "right"> text-right</#if>">
        <#t><#if fieldSubNode["submit"]?has_content>&nbsp;<#else><@fieldTitle fieldSubNode/></#if>
        <#if fieldSubNode["@show-order-by"]! == "true" || fieldSubNode["@show-order-by"]! == "case-insensitive">
            <#assign caseInsensitive = fieldSubNode["@show-order-by"]! == "case-insensitive">
            <#assign curFieldName = fieldNode["@name"]>
            <#assign curOrderByField = ec.getContext().orderByField!>
            <#if curOrderByField?has_content && curOrderByField?contains(",")>
                <#list curOrderByField?split(",") as curOrderByFieldCandidate>
                    <#if curOrderByFieldCandidate?has_content && curOrderByFieldCandidate?contains(curFieldName)>
                        <#assign curOrderByField = curOrderByFieldCandidate><#break></#if>
                </#list>
            </#if>
            <#assign ascActive = curOrderByField?has_content && curOrderByField?contains(curFieldName) && !curOrderByField?starts_with("-")>
            <#assign descActive = curOrderByField?has_content && curOrderByField?contains(curFieldName) && curOrderByField?starts_with("-")>
            <#assign ascOrderByUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("orderByField", caseInsensitive?string("^","") + curFieldName)>
            <#assign descOrderByUrlInfo = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("orderByField", "-" + caseInsensitive?string("^","") + curFieldName)>
            <#if ascActive><#assign ascOrderByUrlInfo = descOrderByUrlInfo></#if>
            <#if descActive><#assign descOrderByUrlInfo = ascOrderByUrlInfo></#if>
            <span class="form-order-by">
                <a href="${ascOrderByUrlInfo.getUrlWithParams()}"<#if ascActive> class="active"</#if>><i class="fa fa-caret-up"></i></a>
                <a href="${descOrderByUrlInfo.getUrlWithParams()}"<#if descActive> class="active"</#if>><i class="fa fa-caret-down"></i></a>
            </span>
        </#if>
    <#t></div>
    <#if !isHeaderDialog && fieldNode["header-field"]?has_content && fieldNode["header-field"][0]?children?has_content>
        <div class="form-header-field<#if containerStyle?has_content> ${containerStyle}</#if><#if headerAlign == "center"> text-center<#elseif headerAlign == "right"> text-right</#if>">
            <@formListWidget fieldNode["header-field"][0] true true false false/>
            <#-- <#recurse fieldNode["header-field"][0]/> -->
        </div>
    </#if>
</#macro>
<#macro formListSubField fieldNode skipCell isHeaderField isMulti isMultiFinalRow>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.getResource().condition(fieldSubNode["@condition"], "")>
            <@formListWidget fieldSubNode skipCell isHeaderField isMulti isMultiFinalRow/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#assign isHeaderField = false>
        <@formListWidget fieldNode["default-field"][0] skipCell isHeaderField isMulti isMultiFinalRow/>
        <#return>
    </#if>
</#macro>
<#macro formListSubFirst fieldNode skipCell>
    <#if fieldNode["first-row-field"]?has_content>
        <#assign rowSubFieldNode = fieldNode["first-row-field"][0]>
        <#if rowSubFieldNode["hidden"]?has_content><#return></#if>
        <#assign isHeaderField = false>
        <@formListWidget rowSubFieldNode skipCell false false false/>
    </#if>
</#macro>
<#macro formListSubSecond fieldNode skipCell>
    <#if fieldNode["second-row-field"]?has_content>
        <#assign rowSubFieldNode = fieldNode["second-row-field"][0]>
        <#if rowSubFieldNode["hidden"]?has_content><#return></#if>
        <#assign isHeaderField = false>
        <@formListWidget rowSubFieldNode skipCell false false false/>
    </#if>
</#macro>
<#macro formListSubLast fieldNode skipCell>
    <#if fieldNode["last-row-field"]?has_content>
        <#assign rowSubFieldNode = fieldNode["last-row-field"][0]>
        <#if rowSubFieldNode["hidden"]?has_content><#return></#if>
        <#assign isHeaderField = false>
        <@formListWidget rowSubFieldNode skipCell false false false/>
    </#if>
</#macro>
<#macro formListWidget fieldSubNode skipCell isHeaderField isMulti isMultiFinalRow>
    <#if fieldSubNode["ignored"]?has_content><#return></#if>
    <#assign fieldSubParent = fieldSubNode?parent>
    <#if ec.getResource().condition(fieldSubParent["@hide"]!, "")><#return></#if>
    <#-- don't do a column for submit fields, they'll go in their own row at the bottom -->
    <#t><#if !isHeaderField && isMulti && !isMultiFinalRow && fieldSubNode["submit"]?has_content><#return></#if>
    <#t><#if !isHeaderField && isMulti && isMultiFinalRow && !fieldSubNode["submit"]?has_content><#return></#if>
    <#if fieldSubNode["hidden"]?has_content><#recurse fieldSubNode/><#return></#if>
    <#assign containerStyle = ec.getResource().expandNoL10n(fieldSubNode["@container-style"]!, "")>
    <#if fieldSubParent["@align"]! == "right"><#assign containerStyle = containerStyle + " text-right"><#elseif fieldSubParent["@align"]! == "center"><#assign containerStyle = containerStyle + " text-center"></#if>
    <#if !isMultiFinalRow && !isHeaderField><#if skipCell><div class="form-group<#if containerStyle?has_content>  ${containerStyle}</#if>"><#else><td class="form-group<#if containerStyle?has_content> ${containerStyle}</#if>"></#if></#if>
    <#t>${sri.pushContext()}
    <#list fieldSubNode?children as widgetNode><#if widgetNode?node_name == "set">${sri.setInContext(widgetNode)}</#if></#list>
    <#list fieldSubNode?children as widgetNode>
        <#if widgetNode?node_name == "link">
            <#assign linkNode = widgetNode>
            <#if linkNode["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(linkNode["@condition"], "")><#else><#assign conditionResult = true></#if>
            <#if conditionResult>
                <#if linkNode["@entity-name"]?has_content>
                    <#assign linkText = sri.getFieldEntityValue(linkNode)>
                <#else>
                    <#assign textMap = "">
                    <#if linkNode["@text-map"]?has_content><#assign textMap = ec.getResource().expression(linkNode["@text-map"], "")!></#if>
                    <#if textMap?has_content><#assign linkText = ec.getResource().expand(linkNode["@text"], "", textMap)>
                        <#else><#assign linkText = ec.getResource().expand(linkNode["@text"]!"", "")></#if>
                </#if>
                <#if linkText == "null"><#assign linkText = ""></#if>
                <#if linkText?has_content || linkNode["image"]?has_content || linkNode["@icon"]?has_content>
                    <#if linkNode["@encode"]! != "false"><#assign linkText = linkText?html></#if>
                    <#assign linkUrlInfo = sri.makeUrlByType(linkNode["@url"], linkNode["@url-type"]!"transition", linkNode, linkNode["@expand-transition-url"]!"true")>
                    <#assign linkFormId><@fieldId linkNode/>_${linkNode["@url"]?replace(".", "_")}</#assign>
                    <#assign afterFormText><@linkFormForm linkNode linkFormId linkText linkUrlInfo/></#assign>
                    <#t>${sri.appendToAfterScreenWriter(afterFormText)}
                    <#t><@linkFormLink linkNode linkFormId linkText linkUrlInfo/>
                <#else>&nbsp;</#if>
            <#else>&nbsp;</#if>
        <#elseif widgetNode?node_name == "set"><#-- do nothing, handled above -->
        <#else>
            <#assign widgetNodeText><#visit widgetNode></#assign>
            <#assign widgetNodeText = widgetNodeText?trim>
            <#t><#if widgetNodeText?has_content>${widgetNodeText}<#else>&nbsp;</#if>
        </#if>
    </#list>
    <#t>${sri.popContext()}
    <#if !isMultiFinalRow && !isHeaderField><#if skipCell></div><#else></td></#if></#if>
</#macro>
<#macro "row-actions"><#-- do nothing, these are run by the SRI --></#macro>

<#-- ========================================================== -->
<#-- ================== Form Field Widgets ==================== -->
<#-- ========================================================== -->

<#macro fieldName widgetNode><#assign fieldNode=widgetNode?parent?parent/>${fieldNode["@name"]?html}<#if isMulti?exists && isMulti && listEntryIndex?has_content && listEntryIndex?matches("\\d*")>_${listEntryIndex}</#if></#macro>
<#macro fieldId widgetNode><#assign fieldNode=widgetNode?parent?parent/><#if fieldFormId?has_content>${fieldFormId}<#else>${ec.getResource().expandNoL10n(fieldNode?parent["@name"], "")}</#if>_${fieldNode["@name"]}<#if listEntryIndex?has_content>_${listEntryIndex}</#if><#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#macro>
<#macro fieldTitle fieldSubNode><#t>
    <#t><#if (fieldSubNode?node_name == 'header-field')>
        <#local fieldNode = fieldSubNode?parent>
        <#local headerFieldNode = fieldNode["header-field"][0]!>
        <#local defaultFieldNode = fieldNode["default-field"][0]!>
        <#t><#if headerFieldNode["@title"]?has_content><#local fieldSubNode = headerFieldNode><#elseif defaultFieldNode["@title"]?has_content><#local fieldSubNode = defaultFieldNode></#if>
    </#if>
    <#t><#assign titleValue><#if fieldSubNode["@title"]?has_content>${ec.getResource().expand(fieldSubNode["@title"], "")}<#else><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>${ec.getL10n().localize(titleValue)}
</#macro>
<#macro fieldIdByName fieldName><#if fieldFormId?has_content>${fieldFormId}<#else>${ec.getResource().expandNoL10n(formNode["@name"], "")}</#if>_${fieldName}<#if listEntryIndex?has_content>_${listEntryIndex}</#if><#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#macro>

<#macro field><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "conditional-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "default-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro set><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>

<#macro check>
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")/></#if>
    <#assign id><@fieldId .node/></#assign>
    <#assign curName><@fieldName .node/></#assign>
    <#assign containerStyle = ec.getResource().expandNoL10n(.node["@container-style"]!, "")>
    <#list (options.keySet())! as key>
        <#assign allChecked = ec.getResource().expandNoL10n(.node["@all-checked"]!, "")>
        <#assign fullId = id>
        <#if (key_index > 0)><#assign fullId = id + "_" + key_index></#if>
        <span id="${fullId}"<#if containerStyle?has_content> class="${containerStyle}"</#if>><input type="checkbox" name="${curName}" value="${key?html}"<#if allChecked! == "true"> checked="checked"<#elseif currentValue?has_content && (currentValue==key || currentValue.contains(key))> checked="checked"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>><#if options.get(key)! != ""><span class="checkbox-label" onclick="$('#${fullId}').children('input[type=checkbox]').click()" style="cursor: default">${options.get(key)}</span></#if></span>
    </#list>
</#macro>

<#macro "date-find">
    <#if .node["@type"]! == "time"><#assign size=9><#assign maxlength=13><#assign defaultFormat="HH:mm"><#assign extraFormatsVal = "['LT', 'LTS', 'HH:mm']">
    <#elseif .node["@type"]! == "date"><#assign size=10><#assign maxlength=10><#assign defaultFormat="yyyy-MM-dd"><#assign extraFormatsVal = "['l', 'L', 'YYYY-MM-DD']">
    <#else><#assign size=16><#assign maxlength=23><#assign defaultFormat="yyyy-MM-dd HH:mm"><#assign extraFormatsVal = "['YYYY-MM-DD HH:mm', 'YYYY-MM-DD HH:mm:ss', 'MM/DD/YYYY HH:mm']">
    </#if>
    <#assign datepickerFormat><@getMomentDateFormat .node["@format"]!defaultFormat/></#assign>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fieldValueFrom = ec.getL10n().format(ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!""), defaultFormat)>
    <#assign fieldValueThru = ec.getL10n().format(ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!""), defaultFormat)>
    <#assign id><@fieldId .node/></#assign>
    <span class="form-date-find">
      <span>${ec.getL10n().localize("From")}&nbsp;</span>
    <#if .node["@type"]! != "time">
        <div class="input-group date" id="${id}_from">
            <input type="text" class="form-control" name="${curFieldName}_from" value="${fieldValueFrom?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <span class="input-group-addon"><i class="fa fa-calendar"></i></span>
        </div>
        <script>$('#${id}_from').datetimepicker({toolbarPlacement:'top', showClose:true, showClear:true, useStrict:true, showTodayButton:true, defaultDate:'${fieldValueFrom?html}' && moment('${fieldValueFrom?html}','${datepickerFormat}'), format:'${datepickerFormat}', extraFormats:${extraFormatsVal}, stepping:5, locale:"${ec.getUser().locale.toLanguageTag()}", keyBinds: {t: function() {this.date(moment());}, up: function () { this.date(this.date().clone().add(1, 'd')); }, down: function () { this.date(this.date().clone().subtract(1, 'd')); }, 'control up': function () { this.date(this.date().clone().add(1, 'd')); }, 'control down': function () { this.date(this.date().clone().subtract(1, 'd')); }}});</script>
    <#else>
        <input type="text" class="form-control" pattern="^(?:(?:([01]?\d|2[0-3]):)?([0-5]?\d):)?([0-5]?\d)$"
               name="${curFieldName}_from" value="${fieldValueFrom?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
    </#if>
    </span>
    <span class="form-date-find">
      <span>${ec.getL10n().localize("Thru")}&nbsp;</span>
    <#if .node["@type"]! != "time">
        <div class="input-group date" id="${id}_thru">
            <input type="text" class="form-control" name="${curFieldName}_thru" value="${fieldValueThru?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <span class="input-group-addon"><i class="fa fa-calendar"></i></span>
        </div>
        <script>$('#${id}_thru').datetimepicker({toolbarPlacement:'top', showClose:true, showClear:true, useStrict:true, showTodayButton:true, defaultDate:'${fieldValueThru?html}' && moment('${fieldValueThru?html}','${datepickerFormat}'), format:'${datepickerFormat}', extraFormats:${extraFormatsVal}, stepping:5, locale:"${ec.getUser().locale.toLanguageTag()}", keyBinds: {t: function() {this.date(moment());}, up: function () { this.date(this.date().clone().add(1, 'd')); }, down: function () { this.date(this.date().clone().subtract(1, 'd')); }, 'control up': function () { this.date(this.date().clone().add(1, 'd')); }, 'control down': function () { this.date(this.date().clone().subtract(1, 'd')); }}});</script>
    <#else>
        <input type="text" class="form-control" pattern="^(?:(?:([01]?\d|2[0-3]):)?([0-5]?\d):)?([0-5]?\d)$"
               name="${curFieldName}_thru" value="${fieldValueThru?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
    </#if>
    </span>
</#macro>

<#macro "date-period">
    <#assign id><@fieldId .node/></#assign>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fvOffset = ec.getContext().get(curFieldName + "_poffset")!>
    <#assign fvPeriod = ec.getContext().get(curFieldName + "_period")!?lower_case>
    <#assign fvDate = ec.getContext().get(curFieldName + "_pdate")!"">
    <#assign allowEmpty = .node["@allow-empty"]!"true">
    <div class="date-period">
        <select name="${curFieldName}_poffset" id="${id}_poffset"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <#if (allowEmpty! != "false")>
                <option value="">&nbsp;</option>
            </#if>
            <option value="0"<#if fvOffset == "0"> selected="selected"</#if>>${ec.getL10n().localize("This")}</option>
            <option value="-1"<#if fvOffset == "-1"> selected="selected"</#if>>${ec.getL10n().localize("Last")}</option>
            <option value="1"<#if fvOffset == "1"> selected="selected"</#if>>${ec.getL10n().localize("Next")}</option>
            <option value="-2"<#if fvOffset == "-2"> selected="selected"</#if>>-2</option>
            <option value="2"<#if fvOffset == "2"> selected="selected"</#if>>+2</option>
            <option value="-3"<#if fvOffset == "-3"> selected="selected"</#if>>-3</option>
            <option value="-4"<#if fvOffset == "-4"> selected="selected"</#if>>-4</option>
            <option value="-6"<#if fvOffset == "-6"> selected="selected"</#if>>-6</option>
            <option value="-12"<#if fvOffset == "-12"> selected="selected"</#if>>-12</option>
        </select>
        <select name="${curFieldName}_period" id="${id}_period"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <#if (allowEmpty! != "false")>
            <option value="">&nbsp;</option>
            </#if>
            <option value="day" <#if fvPeriod == "day"> selected="selected"</#if>>${ec.getL10n().localize("Day")}</option>
            <option value="7d" <#if fvPeriod == "7d"> selected="selected"</#if>>7 ${ec.getL10n().localize("Days")}</option>
            <option value="30d" <#if fvPeriod == "30d"> selected="selected"</#if>>30 ${ec.getL10n().localize("Days")}</option>
            <option value="week" <#if fvPeriod == "week"> selected="selected"</#if>>${ec.getL10n().localize("Week")}</option>
            <option value="weeks" <#if fvPeriod == "weeks"> selected="selected"</#if>>${ec.getL10n().localize("Weeks")}</option>
            <option value="month" <#if fvPeriod == "month"> selected="selected"</#if>>${ec.getL10n().localize("Month")}</option>
            <option value="months" <#if fvPeriod == "months"> selected="selected"</#if>>${ec.getL10n().localize("Months")}</option>
            <option value="quarter" <#if fvPeriod == "quarter"> selected="selected"</#if>>${ec.getL10n().localize("Quarter")}</option>
            <option value="year" <#if fvPeriod == "year"> selected="selected"</#if>>${ec.getL10n().localize("Year")}</option>
            <option value="7r" <#if fvPeriod == "7r"> selected="selected"</#if>>+/-7d</option>
            <option value="30r" <#if fvPeriod == "30r"> selected="selected"</#if>>+/-30d</option>
        </select>
        <div class="input-group date" id="${id}_pdate">
            <input type="text" class="form-control" name="${curFieldName}_pdate" value="${fvDate?html}" size="10" maxlength="10"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <span class="input-group-addon"><span class="fa fa-calendar"></span></span>
        </div>
        <script>
            $("#${id}_poffset").select2({ }); $("#${id}_period").select2({ });
            $('#${id}_pdate').datetimepicker({toolbarPlacement:'top', showClose:true, showClear:true, showTodayButton:true, useStrict:true, defaultDate: '${fvDate?html}' && moment('${fvDate?html}','YYYY-MM-DD'), format:'YYYY-MM-DD', extraFormats:['l', 'L', 'YYYY-MM-DD'], locale:"${ec.getUser().locale.toLanguageTag()}", keyBinds: {t: function() {this.date(moment());}, up: function () { this.date(this.date().clone().add(1, 'd')); }, down: function () { this.date(this.date().clone().subtract(1, 'd')); }, 'control up': function () { this.date(this.date().clone().add(1, 'd')); }, 'control down': function () { this.date(this.date().clone().subtract(1, 'd')); }}});
        </script>
    </div>
</#macro>

<#--
eonasdan/bootstrap-datetimepicker uses Moment for time parsing/etc
For Moment format refer to http://momentjs.com/docs/#/displaying/format/
For Java simple date format refer to http://docs.oracle.com/javase/6/docs/api/java/text/SimpleDateFormat.html
Java	Moment  	Description
-	    a	        am/pm
a	    A	        AM/PM
s	    s	        seconds without leading zeros
ss	    ss	        seconds, 2 digits with leading zeros
m	    m	        minutes without leading zeros
mm	    mm	        minutes, 2 digits with leading zeros
H	    H	        hour without leading zeros - 24-hour format
HH	    HH	        hour, 2 digits with leading zeros - 24-hour format
h	    h	        hour without leading zeros - 12-hour format
hh	    hh	        hour, 2 digits with leading zeros - 12-hour format
d	    D	        day of the month without leading zeros
dd	    DD	        day of the month, 2 digits with leading zeros (NOTE: moment uses lower case d for day of week!)
M	    M	        numeric representation of month without leading zeros
MM	    MM	        numeric representation of the month, 2 digits with leading zeros
MMM	    MMM	        short textual representation of a month, three letters
MMMM	MMMM	    full textual representation of a month, such as January or March
yy	    YY	        two digit representation of a year
yyyy	YYYY	    full numeric representation of a year, 4 digits

Summary of changes needed:
a => A, d => D, y => Y
-->
<#macro getMomentDateFormat dateFormat>${dateFormat?replace("a","A")?replace("d","D")?replace("y","Y")}</#macro>

<#macro "date-time">
    <#assign dtSubFieldNode = .node?parent>
    <#assign dtFieldNode = dtSubFieldNode?parent>
    <#assign javaFormat = .node["@format"]!>
    <#assign validationClasses = formInstance.getFieldValidationClasses(dtSubFieldNode)>
    <#if !javaFormat?has_content>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign datepickerFormat><@getMomentDateFormat javaFormat/></#assign>
    <#assign fieldValue = sri.getFieldValueString(dtFieldNode, .node["@default-value"]!"", javaFormat)>

    <#assign id><@fieldId .node/></#assign>

    <#if .node["@type"]! == "time"><#assign size=9><#assign maxlength=13><#assign extraFormatsVal = "['LT', 'LTS', 'HH:mm']">
        <#elseif .node["@type"]! == "date"><#assign size=10><#assign maxlength=10><#assign extraFormatsVal = "['l', 'L', 'YYYY-MM-DD']">
        <#else><#assign size=16><#assign maxlength=23><#assign extraFormatsVal = "['YYYY-MM-DD HH:mm', 'YYYY-MM-DD HH:mm:ss', 'MM/DD/YYYY HH:mm']"></#if>
    <#assign size = .node["@size"]!size>
    <#assign maxlength = .node["@max-length"]!maxlength>

    <#if .node["@type"]! != "time">
        <div class="input-group date" id="${id}">
            <input type="text" class="form-control<#if validationClasses?contains("required")> required</#if>"<#if validationClasses?contains("required")> required="required"</#if> name="<@fieldName .node/>" value="${fieldValue?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <span class="input-group-addon"><span class="fa fa-calendar"></span></span>
        </div>
        <script>
            $('#${id}').datetimepicker({toolbarPlacement:'top', showClose:true, showClear:true, showTodayButton:true,
                useStrict:true, defaultDate: '${fieldValue?html}' && moment('${fieldValue?html}','${datepickerFormat}'),
                format:'${datepickerFormat}', extraFormats:${extraFormatsVal}, stepping:${.node["@minute-stepping"]!"5"}, locale:"${ec.getUser().locale.toLanguageTag()}",
                keyBinds: {t: function() {this.date(moment());}, up: function () { this.date(this.date().clone().add(1, 'd')); }, down: function () { this.date(this.date().clone().subtract(1, 'd')); }, 'control up': function () { this.date(this.date().clone().add(1, 'd')); }, 'control down': function () { this.date(this.date().clone().subtract(1, 'd')); }}});
            $('#${id}').on("dp.change", function() { var jqEl = $('#${id}'); jqEl.val(jqEl.find("input").first().val()); jqEl.trigger("change"); });
        </script>
    <#else>
        <#-- datetimepicker does not support time only, even with plain HH:mm format; use a regex to validate time format -->
        <input type="text" class="form-control" pattern="^(?:(?:([01]?\d|2[0-3]):)?([0-5]?\d):)?([0-5]?\d)$" name="<@fieldName .node/>" value="${fieldValue?html}" size="${size}" maxlength="${maxlength}"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
    </#if>
</#macro>

<#macro display>
    <#assign dispFieldId><@fieldId .node/></#assign>
    <#assign dispFieldNode = .node?parent?parent>
    <#assign dispAlign = dispFieldNode["@align"]!"left">
    <#assign dispHidden = (!.node["@also-hidden"]?has_content || .node["@also-hidden"] == "true") && !(skipForm!false)>
    <#assign dispDynamic = .node["@dynamic-transition"]?has_content>
    <#assign fieldValue = "">
    <#if .node["@text"]?has_content>
        <#assign textMap = "">
        <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], "")!></#if>
        <#if textMap?has_content>
            <#assign fieldValue = ec.getResource().expand(.node["@text"], "", textMap)>
        <#else>
            <#assign fieldValue = ec.getResource().expand(.node["@text"], "")>
        </#if>
        <#if .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.getL10n().formatCurrency(fieldValue, ec.getResource().expression(.node["@currency-unit-field"], ""))>
        </#if>
    <#elseif .node["@currency-unit-field"]?has_content>
        <#assign fieldValue = ec.getL10n().formatCurrency(sri.getFieldValue(dispFieldNode, ""), ec.getResource().expression(.node["@currency-unit-field"], ""))>
    <#else>
        <#assign fieldValue = sri.getFieldValueString(.node)>
    </#if>
    <#if dispDynamic && !fieldValue?has_content><#assign fieldValue><@widgetTextValue .node true/></#assign></#if>
    <#t><span class="text-inline ${sri.getFieldValueClass(dispFieldNode)}<#if .node["@currency-unit-field"]?has_content> currency</#if><#if dispAlign == "center"> text-center<#elseif dispAlign == "right"> text-right</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if .node["@dynamic-transition"]?has_content> id="${dispFieldId}_display"</#if>>
    <#t><#if fieldValue?has_content><#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html?replace("\n", "<br>")}</#if><#else>&nbsp;</#if>
    <#t></span>
    <#t><#if dispHidden>
        <#if dispDynamic>
            <#assign hiddenValue><@widgetTextValue .node true "value"/></#assign>
            <input type="hidden" id="${dispFieldId}" name="<@fieldName .node/>" value="${hiddenValue}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#else>
            <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
            <#-- don't default to fieldValue for the hidden input value, will only be different from the entry value if @text is used, and we don't want that in the hidden value -->
            <input type="hidden" id="${dispFieldId}" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(dispFieldNode, "")?html}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        </#if>
    </#if>
    <#if dispDynamic>
        <#assign defUrlInfo = sri.makeUrlByType(.node["@dynamic-transition"], "transition", .node, "false")>
        <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
        <#assign depNodeList = .node["depends-on"]>
        <script>
            function populate_${dispFieldId}() {
                <#if .node["@depends-optional"]! != "true">
                    var hasAllParms = true;
                    <#list depNodeList as depNode>if (!$('#<@fieldIdByName depNode["@field"]/>').val()) { hasAllParms = false; } </#list>
                    if (!hasAllParms) { <#-- alert("not has all parms"); --> return; }
                </#if>
                $.ajax({ type:"POST", url:"${defUrlInfo.url}", data:{ moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                    <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local depNodeParm = depNode["@parameter"]!depNodeField><#local _void = defUrlParameterMap.remove(depNodeParm)!>, "${depNodeParm}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                    <#t><#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${defUrlParameterMap.get(parameterKey)}"</#if></#list>
                    <#t>}, dataType:"text" }).done( function(defaultText) { if (defaultText && defaultText.length) {
                        var label = '', value = '';
                        try {
                            var response = JSON.parse(defaultText);
                            if ($.isArray(response) && response.length) { response = response[0]; }
                            else if ($.isPlainObject(response) && response.hasOwnProperty('options') && response.options.length) { response = response.options[0]; }
                            if (response.hasOwnProperty('label')) { label = response.label; }
                            if (response.hasOwnProperty('value')) { value = response.value; }
                        } catch(e) { }
                        if (!label || !label.length) label = defaultText;
                        if (!value || !value.length) value = defaultText;
                        $('#${dispFieldId}_display').html(label);
                        <#if dispHidden>$('#${dispFieldId}').val(value);</#if>
                    }});
            }
            <#list depNodeList as depNode>
            $("#<@fieldIdByName depNode["@field"]/>").on('change', function() { populate_${dispFieldId}(); });
            </#list>
            populate_${dispFieldId}();
        </script>
    </#if>
</#macro>
<#macro "display-entity">
    <#assign fieldValue = sri.getFieldEntityValue(.node)!/>
    <#assign dispHidden = (!.node["@also-hidden"]?has_content || .node["@also-hidden"] == "true") && !(skipForm!false)>
    <#t><span class="text-inline<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"><#if fieldValue?has_content><#if .node["@encode"]! == "false">${fieldValue!"&nbsp;"}<#else>${(fieldValue!" ")?html?replace("\n", "<br>")}</#if><#else>&nbsp;</#if></span>
    <#-- don't default to fieldValue for the hidden input value, will only be different from the entry value if @text is used, and we don't want that in the hidden value -->
    <#t><#if dispHidden><input type="hidden" id="<@fieldId .node/>" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(.node?parent?parent, "")?html}"<#if ownerForm?has_content> form="${ownerForm}"</#if>></#if>
</#macro>

<#macro "drop-down">
    <#assign ddSubFieldNode = .node?parent>
    <#assign ddFieldNode = ddSubFieldNode?parent>
    <#assign id><@fieldId .node/></#assign>
    <#assign allowMultiple = ec.getResource().expandNoL10n(.node["@allow-multiple"]!, "") == "true">
    <#assign isDynamicOptions = .node["dynamic-options"]?has_content>
    <#assign isServerSearch = false>
    <#if isDynamicOptions><#assign doNode = .node["dynamic-options"][0]><#assign isServerSearch = doNode["@server-search"]! == "true"></#if>
    <#assign name><@fieldName .node/></#assign>
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValuePlainString(ddFieldNode, "")>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")></#if>
    <#if currentValue?starts_with("[")><#assign currentValue = currentValue?substring(1, currentValue?length - 1)?replace(" ", "")></#if>
    <#assign currentValueList = (currentValue?split(","))!>
    <#if currentValueList?has_content><#if allowMultiple><#assign currentValue=""><#else><#assign currentValue = currentValueList[0]></#if></#if>
    <#assign currentDescription = (options.get(currentValue))!>
    <#assign validationClasses = formInstance.getFieldValidationClasses(ddSubFieldNode)>
    <#assign optionsHasCurrent = currentDescription?has_content>
    <#if !optionsHasCurrent && .node["@current-description"]?has_content>
        <#assign currentDescription = ec.getResource().expand(.node["@current-description"], "")></#if>
    <select name="${name}" class="<#if isDynamicOptions> dynamic-options</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if><#if validationClasses?has_content> ${validationClasses}</#if><#if isServerSearch || allowMultiple> noResetSelect2</#if>"<#if isServerSearch> style="min-width:200px;"</#if> id="${id}"<#if allowMultiple> multiple="multiple"</#if><#if .node["@size"]?has_content> size="${.node["@size"]}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
    <#if !allowMultiple>
        <#-- don't add first-in-list or empty option if allowMultiple (can deselect all to be empty, including empty option allows selection of empty which isn't the point) -->
        <#if currentValue?has_content>
            <#if .node["@current"]! == "first-in-list">
                <option selected="selected" value="${currentValue}"><#if currentDescription?has_content>${currentDescription}<#else>${currentValue}</#if></option><#rt/>
                <option value="${currentValue}">---</option><#rt/>
            <#elseif !optionsHasCurrent>
                <option selected="selected" value="${currentValue}"><#if currentDescription?has_content>${currentDescription}<#else>${currentValue}</#if></option><#rt/>
            </#if>
        </#if>
        <#assign allowEmpty = ec.getResource().expandNoL10n(.node["@allow-empty"]!, "")/>
        <#if (allowEmpty! == "true") || !(options?has_content)>
            <option value="">&nbsp;</option>
        </#if>
    </#if>
    <#if options?has_content>
        <#list (options.keySet())! as key>
            <#if allowMultiple && currentValueList?has_content><#assign isSelected = currentValueList?seq_contains(key)>
                <#else><#assign isSelected = currentValue?has_content && currentValue == key></#if>
            <option<#if isSelected> selected="selected"</#if> value="${key}">${options.get(key)}</option>
        </#list>
    </#if>
    </select>
    <#if ec.getResource().expandNoL10n(.node["@show-not"]!, "") == "true"><span><input type="checkbox" class="form-control" name="${name}_not" value="Y"<#if ec.getWeb().parameters.get(name + "_not")! == "Y"> checked="checked"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${ec.getL10n().localize("Not")}</span></#if>
    <#-- <span>[${currentValue}]; <#list currentValueList as curValue>[${curValue!''}], </#list></span> -->
    <#if allowMultiple><input type="hidden" id="${id}_op" name="${name}_op" value="in"></#if>
    <#if isDynamicOptions>
        <#assign depNodeList = doNode["depends-on"]>
        <#assign doUrlInfo = sri.makeUrlByType(doNode["@transition"], "transition", doNode, "false")>
        <#assign doUrlParameterMap = doUrlInfo.getParameterMap()>
        <script>
            var ${id}S2Opts = { <#if .node["@combo-box"]! == "true">tags:true, tokenSeparators:[',',' '],</#if>
                <#if allowMultiple>closeOnSelect:false,</#if>
                <#if isServerSearch>minimumResultsForSearch:0, width:"100%", minimumInputLength:${doNode["@min-length"]!"1"},
                ajax:{ url:"${doUrlInfo.url}", type:"POST", dataType:"json", cache:true, delay:${doNode["@delay"]!"300"},
                    data:function(params) { return { moquiSessionToken: "${(ec.getWeb().sessionToken)!}", term:(params.term || ''), pageIndex:(params.page || 1) - 1
                        <#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local depNodeParm = depNode["@parameter"]!depNodeField><#local _void = doUrlParameterMap.remove(depNodeParm)!>, "${depNodeParm}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                        <#list doUrlParameterMap.keySet() as parameterKey><#if doUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${doUrlParameterMap.get(parameterKey)}"</#if></#list>
                    }},
                    processResults:function(data, params) {
                        var isDataArray = moqui.isArray(data);
                        var list = isDataArray ? data : data.options;
                        var newData = [];
                        // funny case where select2 specifies no option.@value if empty so &nbsp; ends up passed with form submit; now filtered on server for \u00a0 only and set to null
                        <#if allowEmpty! == "true">if (!params.page || params.page <= 1) newData.push({ id:'\u00a0', text:'\u00a0' });</#if>
                        var labelField = "${doNode["@label-field"]!"label"}"; var valueField = "${doNode["@value-field"]!"value"}";
                        $.each(list, function(idx, curObj) {
                            var idVal = curObj[valueField]; if (!idVal) idVal = '\u00a0';
                            newData.push({ id:idVal, text:curObj[labelField] })
                        });
                        var outObj = { results:newData };
                        if (!isDataArray) {
                            params.page = params.page || 1; // NOTE: 1 based index, is 0 based on server side
                            var pageSize = data.pageSize || 20;
                            outObj.pagination = { more: (data.count ? (params.page * pageSize) < data.count : false) };
                        }
                        return outObj;
                    }
                }
                </#if>
            };
            $("#${id}").select2(${id}S2Opts);
            <#-- $("#${id}").on("select2:select", function () { $("#${id}").select2("open").select2("close"); }); -->
            function populate_${id}(params) {
                <#if doNode["@depends-optional"]! != "true">
                    var hasAllParms = true;
                    <#list depNodeList as depNode>if (!$('#<@fieldIdByName depNode["@field"]/>').val()) { hasAllParms = false; } </#list>
                    if (!hasAllParms) { $("#${id}").select2("destroy"); $('#${id}').html(""); $("#${id}").select2({ }); <#-- alert("not has all parms"); --> return; }
                </#if>
                $.ajax({ type:"POST", url:"${doUrlInfo.url}", data:{ moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                        <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local _void = doUrlParameterMap.remove(depNodeField)!>, "${depNode["@parameter"]!depNodeField}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                        <#t><#list doUrlParameterMap.keySet() as parameterKey><#if doUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${doUrlParameterMap.get(parameterKey)}"</#if></#list>
                        <#t>, term:((params && params.term) || '')}, dataType:"json"}).done(
                    function(data) {
                        var list = moqui.isArray(data) ? data : data.options;
                        if (list) {
                            var jqEl = $("#${id}");
                            var bWasFocused = jqEl.next().hasClass('select2-container--focus');
                            jqEl.select2("destroy");
                            jqEl.html("");<#-- clear out the drop-down -->
                            <#if allowEmpty! == "true">
                            jqEl.append('<option value="">&nbsp;</option>');
                            </#if>
                            <#if allowMultiple && currentValueList?has_content>var currentValues = [<#list currentValueList as curVal>"${curVal}"<#sep>, </#list>];</#if>
                            $.each(list, function(key, value) {
                                var optionValue = value["${doNode["@value-field"]!"value"}"];
                                <#if allowMultiple && currentValueList?has_content>
                                if (currentValues.indexOf(optionValue) >= 0) {
                                <#else>
                                if (optionValue === "${currentValue}") {
                                </#if>
                                    jqEl.append("<option selected='selected' value='" + optionValue + "'>" + value["${doNode["@label-field"]!"label"}"] + "</option>");
                                } else {
                                    jqEl.append("<option value='" + optionValue + "'>" + value["${doNode["@label-field"]!"label"}"] + "</option>");
                                }
                            });
                            $("#${id}").select2(${id}S2Opts);
                            if( bWasFocused ) jqEl.focus();
                            setTimeout(function() { jqEl.trigger('change'); }, 50);
                        }
                    }
                );
            }
            <#list depNodeList as depNode>
            $("#<@fieldIdByName depNode["@field"]/>").on('change', function() { populate_${id}(<#if currentValue?has_content>{term:"${currentValue}"}</#if>); });
            </#list>
            <#if isServerSearch><#if currentValue?has_content>populate_${id}({term:"${currentValue}"});</#if>
                <#else>populate_${id}();</#if>

        </script>
    <#else>
        <script>$("#${id}").select2({ <#if allowMultiple>closeOnSelect:false, width:"100%", </#if><#if .node["@combo-box"]! == "true">tags:true, tokenSeparators:[',',' ']</#if> });</script>
        <#-- is this really needed any more? $("#${id}").on("select2:select", function () { $("#${id}").select2("open").select2("close"); }); -->
    </#if>
</#macro>

<#macro file><input type="file" class="form-control" name="<@fieldName .node/>"<#if .node.@multiple! == "true"> multiple</#if><#if .node.@accept?has_content> accept="${.node.@accept}"</#if> value="${sri.getFieldValueString(.node)?html}" size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></#macro>

<#macro hidden>
    <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
    <#assign id><@fieldId .node/></#assign>
    <input type="hidden" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(.node?parent?parent, .node["@default-value"]!"")?html}" id="${id}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
</#macro>

<#macro ignored><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>

<#-- TABLED, not to be part of 1.0:
<#macro "lookup">
    <#assign curFieldName = .node?parent?parent["@name"]?html/>
    <#assign curFormName = .node?parent?parent?parent["@name"]?html/>
    <#assign id><@fieldId .node/></#assign>
    <input type="text" name="${curFieldName}" value="${sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", null)?html}" size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if ec.getResource().condition(.node.@disabled!"false", "")> disabled="disabled"</#if> id="${id}">
    <#assign ajaxUrl = ""/><#- - LATER once the JSON service stuff is in place put something real here - ->
    <#- - LATER get lookup code in place, or not... - ->
    <script>
        $(document).ready(function() {
            new ConstructLookup("${.node["@target-screen"]}", "${id}", document.${curFormName}.${curFieldName},
            <#if .node["@secondary-field"]?has_content>document.${curFormName}.${.node["@secondary-field"]}<#else>null</#if>,
            "${curFormName}", "${width!""}", "${height!""}", "${position!"topcenter"}", "${fadeBackground!"true"}", "${ajaxUrl!""}", "${showDescription!""}", ''); });
    </script>
</#macro>
-->

<#macro password>
    <#assign validationClasses = formInstance.getFieldValidationClasses(.node?parent)>
    <input type="password" name="<@fieldName .node/>" id="<@fieldId .node/>" class="form-control<#if validationClasses?has_content> ${validationClasses}</#if>" size="${.node.@size!"25"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if validationClasses?contains("required")> required</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</#macro>

<#macro radio>
    <#assign options = sri.getFieldOptions(.node)/>
    <#assign currentValue = sri.getFieldValueString(.node)/>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")/></#if>
    <#assign id><@fieldId .node/></#assign>
    <#assign curName><@fieldName .node/></#assign>
    <#list (options.keySet())! as key>
        <span id="${id}<#if (key_index > 0)>_${key_index}</#if>"><input type="radio" name="${curName}" value="${key?html}"<#if currentValue?has_content && currentValue==key> checked="checked"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${options.get(key)!""}</span>
    </#list>
</#macro>

<#macro "range-find">
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign id><@fieldId .node/></#assign>
<span class="form-range-find">
    <span>${ec.getL10n().localize("From")}&nbsp;</span><input type="text" class="form-control" name="${curFieldName}_from" value="${ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!"")?html}" size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="${id}_from"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</span>
<span class="form-range-find">
    <span>${ec.getL10n().localize("Thru")}&nbsp;</span><input type="text" class="form-control" name="${curFieldName}_thru" value="${ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!"")?html}" size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="${id}_thru"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</span>
</#macro>

<#macro reset><input type="reset" name="<@fieldName .node/>" value="<@fieldTitle .node?parent/>" id="<@fieldId .node/>"<#if .node["@icon"]?has_content> iconcls="ui-icon-${.node["@icon"]}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></#macro>

<#macro submit>
    <#assign confirmationMessage = ec.getResource().expand(.node["@confirmation"]!, "")/>
    <#assign buttonText><#if .node["@text"]?has_content>${ec.getResource().expand(.node["@text"], "")}<#else><@fieldTitle .node?parent/></#if></#assign>
    <#assign iconClass = .node["@icon"]!>
    <#if !iconClass?has_content><#assign iconClass = sri.getThemeIconClass(buttonText)!></#if>
    <button type="submit" name="<@fieldName .node/>" value="<@fieldName .node/>" id="<@fieldId .node/>"<#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}');"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if> class="btn btn-primary btn-sm"<#if ownerForm?has_content> form="${ownerForm}"</#if>><#if iconClass?has_content><i class="${iconClass}"></i> </#if>
    <#if .node["image"]?has_content><#assign imageNode = .node["image"][0]>
        <img src="${sri.makeUrlByType(imageNode["@url"],imageNode["@url-type"]!"content",null,"true")}" alt="<#if imageNode["@alt"]?has_content>${imageNode["@alt"]}<#else><@fieldTitle .node?parent/></#if>"<#if imageNode["@width"]?has_content> width="${imageNode["@width"]}"</#if><#if imageNode["@height"]?has_content> height="${imageNode["@height"]}"</#if>>
    <#else>
        <#t>${buttonText}
    </#if>
    </button>
</#macro>

<#macro "text-area">
    <#assign textAreaId><@fieldId .node/></#assign>
    <#assign editorType = ec.getResource().expand(.node["@editor-type"]!"", "")>
    <#assign editorScreenThemeId = ec.getResource().expand(.node["@editor-theme"]!"", "")>
<textarea class="form-control" name="<@fieldName .node/>" id="${textAreaId}" <#if .node["@cols"]?has_content>cols="${.node["@cols"]}"<#else>style="width:100%;"</#if> rows="${.node["@rows"]!"3"}"<#if (.node["@read-only"]!"false") == "true"> readonly="readonly"</#if><#if .node["@maxlength"]?has_content> maxlength="${.node["@maxlength"]}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>${sri.getFieldValueString(.node)?html}</textarea>
    <#if editorType == "html">
        <#assign editorThemeCssList = sri.getThemeValues("STRT_STYLESHEET", editorScreenThemeId)>
        <script src="https://cdn.ckeditor.com/4.14.1/standard-all/ckeditor.js" type="text/javascript"></script>
        <script>
        CKEDITOR.dtd.$removeEmpty['i'] = false;
        CKEDITOR.replace('${textAreaId}', { customConfig:'',<#if editorThemeCssList?has_content>contentsCss:[<#list editorThemeCssList as themeCss>'${themeCss}'<#sep>,</#list>],</#if>
            allowedContent:true, linkJavaScriptLinksAllowed:true, fillEmptyBlocks:false,
            extraAllowedContent:'p(*)[*]{*};div(*)[*]{*};li(*)[*]{*};ul(*)[*]{*};i(*)[*]{*};span(*)[*]{*}',
            width:'100%', height:'600px', removeButtons:'Image,Save,NewPage,Preview' }).on('change', function(evt) { this.updateElement(); });
        </script>
    <#elseif editorType == "md">
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.css" type="text/css"/>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.js" type="text/javascript"></script>
        <script>new SimpleMDE({ element: document.getElementById("${textAreaId}"), indentWithTabs:false, autoDownloadFontAwesome:false, autofocus:true, spellChecker:false, forceSync:true });</script>
    </#if>
</#macro>

<#macro "text-line">
    <#assign tlSubFieldNode = .node?parent>
    <#assign tlFieldNode = tlSubFieldNode?parent>
    <#assign id><@fieldId .node/></#assign>
    <#assign name><@fieldName .node/></#assign>
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#assign validationClasses = formInstance.getFieldValidationClasses(tlSubFieldNode)>
    <#assign regexpInfo = formInstance.getFieldValidationRegexpInfo(tlSubFieldNode)!>
    <#assign inputType><#if .node["@input-type"]?has_content>${.node["@input-type"]}<#else><#rt>
        <#lt><#if validationClasses?contains("email")>email<#elseif validationClasses?contains("url")>url<#else>text</#if></#if></#assign>
    <#-- NOTE: removed number type (<#elseif validationClasses?contains("number")>number) because on Safari, maybe others, ignores size and behaves funny for decimal values -->
    <#if .node["@ac-transition"]?has_content>
        <#assign acUrlInfo = sri.makeUrlByType(.node["@ac-transition"], "transition", .node, "false")>
        <#assign acUrlParameterMap = acUrlInfo.getParameterMap()>
        <#assign acShowValue = .node["@ac-show-value"]! == "true">
        <#assign acUseActual = .node["@ac-use-actual"]! == "true">
        <#if .node["@ac-initial-text"]?has_content><#assign valueText = ec.getResource().expand(.node["@ac-initial-text"]!, "")>
            <#else><#assign valueText = fieldValue></#if>
        <#t><input id="${id}_ac" type="${inputType}"
            <#t> name="${name}_ac" value="${valueText?html}" size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
            <#t><#if ec.getResource().condition(.node.@disabled!"false", "")> disabled="disabled"</#if>
            <#t> class="form-control typeahead<#if validationClasses?has_content> ${validationClasses}</#if>"<#if validationClasses?contains("required")> required</#if>
            <#t><#if regexpInfo?has_content> pattern="${regexpInfo.regexp}" data-msg-pattern="${regexpInfo.message!"Invalid format"}"</#if>
            <#t><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if> autocomplete="off"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <input id="${id}" type="hidden" name="${name}" value="${fieldValue?html}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#if acShowValue><span id="${id}_value" class="form-autocomplete-value"><#if valueText?has_content>${valueText?html}<#else>&nbsp;</#if></span></#if>
        <#assign depNodeList = .node["depends-on"]>
        <script>
            $("#${id}_ac").typeahead({ <#if .node["@ac-min-length"]?has_content>minLength: ${.node["@ac-min-length"]},</#if> highlight: true, hint: false }, { limit: 99,
                source: moqui.debounce(function(query, syncResults, asyncResults) { $.ajax({
                    url: "${acUrlInfo.url}", type: "POST", dataType: "json", data: { term: query, moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                        <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local _void = acUrlParameterMap.remove(depNodeField)!>, '${depNode["@parameter"]!depNodeField}': $('#<@fieldIdByName depNodeField/>').val()</#list>
                        <#t><#list acUrlParameterMap.keySet() as parameterKey><#if acUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${acUrlParameterMap.get(parameterKey)}"</#if></#list> },
                    success: function(data) { var list = moqui.isArray(data) ? data : data.options; asyncResults($.map(list, function(item) { return { label: item.label, value: item.value } })); }
                }); }, <#if .node["@ac-delay"]?has_content>${.node["@ac-delay"]}<#else>300</#if>),
                display: function(item) { return item.label; }
            });
            $("#${id}_ac").bind('typeahead:select', function(event, item) {
                if (item) { this.value = item.value; $("#${id}").val(item.value); $("#${id}").trigger("change"); $("#${id}_ac").val(item.label);<#if acShowValue> if (item.label) { $("#${id}_value").html(item.label); }</#if> return false; }
            });

            $("#${id}_ac").change(function() { if (!$("#${id}_ac").val()) { $("#${id}").val(""); $("#${id}").trigger("change"); }<#if acUseActual> else { $("#${id}").val($("#${id}_ac").val()); $("#${id}").trigger("change"); }</#if> });
            <#list depNodeList as depNode>
                $("#<@fieldIdByName depNode["@field"]/>").change(function() { $("#${id}").val(""); $("#${id}_ac").val(""); });
            </#list>
            <#if !.node["@ac-initial-text"]?has_content>
            /* load the initial value if there is one */
            if ($("#${id}").val()) {
                $.ajax({ url: "${acUrlInfo.url}", type: "POST", dataType: "json", data: { term: $("#${id}").val(), moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#list acUrlParameterMap.keySet() as parameterKey><#if acUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${acUrlParameterMap.get(parameterKey)}"</#if></#list> },
                    success: function(data) {
                        var list = moqui.isArray(data) ? data : data.options;
                        var curValue = $("#${id}").val();
                        for (var i = 0; i < list.length; i++) { if (list[i].value == curValue) { $("#${id}_ac").val(list[i].label); <#if acShowValue>$("#${id}_value").html(list[i].label);</#if> break; } }
                        <#-- don't do this by default if we haven't found a valid one: if (list && list[0].label) { $("#${id}_ac").val(list[0].label); <#if acShowValue>$("#${id}_value").html(list[0].label);</#if> } -->
                    }
                });
            }
            </#if>
        </script>
    <#else>
        <#assign tlAlign = tlFieldNode["@align"]!"left">
        <#t><input id="${id}" type="${inputType}"
        <#t> name="${name}" value="${fieldValue?html}" <#if .node.@size?has_content>size="${.node.@size}"<#else>style="width:100%;"</#if><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
        <#t><#if ec.getResource().condition(.node.@disabled!"false", "")> disabled="disabled"</#if>
        <#t> class="form-control<#if validationClasses?has_content> ${validationClasses}</#if><#if tlAlign == "center"> text-center<#elseif tlAlign == "right"> text-right</#if>"
        <#t><#if validationClasses?contains("required")> required</#if><#if regexpInfo?has_content> pattern="${regexpInfo.regexp}" data-msg-pattern="${regexpInfo.message!"Invalid format"}"</#if>
        <#t><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#assign expandedMask = ec.getResource().expandNoL10n(.node["@mask"], "")!>
        <#if expandedMask?has_content><script>$('#${id}').inputmask("${expandedMask}");</script></#if>
        <#if .node["@default-transition"]?has_content>
            <#assign defUrlInfo = sri.makeUrlByType(.node["@default-transition"], "transition", .node, "false")>
            <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
            <#assign depNodeList = .node["depends-on"]>
            <script>
                function populate_${id}() {
                    // if ($('#${id}').val()) return;
                    var hasAllParms = true;
                    <#list depNodeList as depNode>if (!$('#<@fieldIdByName depNode["@field"]/>').val()) { hasAllParms = false; } </#list>
                    if (!hasAllParms) { <#-- alert("not has all parms"); --> return; }
                    $.ajax({ type:"POST", url:"${defUrlInfo.url}", data:{ moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                            <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local _void = defUrlParameterMap.remove(depNodeField)!>, "${depNode["@parameter"]!depNodeField}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                            <#t><#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${defUrlParameterMap.get(parameterKey)}"</#if></#list>
                            <#t>}, dataType:"text", success:function(defaultText) {   $('#${id}').val(defaultText);  } });
                }
                <#list depNodeList as depNode>
                $("#<@fieldIdByName depNode["@field"]/>").on('change', function() { populate_${id}(); });
                </#list>
                populate_${id}();
            </script>
        </#if>
    </#if>
</#macro>

<#macro "text-find">
<span class="form-text-find">
    <#assign defaultOperator = .node["@default-operator"]!"contains">
    <#assign curFieldName><@fieldName .node/></#assign>
    <#if .node["@hide-options"]! == "true" || .node["@hide-options"]! == "operator">
        <input type="hidden" name="${curFieldName}_op" value="${defaultOperator}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
    <#else>
        <span><input type="checkbox" class="form-control" name="${curFieldName}_not" value="Y"<#if ec.getWeb().parameters.get(curFieldName + "_not")! == "Y"> checked="checked"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${ec.getL10n().localize("Not")}</span>
        <select name="${curFieldName}_op" class="form-control"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <option value="equals"<#if defaultOperator == "equals"> selected="selected"</#if>>${ec.getL10n().localize("Equals")}</option>
            <option value="like"<#if defaultOperator == "like"> selected="selected"</#if>>${ec.getL10n().localize("Like")}</option>
            <option value="contains"<#if defaultOperator == "contains"> selected="selected"</#if>>${ec.getL10n().localize("Contains")}</option>
            <option value="begins"<#if defaultOperator == "begins"> selected="selected"</#if>>${ec.getL10n().localize("Begins With")}</option>
            <option value="empty"<#rt/><#if defaultOperator == "empty"> selected="selected"</#if>>${ec.getL10n().localize("Empty")}</option>
        </select>
    </#if>
    <input type="text" class="form-control" name="${curFieldName}" value="${sri.getFieldValueString(.node)?html}" size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="<@fieldId .node/>"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
    <#assign ignoreCase = (ec.getWeb().parameters.get(curFieldName + "_ic")! == "Y") || !(.node["@ignore-case"]?has_content) || (.node["@ignore-case"] == "true")>
    <#if .node["@hide-options"]! == "true" || .node["@hide-options"]! == "ignore-case">
        <input type="hidden" name="${curFieldName}_ic" value="Y"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
    <#else>
        <span><input type="checkbox" class="form-control" name="${curFieldName}_ic" value="Y"<#if ignoreCase> checked="checked"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${ec.getL10n().localize("Ignore Case")}</span>
    </#if>
</span>
</#macro>

<#macro widgetTextValue widgetNode alwaysGet=false valueField="label">
    <#assign widgetType = widgetNode?node_name>
    <#assign curFieldName><@fieldName widgetNode/></#assign>
    <#assign noDisplay = ["display", "display-entity", "hidden", "ignored", "password", "reset", "submit", "text-area", "link", "label"]>
    <#t><#if !alwaysGet && noDisplay?seq_contains(widgetType)><#return></#if>
    <#t><#if widgetType == "drop-down">
        <#assign ddFieldNode = widgetNode?parent?parent>
        <#assign allowMultiple = ec.getResource().expandNoL10n(widgetNode["@allow-multiple"]!, "") == "true">
        <#assign isDynamicOptions = widgetNode["dynamic-options"]?has_content>
        <#assign options = sri.getFieldOptions(widgetNode)>
        <#assign currentValue = sri.getFieldValuePlainString(ddFieldNode, "")>
        <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(widgetNode["@no-current-selected-key"]!, "")></#if>
        <#if currentValue?starts_with("[")><#assign currentValue = currentValue?substring(1, currentValue?length - 1)?replace(" ", "")></#if>
        <#assign currentValueList = (currentValue?split(","))!>
        <#if currentValueList?has_content><#if allowMultiple><#assign currentValue=""><#else><#assign currentValue = currentValueList[0]></#if></#if>
        <#assign currentDescription = (options.get(currentValue))!>
        <#assign optionsHasCurrent = currentDescription?has_content>
        <#if !optionsHasCurrent && widgetNode["@current-description"]?has_content><#assign currentDescription = ec.getResource().expand(widgetNode["@current-description"], "")></#if>
        <#t><#if allowMultiple>
            <#list currentValueList as listValue>
                <#t><#if isDynamicOptions>
                    <#assign doNode = widgetNode["dynamic-options"][0]>
                    <#assign transValue = sri.getFieldTransitionValue(doNode["@transition"], doNode, listValue, doNode["@label-field"]!"label", alwaysGet)!>
                    <#t><#if transValue?has_content>${transValue}<#elseif listValue?has_content>${listValue}</#if><#if listValue_has_next>, </#if>
                <#else>
                    <#assign currentDescription = (options.get(listValue))!>
                    <#t><#if currentDescription?has_content>${currentDescription}<#elseif listValue?has_content>${listValue}</#if><#if listValue_has_next>, </#if>
                </#if><#t>
            </#list>
        <#else>
            <#t><#if isDynamicOptions>
                <#assign doNode = widgetNode["dynamic-options"][0]>
                <#assign transValue = sri.getFieldTransitionValue(doNode["@transition"], doNode, currentValue, doNode["@label-field"]!"label", alwaysGet)!>
                <#t><#if transValue?has_content>${transValue}<#elseif currentValue?has_content>${currentValue}</#if>
            <#else>
                <#t><#if currentDescription?has_content>${currentDescription}<#elseif currentValue?has_content>${currentValue}</#if>
            </#if><#t>
        </#if><#t>
        <#t><#if ec.getWeb().parameters.get(curFieldName + "_not")! == "Y"> (${ec.getL10n().localize("Not")})</#if>
    <#elseif widgetType == "check" || widgetType == "radio">
        <#assign currentValue = sri.getFieldValueString(widgetNode)/>
        <#if !currentValue?has_content><#return></#if>
        <#assign options = sri.getFieldOptions(widgetNode)/>
        <#assign currentLabel = "">
        <#list (options.keySet())! as key><#if currentValue?has_content && currentValue==key><#assign currentLabel = options.get(key)></#if></#list>
        <#t><#if currentLabel?has_content>${currentLabel}<#else>${currentValue}</#if>
    <#elseif widgetType == "text-line">
        <#assign fieldValue = sri.getFieldValueString(widgetNode)>
        <#t><#if widgetNode["@ac-transition"]?has_content>
            <#assign transValue = sri.getFieldTransitionValue(widgetNode["@ac-transition"], widgetNode, fieldValue, "label", alwaysGet)!>
            <#t><#if transValue?has_content>${transValue}</#if>
        <#else>
            <#t><#if fieldValue?has_content>${fieldValue}</#if>
        </#if><#t>
    <#elseif widgetType == "date-period">
        <#assign fvPeriod = ec.getContext().get(curFieldName + "_period")!?lower_case>
        <#if fvPeriod?has_content>
            <#assign fvOffset = ec.getContext().get(curFieldName + "_poffset")!"0">
            <#assign fvDate = ec.getContext().get(curFieldName + "_pdate")!"">
            <#t>${ec.getUser().getPeriodDescription(fvPeriod, fvOffset, fvDate)}
        <#else>
            <#assign fieldValueFrom = ec.getL10n().format(ec.getContext().get(curFieldName + "_from")!, "yyyy-MM-dd")>
            <#assign fieldValueThru = ec.getL10n().format(ec.getContext().get(curFieldName + "_thru")!, "yyyy-MM-dd")>
            <#t><#if fieldValueFrom?has_content>${ec.getL10n().localize("From")} ${fieldValueFrom?html}</#if>
            <#t><#if fieldValueThru?has_content> ${ec.getL10n().localize("to")} ${fieldValueThru?html}</#if>
        </#if>
    <#elseif widgetType == "date-time">
        <#assign dtFieldNode = widgetNode?parent?parent>
        <#assign javaFormat = widgetNode["@format"]!>
        <#t><#if !javaFormat?has_content><#if widgetNode["@type"]! == "time"><#assign javaFormat="HH:mm"><#elseif widgetNode["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd"><#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if></#if>
        <#assign fieldValue = sri.getFieldValueString(dtFieldNode, widgetNode["@default-value"]!"", javaFormat)>
        <#t><#if fieldValue?has_content>${fieldValue?html}</#if>
    <#elseif widgetType == "date-find">
        <#t><#if widgetNode["@type"]! == "time"><#assign defaultFormat="HH:mm"><#elseif widgetNode["@type"]! == "date"><#assign defaultFormat="yyyy-MM-dd"><#else><#assign defaultFormat="yyyy-MM-dd HH:mm"></#if>
        <#assign fieldValueFrom = ec.getL10n().format(ec.getContext().get(curFieldName + "_from")!?default(widgetNode["@default-value-from"]!""), defaultFormat)>
        <#assign fieldValueThru = ec.getL10n().format(ec.getContext().get(curFieldName + "_thru")!?default(widgetNode["@default-value-thru"]!""), defaultFormat)>
        <#t><#if fieldValueFrom?has_content>${ec.getL10n().localize("From")} ${fieldValueFrom?html}</#if>
        <#t><#if fieldValueThru?has_content> ${ec.getL10n().localize("to")} ${fieldValueThru?html}</#if>
    <#elseif widgetType == "range-find">
        <#assign fieldValueFrom = ec.getContext().get(curFieldName + "_from")!?default(widgetNode["@default-value-from"]!"")>
        <#assign fieldValueThru = ec.getContext().get(curFieldName + "_thru")!?default(widgetNode["@default-value-thru"]!"")>
        <#t><#if fieldValueFrom?has_content>${ec.getL10n().localize("From")} ${fieldValueFrom?html}</#if>
        <#t><#if fieldValueThru?has_content> ${ec.getL10n().localize("to")} ${fieldValueThru?html}</#if>
    <#elseif widgetType == "display">
        <#assign fieldValue = sri.getFieldValueString(widgetNode)>
        <#t><#if widgetNode["@dynamic-transition"]?has_content>
            <#assign transValue = sri.getFieldTransitionValue(widgetNode["@dynamic-transition"], widgetNode, fieldValue, valueField, alwaysGet)!>
            <#t><#if transValue?has_content>${transValue}</#if>
        <#else>
            <#t><#if fieldValue?has_content>${fieldValue}</#if>
        </#if><#t>
    <#else>
        <#-- handles text-find, ... -->
        <#t>${sri.getFieldValueString(widgetNode)}
    </#if><#t>
</#macro>
