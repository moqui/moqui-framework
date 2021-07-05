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
    <#if .node["@type"]! == "popup"><#-- NOTE: popup menus no longer handled here, how handled dynamically in navbar.html.ftl -->
    <#-- default to type=tab -->
    <#else><subscreens-tabs/></#if>
</#if></#macro>
<#macro "subscreens-active"><subscreens-active/></#macro>
<#macro "subscreens-panel">
    <#if .node["@type"]! == "popup"><#-- NOTE: popup menus no longer handled here, how handled dynamically in navbar.html.ftl -->
        <subscreens-active/>
    <#elseif .node["@type"]! == "stack"><h1>LATER stack type subscreens-panel not yet supported.</h1>
    <#elseif .node["@type"]! == "wizard"><h1>LATER wizard type subscreens-panel not yet supported.</h1>
    <#else><#-- default to type=tab -->
        <div<#if .node["@id"]?has_content> id="${.node["@id"]}-tabpanel"</#if>>
            <subscreens-tabs/>
            <subscreens-active/>
        </div>
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
    <#if .node["@paginate"]! == "true">
        <#assign listName = .node["@list"]>
        <#assign listObj = context.get(listName)>
        <#assign pagParms = Static["org.moqui.util.CollectionUtilities"].paginateParameters(listObj?size, listName, context)>
        <form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
            <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
            <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></form-paginate>
    </#if>
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
    <#assign boxHeader = .node["box-header"][0]!>
    <#assign boxType = ec.resource.expandNoL10n(.node["@type"], "")!>
    <#if !boxType?has_content><#assign boxType = "default"></#if>
    <container-box<#if contBoxDivId?has_content> id="${contBoxDivId}"</#if> type="${boxType}"<#if boxHeader??> title="${ec.getResource().expand(boxHeader["@title"]!"", "")?html}"</#if> :initial-open="<#if ec.getResource().expandNoL10n(.node["@initial"]!, "") == "closed">false<#else>true</#if>">
        <#-- NOTE: direct use of the container-box component would not use template elements but rather use the 'slot' attribute directly on the child elements which we can't do here -->
        <#if boxHeader??><template slot="header"><#recurse boxHeader></template></#if>
        <#if .node["box-toolbar"]?has_content><template slot="toolbar"><#recurse .node["box-toolbar"][0]></template></#if>
        <#if .node["box-body"]?has_content><box-body<#if .node["box-body"][0]["@height"]?has_content> height="${.node["box-body"][0]["@height"]}"</#if>><#recurse .node["box-body"][0]></box-body></#if>
        <#if .node["box-body-nopad"]?has_content><#recurse .node["box-body-nopad"][0]></#if>
    </container-box>
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
    <#assign iconClass = "fa fa-external-link">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign title = ec.getResource().expand(.node["@title"], "")>
        <#if !title?has_content><#assign title = buttonText></#if>
        <#assign cdDivId><@nodeId .node/></#assign>
        <button id="${cdDivId}-button" type="button" data-toggle="modal" data-target="#${cdDivId}" data-original-title="${buttonText}" data-placement="bottom" class="btn btn-${ec.getResource().expandNoL10n(.node["@type"]!"primary", "")} btn-sm ${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}"><i class="${iconClass}"></i> ${buttonText}</button>
        <container-dialog id="${cdDivId}" width="${.node["@width"]!"760"}" title="${title}"<#if _openDialog! == cdDivId> :openDialog="true"</#if>>
            <#recurse>
        </container-dialog>
    </#if>
</#macro>
<#macro "dynamic-container">
    <#assign dcDivId><@nodeId .node/></#assign>
    <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true").addParameter("_dynamic_container_id", dcDivId)>
    <dynamic-container id="${dcDivId}" url="${urlInstance.passThroughSpecialParameters().pathWithParams}"></dynamic-container>
</#macro>
<#macro "dynamic-dialog">
    <#assign iconClass = "fa fa-external-link">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign title = ec.getResource().expand(.node["@title"], "")>
        <#if !title?has_content><#assign title = buttonText></#if>
        <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
        <#assign ddDivId><@nodeId .node/></#assign>
        <#if urlInstance.disableLink>
            <button id="${ddDivId}-button" type="button" class="disabled text-muted btn btn-${.node["@type"]!"primary"} btn-sm ${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}"><i class="${iconClass}"></i> ${buttonText}</button>
        <#else>
            <button id="${ddDivId}-button" type="button" data-toggle="modal" data-target="#${ddDivId}" data-original-title="${buttonText}" data-placement="bottom" class="btn btn-${.node["@type"]!"primary"} btn-sm ${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}"><i class="${iconClass}"></i> ${buttonText}</button>
            <#assign afterFormText>
            <dynamic-dialog id="${ddDivId}" url="${urlInstance.urlWithParams}" width="${.node["@width"]!"760"}" title="${title}"<#if _openDialog! == ddDivId> :openDialog="true"</#if>></dynamic-dialog>
            </#assign>
            <#t>${sri.appendToAfterScreenWriter(afterFormText)}
        </#if>
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
        <#assign itemsUrl = ajaxUrlInfo.path>
    <#else>
        <#assign ajaxUrlInfo = sri.makeUrlByType("actions", "transition", .node, "true")>
        <#assign itemsUrl = ajaxUrlInfo.path + "/" + .node["@name"]>
    </#if>
    <#assign ajaxParms = ajaxUrlInfo.getParameterMap()>
    <tree-top id="${.node["@name"]}" items="${itemsUrl}" open-path="${ec.getResource().expandNoL10n(.node["@open-path"], "")}"
              :parameters="{<#list ajaxParms.keySet() as pKey>'${pKey}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(ajaxParms.get(pKey)!"")}'<#sep>,</#list>}"></tree-top>
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
        <#if sri.isAnchorLink(linkNode, urlInstance)>
            <#assign linkNoParam = linkNode["@url-noparam"]! == "true">
            <#if urlInstance.isScreenUrl()><#assign linkElement = "m-link">
                <#if linkNoParam><#assign urlText = urlInstance.path/><#else><#assign urlText = urlInstance.pathWithParams/></#if>
            <#else><#assign linkElement = "a">
                <#if linkNoParam><#assign urlText = urlInstance.url/><#else><#assign urlText = urlInstance.urlWithParams/></#if>
            </#if>
            <${linkElement} href="${urlText}"<#if linkFormId?has_content> id="${linkFormId}"</#if><#rt>
                <#t><#if linkNode["@target-window"]?has_content> target="${linkNode["@target-window"]}"</#if>
                <#t><#if linkNode["@dynamic-load-id"]?has_content> load-id="${linkNode["@dynamic-load-id"]}"</#if>
                <#t><#if confirmationMessage?has_content><#if linkElement == "m-link"> :confirmation="'${confirmationMessage?js_string}'"<#else> onclick="return confirm('${confirmationMessage?js_string}')"</#if></#if>
                <#t> class="<#if linkNode["@link-type"]! != "anchor">btn btn-${linkNode["@btn-type"]!"primary"} btn-sm</#if><#if linkNode["@style"]?has_content> ${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>"
                <#t><#if linkNode["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(linkNode["@tooltip"], "")}"</#if>>
                <#t><#if iconClass?has_content><i class="${iconClass}"></i> </#if><#rt>
                <#t><#if linkNode["image"]?has_content><#visit linkNode["image"][0]><#else>${linkText}</#if>
                <#t><#if badgeMessage?has_content> <span class="badge">${badgeMessage}</span></#if>
                <#t></${linkElement}>
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
    <#if !urlInstance.disableLink && !sri.isAnchorLink(linkNode, urlInstance)>
        <#if urlInstance.getTargetTransition()?has_content><#assign linkFormType = "m-form"><#else><#assign linkFormType = "form-link"></#if>
        <${linkFormType} action="${urlInstance.path}" name="${linkFormId!""}"<#if linkFormId?has_content> id="${linkFormId}"</#if><#if linkNode["@target-window"]?has_content> target="${linkNode["@target-window"]}"</#if>><#-- :no-validate="true" -->
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
        </${linkFormType}>
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
<${labelType}<#if labelDivId?has_content> id="${labelDivId}"</#if> class="text-inline <#if .node["@style"]?has_content>${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if .node["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node["@tooltip"], "")}"</#if>>${labelValue}</${labelType}>
        </#if>
    </#if>
</#macro>
<#macro editable>
    <#-- for docs on JS usage see: http://www.appelsiini.net/projects/jeditable -->
    <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
    <#assign urlParms = urlInstance.getParameterMap()>
    <#assign labelValue = ec.getResource().expand(.node["@text"], "")>
    <#if .node["@encode"]! == "true"><#assign labelValue = labelValue?html?replace("\n", "<br>")></#if>
    <#if labelValue?trim?has_content>
        <#assign hasLoad = false>
        <#if .node["editable-load"]?has_content>
            <#assign hasLoad = true><#assign loadNode = .node["editable-load"][0]>
            <#assign loadUrlInfo = sri.makeUrlByType(loadNode["@transition"], "transition", loadNode, "true")>
            <#assign loadUrlParms = loadUrlInfo.getParameterMap()>
        </#if>
        <m-editable id="<@nodeId .node/>" label-type="${.node["@type"]!"span"}" label-value="${labelValue?xml}"<#rt>
            <#t> url="${urlInstance.url}" :url-parameters="{<#list urlParms.keySet() as parameterKey>'${parameterKey}':'${urlParms[parameterKey]}',</#list>}"
            <#t> parameter-name="${.node["@parameter-name"]!"value"}" widget-type="${.node["@widget-type"]!"textarea"}"
            <#t> indicator="${ec.getL10n().localize("Saving")}" tooltip="${ec.getL10n().localize("Click to edit")}"
            <#t> cancel="${ec.getL10n().localize("Cancel")}" submit="${ec.getL10n().localize("Save")}"
            <#t> <#if hasLoad> load-url="${loadUrlInfo.url}" :load-parameters="{<#list loadUrlParms.keySet() as parameterKey>'${parameterKey}':'${loadUrlParms[parameterKey]}',</#list>}"</#if>/>
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
    <#-- Use the formSingleNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formSingleNode = formInstance.getFormNode()>
    <#t>${sri.pushSingleFormMapContext(formSingleNode["@map"]!"fieldValues")}
    <#assign skipStart = formSingleNode["@skip-start"]! == "true">
    <#assign skipEnd = formSingleNode["@skip-end"]! == "true">
    <#assign ownerForm = formSingleNode["@owner-form"]!>
    <#if ownerForm?has_content><#assign skipStart = true><#assign skipEnd = true></#if>
    <#assign urlInstance = sri.makeUrlByType(formSingleNode["@transition"], "transition", null, "true")>
    <#assign formSingleId>${ec.getResource().expandNoL10n(formSingleNode["@name"], "")}<#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#assign>
    <#if urlInstance.isScreenUrl()>
        <#if urlInstance.getTargetTransition()?has_content><#assign formSingleType = "m-form"><#else><#assign formSingleType = "form-link"></#if>
    <#else><#assign formSingleType = "form"></#if>
    <#if !skipStart>
    <${formSingleType} name="${formSingleId}" id="${formSingleId}" action="${urlInstance.path}"<#if formSingleNode["@focus-field"]?has_content> focus-field="${formSingleNode["@focus-field"]}"</#if><#rt>
            <#t><#if formSingleNode["@body-parameters"]?has_content> :body-parameter-names="[<#list formSingleNode["@body-parameters"]?split(",") as bodyParm>'${bodyParm}'<#sep>,</#list>]"</#if>
            <#t><#if formSingleNode["@background-message"]?has_content> submit-message="${formSingleNode["@background-message"]?html}"</#if>
            <#t><#if formSingleNode["@background-reload-id"]?has_content> submit-reload-id="${formSingleNode["@background-reload-id"]}"</#if>
            <#t><#if formSingleNode["@background-hide-id"]?has_content> submit-hide-id="${formSingleNode["@background-hide-id"]}"</#if>
            <#t><#if formSingleNode["@exclude-empty-fields"]! == "true"> :exclude-empty-fields="true"</#if>
            <#t> autocapitalize="off" autocomplete="off">
        <input type="hidden" name="moquiFormName" value="${formSingleNode["@name"]}">
        <#assign lastUpdatedString = sri.getNamedValuePlain("lastUpdatedStamp", formSingleNode)>
        <#if lastUpdatedString?has_content><input type="hidden" name="lastUpdatedStamp" value="${lastUpdatedString}"></#if>
    </#if>
    <#if formSingleNode["@pass-through-parameters"]! == "true">
        <#assign currentFindUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("moquiFormName").removeParameter("moquiSessionToken").removeParameter("lastStandalone").removeParameter("formListFindId")>
        <#assign currentFindUrlParms = currentFindUrl.getParameterMap()>
        <#list currentFindUrlParms.keySet() as parmName><#if !formInstance.getFieldNode(parmName)??>
            <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
        </#if></#list>
    </#if>
        <fieldset class="form-horizontal"<#if urlInstance.disableLink> disabled="disabled"</#if>>
        <#if formSingleNode["field-layout"]?has_content>
            <#recurse formSingleNode["field-layout"][0]/>
        <#else>
            <#list formSingleNode["field"] as fieldNode><@formSingleSubField fieldNode formSingleId/></#list>
        </#if>
        </fieldset>
    <#if !skipEnd></${formSingleType}></#if>
    <#t>${sri.popContext()}<#-- context was pushed for the form-single so pop here at the end -->
    <#if sri.doBoundaryComments()><!-- END   form-single[@name=${.node["@name"]}] --></#if>
    <#assign ownerForm = ""><#-- clear ownerForm so later form fields don't pick it up -->
    <#assign formSingleId = "">
    <#assign fieldsJsName = "">
</#macro>
<#macro "field-ref">
    <#assign fieldRef = .node["@name"]>
    <#assign fieldNode = formInstance.getFieldNode(fieldRef)!>
    <#if fieldNode?has_content>
        <@formSingleSubField fieldNode formSingleId/>
    <#else>
        <div>Error: could not find field with name ${fieldRef} referred to in a field-ref.@name attribute.</div>
    </#if>
</#macro>
<#macro "fields-not-referenced">
    <#assign nonReferencedFieldList = formInstance.getFieldLayoutNonReferencedFieldList()>
    <#list nonReferencedFieldList as nonReferencedField>
        <@formSingleSubField nonReferencedField formSingleId/></#list>
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
                <a role="button" data-toggle="collapse" data-parent="#${accordionId}" href="#${accordionId}_collapse${accordionIndex}"
                   aria-expanded="true" aria-controls="${accordionId}_collapse${accordionIndex}"<#if !accIsActive> class="collapsed"</#if>>${fgTitle!"Fields"}</a>
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
    <#assign accordionId = .node["@id"]!(formSingleId + "_accordion")>
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

<#macro formSingleSubField fieldNode formSingleId>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.getResource().condition(fieldSubNode["@condition"], "")>
            <@formSingleWidget fieldSubNode formSingleId "col-sm" fsFieldRow!false fsBigRow!false/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formSingleWidget fieldNode["default-field"][0] formSingleId "col-sm" fsFieldRow!false fsBigRow!false/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode formSingleId colPrefix inFieldRow bigRow>
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
                    <label class="control-label" for="${formSingleId}_${fieldSubParent["@name"]}">${curFieldTitle}</label><#-- was form-title -->
                </#if>
    <#else>
        <#-- NOTE: this style is only good for 2 fields in a field-row! in field-row cols are double size because are inside a ${colPrefix}-6 element -->
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
            <label class="control-label ${labelClass}" for="${formSingleId}_${fieldSubParent["@name"]}">${curFieldTitle}</label><#-- was form-title -->
            <div class="${widgetClass}<#if containerStyle?has_content> ${containerStyle}</#if>">
        </#if>
    </#if>
    <#t>${sri.pushContext()}
    <#assign fieldFormId = formSingleId><#-- set this globally so fieldId macro picks up the proper formSingleId, clear after -->
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
    <#assign currentFindUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageIndex").removeParameter("moquiFormName").removeParameter("moquiSessionToken").removeParameter("lastStandalone").removeParameter("formListFindId")>
    <#assign currentFindUrlParms = currentFindUrl.getParameterMap()>
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
    <#if isSavedFinds || isHeaderDialog>
        <#assign headerFormDialogId = formId + "_hdialog">
        <#assign headerFormId = formId + "_header">
        <#assign headerFormButtonText = ec.getL10n().localize("Find Options")>
        <container-dialog id="${headerFormDialogId}" title="${headerFormButtonText}">
            <#-- Saved Finds -->
            <#if isSavedFinds && isHeaderDialog><h4 style="margin-top: 0;">${ec.getL10n().localize("Saved Finds")}</h4></#if>
            <#if isSavedFinds>
                <#assign activeFormListFind = formListInfo.getFormInstance().getActiveFormListFind(ec)!>
                <#assign formSaveFindUrl = sri.buildUrl("formSaveFind").path>
                <#assign descLabel = ec.getL10n().localize("Description")>
                <#if activeFormListFind?has_content>
                    <#assign screenScheduled = formListInfo.getScreenForm().getFormListFindScreenScheduled(activeFormListFind.formListFindId, ec)!>
                    <div><strong>${ec.getL10n().localize("Active Saved Find:")} ${activeFormListFind.description?html}</strong></div>
                    <#if screenScheduled?has_content>
                        <p>(Scheduled for <#if screenScheduled.renderMode! == 'xsl-fo'>PDF<#else>${screenScheduled.renderMode!?upper_case}</#if><#rt>
                            <#t> ${Static["org.moqui.impl.service.ScheduledJobRunner"].getCronDescription(screenScheduled.cronExpression, ec.user.getLocale(), true)!})</p>
                    <#else>
                        <m-form class="form-inline" id="${formId}_SCHED" action="${formSaveFindUrl}">
                            <input type="hidden" name="formListFindId" value="${activeFormListFind.formListFindId}">
                            <input type="hidden" name="screenPath" value="${sri.getScreenUrlInstance().path}">
                            <div class="form-group">
                                <label class="sr-only" for="${formId}_SCHED_renderMode">${ec.getL10n().localize("Mode")}</label>
                                <drop-down name="renderMode" id="${formId}_SCHED_renderMode" :options="[{id:'xlsx',text:'XLSX'},{id:'csv',text:'CSV'},{id:'xsl-fo',text:'PDF'}]"></drop-down>
                            </div>
                            <div class="form-group">
                                <label class="sr-only" for="${formId}_SCHED_cronSelected">${ec.getL10n().localize("Schedule")}</label>
                                <drop-down name="cronSelected" id="${formId}_SCHED_cronSelected" value="" :options="[{id:'0 0 6 ? * MON-FRI',text:'Monday-Friday'},{id:'0 0 6 ? * *',text:'Every Day'},{id:'0 0 6 ? * MON',text:'Monday Only'},{id:'0 0 6 1 * ?',text:'Monthly'}]"></drop-down>
                            </div>

                            <button type="submit" name="ScheduleFind" class="btn btn-primary btn-sm" onclick="return confirm('${ec.getL10n().localize("Setup a schedule to send this saved find to you by email?")}');">${ec.getL10n().localize("Schedule")}</button>
                        </m-form>
                    </#if>
                </#if>
                <#if currentFindUrlParms?has_content>
                    <div><m-form class="form-inline" id="${formId}_NewFind" action="${formSaveFindUrl}">
                        <input type="hidden" name="formLocation" value="${formListInfo.getSavedFindFullLocation()}">
                        <#list currentFindUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                        </#list>
                        <div class="form-group">
                            <label class="sr-only" for="${formId}_NewFind_description">${descLabel}</label>
                            <input type="text" size="40" name="_findDescription" id="${formId}_NewFind_description" placeholder="${descLabel}" class="form-control required" required="required">
                        </div>
                        <button type="submit" class="btn btn-primary btn-sm">${ec.getL10n().localize("Save New Find")}</button>
                    </m-form></div>
                <#else>
                    <p>${ec.getL10n().localize("No find parameters, choose some to save a new find or update existing")}</p>
                </#if>
                <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                <#list userFindInfoList as userFindInfo>
                    <#assign formListFind = userFindInfo.formListFind>
                    <#assign findParameters = userFindInfo.findParameters>
                    <#-- use only formListFindId now that ScreenRenderImpl picks it up and auto adds configured parameters:
                        <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)> -->
                    <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", formListFind.formListFindId)>
                    <#assign saveFindFormId = formId + "_SaveFind" + userFindInfo_index>
                    <div>
                    <#if currentFindUrlParms?has_content>
                        <m-form class="form-inline" id="${saveFindFormId}" action="${formSaveFindUrl}">
                            <input type="hidden" name="formLocation" value="${formListInfo.getSavedFindFullLocation()}">
                            <input type="hidden" name="formListFindId" value="${formListFind.formListFindId}">
                            <#list currentFindUrlParms.keySet() as parmName>
                                <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                            </#list>
                            <div class="form-group">
                                <label class="sr-only" for="${saveFindFormId}_description">${descLabel}</label>
                                <input type="text" size="40" name="_findDescription" id="${saveFindFormId}_description" value="${formListFind.description?html}" class="form-control required" required="required">
                            </div>
                            <button type="submit" name="UpdateFind" class="btn btn-primary btn-sm">${ec.getL10n().localize("Update to Current")}</button>
                            <#if userFindInfo.isByUserId == "true"><button type="submit" name="DeleteFind" class="btn btn-danger btn-sm" onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');">&times;</button></#if>
                        </m-form>
                        <m-link href="${doFindUrl.pathWithParams}" class="btn btn-success btn-sm">${ec.getL10n().localize("Do Find")}</m-link>
                    <#else>
                        <m-link href="${doFindUrl.pathWithParams}" class="btn btn-success btn-sm">${ec.getL10n().localize("Do Find")}</m-link>
                        <#if userFindInfo.isByUserId == "true">
                        <m-form class="form-inline" id="${saveFindFormId}" action="${formSaveFindUrl}" :no-validate="true">
                            <input type="hidden" name="formListFindId" value="${formListFind.formListFindId}">
                            <button type="submit" name="DeleteFind" class="btn btn-danger btn-sm" onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');">&times;</button>
                        </m-form>
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
                <#assign skipFormSave = skipForm!false>
                <#assign skipForm = false>
                <form-link name="${headerFormId}" id="${headerFormId}" action="${curUrlInstance.path}"><template slot-scope="props">
                    <#if formListFindId?has_content><input type="hidden" name="formListFindId" value="${formListFindId}"></#if>
                    <#if context[listName + "PageSize"]??><input type="hidden" name="pageSize" value="${context[listName + "PageSize"]?c}"></#if>
                    <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                    <fieldset class="form-horizontal">
                        <div class="form-group"><div class="col-sm-2">&nbsp;</div><div class="col-sm-10">
                            <button type="button" name="clearParameters" class="btn btn-primary btn-sm" @click.prevent="props.clearForm">${ec.getL10n().localize("Clear Parameters")}</button></div></div>

                        <#-- Always add an orderByField to select one or more columns to order by -->
                        <div class="form-group">
                            <label class="control-label col-sm-2" for="${headerFormId}_orderByField">${ec.getL10n().localize("Order By")}</label>
                            <div class="col-sm-10">
                                <select name="orderBySelect" id="${headerFormId}_orderBySelect" multiple="multiple" style="width:100%;" class="noResetSelect2">
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
                                <input type="hidden" id="${headerFormId}_orderByField" name="orderByField" value="${(orderByField!"")?html}">
                                <m-script>
                                    $("#${headerFormId}_orderBySelect").selectivity({ positionDropdown: function(dropdownEl, selectEl) { dropdownEl.css("width", "300px"); } })[0].selectivity.filterResults = function(results) {
                                        // Filter out asc and desc options if anyone selected.
                                        return results.filter(function(item){return !this._data.some(function(data_item) {return data_item.id.substring(1) === item.id.substring(1);});}, this);
                                    };
                                    <#assign orderByJsValue = formListInfo.getOrderByActualJsString(ec.getContext().orderByField)>
                                    <#if orderByJsValue?has_content>$("#${headerFormId}_orderBySelect").selectivity("value", ${orderByJsValue});</#if>
                                    $("div#${headerFormId}_orderBySelect").on("change", function(evt) {
                                        if (evt.value) $("#${headerFormId}_orderByField").val(evt.value.join(","));
                                    });
                                </m-script>
                            </div>
                        </div>
                        <#t>${sri.pushSingleFormMapContext("")}
                        <#list formNode["field"] as fieldNode><#if fieldNode["header-field"]?has_content && fieldNode["header-field"][0]?children?has_content>
                            <#assign headerFieldNode = fieldNode["header-field"][0]>
                            <#assign allHidden = true>
                            <#list fieldNode?children as fieldSubNode>
                                <#if !(fieldSubNode["hidden"]?has_content || fieldSubNode["ignored"]?has_content)><#assign allHidden = false></#if>
                            </#list>

                            <#if !(ec.getResource().condition(fieldNode["@hide"]!, "") || allHidden ||
                                    ((!fieldNode["@hide"]?has_content) && fieldNode?children?size == 1 &&
                                    (headerFieldNode["hidden"]?has_content || headerFieldNode["ignored"]?has_content)))>
                                <@formSingleWidget headerFieldNode headerFormId "col-sm" false false/>
                            <#elseif (headerFieldNode["hidden"])?has_content>
                                <#recurse headerFieldNode/>
                            </#if>
                        </#if></#list>
                        <#t>${sri.popContext()}<#-- context was pushed so pop here at the end -->
                    </fieldset>
                </template></form-link>
                <#assign skipForm = skipFormSave>
            </#if>
        </container-dialog>
    </#if>
    <#if isSelectColumns>
        <#assign selectColumnsDialogId = formId + "_SelColsDialog">
        <#assign selectColumnsSortableId = formId + "_SelColsSortable">
        <#assign fieldsNotInColumns = formListInfo.getFieldsNotReferencedInFormListColumn()>
        <container-dialog id="${selectColumnsDialogId}" title="${ec.l10n.localize("Column Fields")}">
            <p>${ec.getL10n().localize("Drag fields to the desired column or do not display")}</p>
            <ul id="${selectColumnsSortableId}">
                <li id="hidden"><div>${ec.l10n.localize("Do Not Display")}</div>
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
                    <li id="column_${columnFieldList_index}"><div>${ec.l10n.localize("Column")} ${columnFieldList_index + 1}</div><ul>
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
            <m-form class="form-inline" id="${formId}_SelColsForm" action="${sri.buildUrl("formSelectColumns").path}">
                <input type="hidden" name="formLocation" value="${formListInfo.getFormLocation()}">
                <input type="hidden" id="${formId}_SelColsForm_columnsTree" name="columnsTree" value="">
                <#if currentFindUrlParms?has_content><#list currentFindUrlParms.keySet() as parmName>
                    <input type="hidden" name="${parmName}" value="${currentFindUrlParms.get(parmName)!?html}">
                </#list></#if>
                <input type="submit" name="SaveColumns" value="${ec.getL10n().localize("Save Columns")}" class="btn btn-success btn-sm"/>
                <input type="submit" name="ResetColumns" value="${ec.getL10n().localize("Reset to Default")}" class="btn btn-danger btn-sm"/>
            </m-form>
        </container-dialog>
        <m-script>$('#${selectColumnsDialogId}').on('shown.bs.modal', function() {
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
        })</m-script>
    </#if>
    <#if formNode["@show-text-button"]! == "true">
        <#assign showTextDialogId = formId + "_TextDialog">
        <#assign textLinkUrl = sri.getScreenUrlInstance()>
        <#assign textLinkUrlParms = textLinkUrl.getParameterMap()>
        <container-dialog id="${showTextDialogId}" title="${ec.getL10n().localize("Export Fixed-Width Plain Text")}">
            <#-- NOTE: don't use m-form, most commonly results in download and if not won't be html -->
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
        </container-dialog>
    </#if>
    <#if formNode["@show-pdf-button"]! == "true">
        <#assign showPdfDialogId = formId + "_PdfDialog">
        <#assign pdfLinkUrl = sri.getScreenUrlInstance()>
        <#assign pdfLinkUrlParms = pdfLinkUrl.getParameterMap()>
        <container-dialog id="${showPdfDialogId}" title="${ec.getL10n().localize("Generate PDF")}">
            <#-- NOTE: don't use m-form, most commonly results in download and if not won't be html -->
            <form id="${formId}_Pdf" method="post" action="${ec.web.getWebappRootUrl(false, null)}/fop${pdfLinkUrl.getPath()}">
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
        </container-dialog>
    </#if>
</#macro>
<#macro paginationHeader formListInfo formId isHeaderDialog>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign mainColInfoList = formListInfo.getMainColInfo()>
    <#assign numColumns = (mainColInfoList?size)!100>
    <#if numColumns == 0><#assign numColumns = 100></#if>
    <#assign isSavedFinds = formNode["@saved-finds"]! == "true">
    <#assign isSelectColumns = formNode["@select-columns"]! == "true">
    <#assign isPaginated = (!(formNode["@paginate"]! == "false") && context[listName + "Count"]?? && (context[listName + "Count"]! > 0) &&
            (!formNode["@paginate-always-show"]?has_content || formNode["@paginate-always-show"]! == "true" || (context[listName + "PageMaxIndex"] > 0)))>
    <#if (isHeaderDialog || isSavedFinds || isSelectColumns || isPaginated) && hideNav! != "true">
        <tr class="form-list-nav-row"><th colspan="${numColumns}"><nav class="form-list-nav">
            <#if isSavedFinds>
                <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                <#if userFindInfoList?has_content>
                    <#assign activeUserFindName = ""/>
                    <#if ec.getContext().formListFindId?has_content>
                        <#list userFindInfoList as userFindInfo>
                            <#if userFindInfo.formListFind.formListFindId == ec.getContext().formListFindId>
                                <#assign activeUserFindName = userFindInfo.description/></#if></#list>
                    </#if>
                    <div class="btn-group">
                      <button type="button" class="btn <#if activeUserFindName?has_content>btn-info<#else>btn-default</#if> dropdown-toggle" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
                        <#if activeUserFindName?has_content>${activeUserFindName?html}<#else>${ec.getL10n().localize("Saved Finds")}</#if> <span class="caret"></span>
                      </button>
                      <ul class="dropdown-menu">
                        <li><m-link href="${sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", "_clear").pathWithParams}">${ec.getL10n().localize("Clear Current Find")}</m-link></li>
                        <#list userFindInfoList as userFindInfo>
                            <#assign formListFind = userFindInfo.formListFind>
                            <#assign findParameters = userFindInfo.findParameters>
                            <#-- use only formListFindId now that ScreenRenderImpl picks it up and auto adds configured parameters:
                                <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)> -->
                            <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", formListFind.formListFindId)>
                            <li><m-link href="${doFindUrl.pathWithParams}">${userFindInfo.description?html}</m-link></li>
                        </#list>
                      </ul>
                    </div>
                </#if>
            </#if>
            <#if isSavedFinds || isHeaderDialog><button id="${headerFormDialogId}_button" type="button" data-toggle="modal" data-target="#${headerFormDialogId}" data-original-title="${headerFormButtonText}" data-placement="bottom" class="btn btn-default"><i class="fa fa-external-link"></i> ${headerFormButtonText}</button></#if>
            <#if isSelectColumns><button id="${selectColumnsDialogId}_button" type="button" data-toggle="modal" data-target="#${selectColumnsDialogId}" data-original-title="${ec.getL10n().localize("Columns")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-external-link"></i> ${ec.getL10n().localize("Columns")}</button></#if>

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
                <button id="${showTextDialogId}_button" type="button" data-toggle="modal" data-target="#${showTextDialogId}" data-original-title="${ec.getL10n().localize("Text")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-external-link"></i> ${ec.getL10n().localize("Text")}</button>
            </#if>
            <#if formNode["@show-pdf-button"]! == "true">
                <#assign showPdfDialogId = formId + "_PdfDialog">
                <button id="${showPdfDialogId}_button" type="button" data-toggle="modal" data-target="#${showPdfDialogId}" data-original-title="${ec.getL10n().localize("PDF")}" data-placement="bottom" class="btn btn-default"><i class="fa fa-external-link"></i> ${ec.getL10n().localize("PDF")}</button>
            </#if>

            <#if isPaginated>
                <#-- no more paginate/show-all button, use page size drop-down with 500 instead:
                <#if formNode["@show-all-button"]! == "true" && (context[listName + 'Count'] < 500)>
                    <#if context["pageNoLimit"]?has_content>
                        <#assign allLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageNoLimit")>
                        <m-link href="${allLinkUrl.pathWithParams}" class="btn btn-default">${ec.getL10n().localize("Paginate")}</m-link>
                    <#else>
                        <#assign allLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageNoLimit", "true")>
                        <m-link href="${allLinkUrl.pathWithParams}" class="btn btn-default">${ec.getL10n().localize("Show All")}</m-link>
                    </#if>
                </#if>
                -->
                <#if formNode["@show-all-button"]! == "true" || formNode["@show-page-size"]! == "true">
                    <div class="btn-group">
                      <button type="button" class="btn btn-default dropdown-toggle" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
                        ${context[listName + "PageSize"]?c} <span class="caret"></span>
                      </button>
                      <ul class="dropdown-menu">
                        <#list [10,20,50,100,200,500] as curPageSize>
                            <#assign pageSizeUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageSize", curPageSize?c)>
                            <li><m-link href="${pageSizeUrl.pathWithParams}">${curPageSize?c}</m-link></li>
                        </#list>
                      </ul>
                    </div>
                </#if>

                <#assign curPageMaxIndex = context[listName + "PageMaxIndex"]>
                <#if (curPageMaxIndex > 4)><form-go-page id-val="${formId}" :max-index="${curPageMaxIndex?c}"></form-go-page></#if>
                <form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
                    <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
                    <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></form-paginate>
            </#if>

            <#if (context[listName + "Count"]!(context[listName].size())!0) == 0>
                <#if context.getSharedMap().get("_entityListNoSearchParms")!false == true>
                    <h4 class="text-warning" style="display:inline-block;padding-top:2px;">${ec.getL10n().localize("Find Options required to view results")}</h4>
                <#else>
                    <h4 class="text-warning" style="display:inline-block;padding-top:2px;">${ec.getL10n().localize("No results found")}</h4>
                </#if>
            </#if>
        </nav></th></tr>

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
                <form-link name="${headerFormId}_clr" id="${headerFormId}_clr" action="${curUrlInstance.path}">
                    <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                    <button id="${headerFormId}-quick-clear" type="submit" name="clearParameters" style="float:left; padding: 0 5px 0 5px; margin: 0 4px 0 0;" class="btn btn-primary btn-sm"><i class="fa fa-remove"></i></button>
                </form-link>
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
    <#assign formName = ec.getResource().expandNoL10n(formNode["@name"], "")>
    <#assign formId>${formName}<#if sectionEntryIndex?has_content>_${sectionEntryIndex}</#if></#assign>
    <#assign headerFormId = formId + "_header">
    <#assign skipStart = (formNode["@skip-start"]! == "true")>
    <#assign skipEnd = (formNode["@skip-end"]! == "true")>
    <#assign skipForm = (formNode["@skip-form"]! == "true")>
    <#assign skipHeader = !skipStart && (formNode["@skip-header"]! == "true")>
    <#assign needHeaderForm = !skipHeader && formListInfo.isHeaderForm()>
    <#assign isHeaderDialog = needHeaderForm && formNode["@header-dialog"]! == "true">
    <#assign isMulti = !skipForm && formNode["@multi"]! == "true">
    <#assign formListUrlInfo = sri.makeUrlByType(formNode["@transition"], "transition", null, "false")>
    <#assign formListUrlParms = formListUrlInfo.getParameterMap()>
    <#assign listName = formNode["@list"]>
    <#assign isServerStatic = formInstance.isServerStatic(sri.getRenderMode())>
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>

<#if isServerStatic><#-- client rendered, static -->
    <#if !skipHeader><@paginationHeaderModals formListInfo formId isHeaderDialog/></#if>
    <form-list name="${formName}" id="${formId}" rows="${formName}" action="${formListUrlInfo.path}" :multi="${isMulti?c}"<#rt>
            <#t> :skip-form="${skipForm?c}" :skip-header="${skipHeader?c}" :header-form="${needHeaderForm?c}"
            <#t> :header-dialog="${isHeaderDialog?c}" :saved-finds="${(formNode["@saved-finds"]! == "true")?c}"
            <#t> :select-columns="${(formNode["@select-columns"]! == "true")?c}" :all-button="${(formNode["@show-all-button"]! == "true")?c}"
            <#t> :csv-button="${(formNode["@show-csv-button"]! == "true")?c}" :text-button="${(formNode["@show-text-button"]! == "true")?c}"
            <#lt> :pdf-button="${(formNode["@show-pdf-button"]! == "true")?c}" columns="${numColumns}">
        <template slot="headerForm" slot-scope="header">
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#assign fieldsJsName = "header.search">
            <#assign hiddenFieldList = formListInfo.getListHeaderHiddenFieldList()>
            <#list hiddenFieldList as hiddenField><#recurse hiddenField["header-field"][0]/></#list>
            <#assign fieldsJsName = "">
        </template>
        <template slot="header" slot-scope="header">
            <#assign fieldsJsName = "header.search"><#assign ownerForm = headerFormId>
            <tr><#list mainColInfoList as columnFieldList>
                <th><#list columnFieldList as fieldNode>
                    <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                </#list></th>
            </#list></tr>
            <#if hasSubColumns>
                <tr><td colspan="${numColumns}" class="form-list-sub-row-cell"><div class="form-list-sub-rows"><table class="table table-striped table-hover table-condensed${tableStyle}"><thead>
                    <#list subColInfoList as subColFieldList><th>
                        <#list subColFieldList as fieldNode>
                            <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                        </#list>
                    </th></#list>
                </thead></table></div></td></tr>
            </#if>
            <#assign fieldsJsName = ""><#assign ownerForm = "">
        </template>
        <#-- for adding more to form-list nav bar <template slot="nav"></template> -->
        <template slot="rowForm" slot-scope="row">
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#assign fieldsJsName = "row.fields"><#assign ownerForm = formId>
            <#assign hiddenFieldList = formListInfo.getListHiddenFieldList()>
            <#list hiddenFieldList as hiddenField><@formListSubField hiddenField true false isMulti false/></#list>
            <#assign fieldsJsName = ""><#assign ownerForm = "">
        </template>
        <#-- TODO: add first-row, second-row, last-row forms and rows, here and in form-list Vue component; support add from first, second (or last?) row with add to client list and server submit -->
        <template slot="row" slot-scope="row">
            <#assign fieldsJsName = "row.fields"><#assign ownerForm = formId>
            <#list mainColInfoList as columnFieldList>
                <td><#list columnFieldList as fieldNode>
                    <@formListSubField fieldNode true false isMulti false/>
                </#list></td>
            </#list>
            <#assign fieldsJsName = ""><#assign ownerForm = "">
        </template>
    </form-list>
<#else><#-- server rendered, non-static -->
    <#assign listObject = formListInfo.getListObject(true)!>
    <#assign listHasContent = listObject?has_content>

    <#-- all form elements outside table element and referred to with input/etc.@form attribute for proper HTML -->
    <#if !(isMulti || skipForm) && listHasContent><#list listObject as listEntry>
        ${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
        <m-form name="${formId}_${listEntry_index}" id="${formId}_${listEntry_index}" action="${formListUrlInfo.path}">
            <input type="hidden" name="pageIndex" value="${pageIndex!"0"}">
            <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#assign listEntryIndex = listEntry_index>
            <#-- hidden fields -->
            <#assign hiddenFieldList = formListInfo.getListHiddenFieldList()>
            <#list hiddenFieldList as hiddenField><@formListSubField hiddenField true false isMulti false/></#list>
            <#assign listEntryIndex = "">
        </m-form>
        ${sri.endFormListRow()}
    </#list></#if>
    <#if !skipStart>
        <#if needHeaderForm && !isHeaderDialog>
            <#assign headerUrlInstance = sri.getCurrentScreenUrl()>
            <form-link name="${headerFormId}" id="${headerFormId}" action="${headerUrlInstance.path}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListHeaderHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["header-field"][0]/></#list>
            </form-link>
        </#if>
        <#if formListInfo.isFirstRowForm()>
            <#t>${sri.pushSingleFormMapContext(formNode["@map-first-row"]!"")}
            <#assign listEntryIndex = "first">
            <#assign firstUrlInstance = sri.makeUrlByType(formNode["@transition-first-row"], "transition", null, "false")>
            <m-form name="${formId}_first" id="${formId}_first" action="${firstUrlInstance.path}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListFirstRowHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["first-row-field"][0]/></#list>
            </m-form>
            <#assign listEntryIndex = "">
            <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if formListInfo.isSecondRowForm()>
            <#t>${sri.pushSingleFormMapContext(formNode["@map-second-row"]!"")}
            <#assign listEntryIndex = "second">
            <#assign secondUrlInstance = sri.makeUrlByType(formNode["@transition-second-row"], "transition", null, "false")>
            <m-form name="${formId}_second" id="${formId}_second" action="${secondUrlInstance.path}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListSecondRowHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["second-row-field"][0]/></#list>
            </m-form>
            <#assign listEntryIndex = "">
            <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if formListInfo.isLastRowForm()>
            <#t>${sri.pushSingleFormMapContext(formNode["@map-last-row"]!"")}
            <#assign listEntryIndex = "last">
            <#assign lastUrlInstance = sri.makeUrlByType(formNode["@transition-last-row"], "transition", null, "false")>
            <m-form name="${formId}_last" id="${formId}_last" action="${lastUrlInstance.path}">
                <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
                <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
                <#assign hiddenFieldList = formListInfo.getListLastRowHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><#recurse hiddenField["last-row-field"][0]/></#list>
            </m-form>
            <#assign listEntryIndex = "">
            <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
        </#if>
        <#if isMulti>
        <m-form name="${formId}" id="${formId}" action="${formListUrlInfo.path}">
            <input type="hidden" name="moquiFormName" value="${formName}">
            <input type="hidden" name="_isMulti" value="true">
            <input type="hidden" name="pageIndex" value="${pageIndex!"0"}">
            <#if orderByField?has_content><input type="hidden" name="orderByField" value="${orderByField}"></#if>
            <#list formListUrlParms.keySet() as parmName><#if !hiddenParameterMap.containsKey(parmName)>
                <input type="hidden" name="${parmName}" value="${formListUrlParms.get(parmName)!?html}">
            </#if></#list>
            <#list hiddenParameterKeys as hiddenParameterKey><input type="hidden" name="${hiddenParameterKey}" value="${hiddenParameterMap.get(hiddenParameterKey)!""}"></#list>
            <#if listHasContent><#list listObject as listEntry>
                <#assign listEntryIndex = listEntry_index>
                <#t>${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
                <#-- hidden fields -->
                <#assign hiddenFieldList = formListInfo.getListHiddenFieldList()>
                <#list hiddenFieldList as hiddenField><@formListSubField hiddenField true false isMulti false/></#list>
                <#t>${sri.endFormListRow()}
                <#assign listEntryIndex = "">
            </#list></#if>
        </m-form>
        </#if>

        <#if !skipHeader><@paginationHeaderModals formListInfo formId isHeaderDialog/></#if>
        <div class="table-scroll-wrapper"><table class="table table-striped table-hover table-condensed${tableStyle}" id="${formId}_table">
        <#if !skipHeader>
            <thead>
                <@paginationHeader formListInfo formId isHeaderDialog/>
                <#assign ownerForm = headerFormId>
                <tr>
                    <#list mainColInfoList as columnFieldList><th><#list columnFieldList as fieldNode>
                        <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                    </#list></th></#list>
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
                <#assign ownerForm = "">
            </thead>
        </#if>
        <#if !isServerStatic><tbody></#if>
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
        <#t>${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}
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
                    <#t>${sri.startFormListSubRow(formListInfo, subListEntry, subListEntry_index, subListEntry_has_next)}
                    <#list subColInfoList as subColFieldList><td>
                        <#list subColFieldList as fieldNode>
                            <@formListSubField fieldNode true false isMulti false/>
                        </#list>
                    </td></#list>
                    <#t>${sri.endFormListSubRow()}
                </tr></#list>
            </table></div></td><#-- note no /tr, let following blocks handle it -->
        </#if></#if>
        </tr>
        <#if !(isMulti || skipForm)><#assign ownerForm = ""></#if>
        <#t>${sri.endFormListRow()}
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
        <#assign ownerForm = formId>
        <#assign listEntryIndex = "">
        <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
    </#if>
    <#if !skipEnd>
        <#if isMulti && listHasContent>
            <tr><td colspan="${numColumns}">
                <#list formNode["field"] as fieldNode><@formListSubField fieldNode false false true true/></#list>
            </td></tr>
        </#if>
        <#-- footer pagination control -->
        <#if isPaginated?? && isPaginated>
            <tr class="form-list-nav-row"><th colspan="${numColumns}"><nav class="form-list-nav">
                <#if formNode["@show-all-button"]! == "true" || formNode["@show-page-size"]! == "true">
                    <div class="btn-group">
                      <button type="button" class="btn btn-default dropdown-toggle" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
                        ${context[listName + "PageSize"]?c} <span class="caret"></span>
                      </button>
                      <ul class="dropdown-menu">
                        <#list [10,20,50,100,200,500] as curPageSize>
                            <#assign pageSizeUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageSize", curPageSize?c)>
                            <li><m-link href="${pageSizeUrl.pathWithParams}">${curPageSize?c}</m-link></li>
                        </#list>
                      </ul>
                    </div>
                </#if>
                <#assign curPageMaxIndex = context[listName + "PageMaxIndex"]>
                <#if (curPageMaxIndex > 4)><form-go-page id-val="${formId}" :max-index="${curPageMaxIndex?c}"></form-go-page></#if>
                <form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
                    <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
                    <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></form-paginate>
            </nav></th></tr>
        </#if>
        <#if !isServerStatic></tbody></#if>
        <#assign ownerForm = "">
        </table></div>
    </#if>
    <#if formNode["@focus-field"]?has_content>
        <m-script>$("#${formId}_table").find('[name="${formNode["@focus-field"]}<#if isMulti && !formListInfo.hasFirstRow()>_0</#if>"][form="${formId}<#if formListInfo.hasFirstRow()>_first<#elseif !isMulti>_0</#if>"]').addClass('default-focus').focus();</m-script>
    </#if>
    <#if hasSubColumns><m-script>moqui.makeColumnsConsistent('${formId}_table');</m-script></#if>
</#if>
    <#if sri.doBoundaryComments()><!-- END   form-list[@name=${formName}] --></#if>
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
                <m-link href="${ascOrderByUrlInfo.pathWithParams}"<#if ascActive> class="active"</#if>><i class="fa fa-caret-up"></i></m-link>
                <m-link href="${descOrderByUrlInfo.pathWithParams}"<#if descActive> class="active"</#if>><i class="fa fa-caret-down"></i></m-link>
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
    <#assign tlId><@fieldId .node/></#assign>
    <#assign curName><@fieldName .node/></#assign>
    <#assign containerStyle = ec.getResource().expandNoL10n(.node["@container-style"]!, "")>
    <#list (options.keySet())! as key>
        <#assign allChecked = ec.getResource().expandNoL10n(.node["@all-checked"]!, "")>
        <#assign fullId = tlId>
        <#if (key_index > 0)><#assign fullId = tlId + "_" + key_index></#if>
        <span id="${fullId}"<#if containerStyle?has_content> class="${containerStyle}"</#if>><input type="checkbox" name="${curName}" value="${key?html}"<#if allChecked! == "true"> checked="checked"<#elseif currentValue?has_content && (currentValue==key || currentValue.contains(key))> checked="checked"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>><#if options.get(key)! != ""><span class="checkbox-label" onclick="$('#${fullId}').children('input[type=checkbox]').click()" style="cursor: default">${options.get(key)}</span></#if></span>
    </#list>
</#macro>

<#macro "date-find">
    <#if .node["@type"]! == "time"><#assign size=9><#assign maxlength=13><#assign defaultFormat="HH:mm">
    <#elseif .node["@type"]! == "date"><#assign size=10><#assign maxlength=10><#assign defaultFormat="yyyy-MM-dd">
    <#else><#assign size=16><#assign maxlength=23><#assign defaultFormat="yyyy-MM-dd HH:mm">
    </#if>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fieldValueFrom = ec.getL10n().format(ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!""), defaultFormat)>
    <#assign fieldValueThru = ec.getL10n().format(ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!""), defaultFormat)>
    <span class="form-date-find">
      <span>${ec.getL10n().localize("From")}&nbsp;</span>
      <date-time id="<@fieldId .node/>_from" name="${curFieldName}_from" value="${fieldValueFrom?html}" type="${.node["@type"]!""}" size="${.node["@size"]!""}"<#rt>
        <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"<#else> tooltip="From"</#if>
        <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>/>
    </span>
    <span class="form-date-find">
      <span>${ec.getL10n().localize("Thru")}&nbsp;</span>
      <date-time id="<@fieldId .node/>_thru" name="${curFieldName}_thru" value="${fieldValueThru?html}" type="${.node["@type"]!""}" size="${.node["@size"]!""}"<#rt>
          <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"<#else> tooltip="Thru"</#if>
          <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>/>
    </span>
</#macro>

<#macro "date-period">
    <#assign tlId><@fieldId .node/></#assign><#assign curFieldName><@fieldName .node/></#assign>
    <#assign fvOffset = ec.getContext().get(curFieldName + "_poffset")!>
    <#assign fvPeriod = ec.getContext().get(curFieldName + "_period")!?lower_case>
    <#assign fvDate = ec.getContext().get(curFieldName + "_pdate")!"">
    <#assign fvFromDate = ec.getContext().get(curFieldName + "_from")!"">
    <#assign fvThruDate = ec.getContext().get(curFieldName + "_thru")!"">
    <#assign allowEmpty = .node["@allow-empty"]!"true">
    <#if .node["@time"]! == "true"><#assign fromThruType = "date-time"><#else><#assign fromThruType = "date"></#if>
    <date-period name="${curFieldName}" id="${tlId}" :allow-empty="${allowEmpty}" offset="${fvOffset}" period="${fvPeriod}" from-thru-type="${fromThruType}"
         date="${fvDate}" from-date="${fvFromDate}" thru-date="${fvThruDate}" <#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>/>
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
    <#if !javaFormat?has_content>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign fieldValue = sri.getFieldValueString(dtFieldNode, .node["@default-value"]!"", javaFormat)>
    <#assign validationClasses = formInstance.getFieldValidationClasses(dtSubFieldNode)>
    <date-time id="<@fieldId .node/>" name="<@fieldName .node/>" value="${fieldValue?html}" type="${.node["@type"]!""}" size="${.node["@size"]!""}"<#rt>
        <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
        <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>
        <#t><#if validationClasses?contains("required")> required="required"</#if> auto-year="${.node["@auto-year"]!"true"}" :minuteStep="${.node["@minute-stepping"]!"5"}"/>
</#macro>

<#macro display>
    <#assign dispFieldId><@fieldId .node/></#assign>
    <#assign dispFieldName><@fieldName .node/></#assign>
    <#assign dispFieldNode = .node?parent?parent>
    <#assign dispAlign = dispFieldNode["@align"]!"left">
    <#assign dispHidden = (!.node["@also-hidden"]?has_content || .node["@also-hidden"] == "true") && !(skipForm!false)>
    <#assign dispDynamic = .node["@dynamic-transition"]?has_content>
    <#assign fieldValue = "">
    <#if fieldsJsName?has_content>
        <#assign format = .node["@format"]!>
        <#assign fieldValue>{{${fieldsJsName}.${dispFieldName} | format<#if format?has_content>("${format}")</#if>}}</#assign>
    <#else>
        <#if .node["@text"]?has_content>
            <#assign textMap = "">
            <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], "")!></#if>
            <#if textMap?has_content><#assign fieldValue = ec.getResource().expand(.node["@text"], "", textMap)>
                <#else><#assign fieldValue = ec.getResource().expand(.node["@text"], "")></#if>
            <#if .node["@currency-unit-field"]?has_content>
                <#assign fieldValue = ec.getL10n().formatCurrency(fieldValue, ec.getResource().expression(.node["@currency-unit-field"], ""))></#if>
        <#elseif .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.getL10n().formatCurrency(sri.getFieldValue(dispFieldNode, ""), ec.getResource().expression(.node["@currency-unit-field"], ""))>
        <#else>
            <#assign fieldValue = sri.getFieldValueString(.node)>
        </#if>
    </#if>
    <#if dispDynamic && !fieldValue?has_content><#assign fieldValue><@widgetTextValue .node true/></#assign></#if>
    <#t><span class="text-inline ${sri.getFieldValueClass(dispFieldNode)}<#if .node["@currency-unit-field"]?has_content> currency</#if><#if dispAlign == "center"> text-center<#elseif dispAlign == "right"> text-right</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if dispDynamic> id="${dispFieldId}_display"</#if>>
    <#t><#if fieldValue?has_content><#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html?replace("\n", "<br>")}</#if><#else>&nbsp;</#if>
    <#t></span>
    <#t><#if dispHidden>
        <#if dispDynamic>
            <#assign hiddenValue><@widgetTextValue .node true "value"/></#assign>
            <input type="hidden" id="${dispFieldId}" name="${dispFieldName}" value="${hiddenValue}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#else>
            <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
            <#-- don't default to fieldValue for the hidden input value, will only be different from the entry value if @text is used, and we don't want that in the hidden value -->
            <input type="hidden" id="${dispFieldId}" name="${dispFieldName}" <#if fieldsJsName?has_content>:value="${fieldsJsName}.${dispFieldName}"<#else>value="${sri.getFieldValuePlainString(dispFieldNode, "")?html}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        </#if>
    </#if>
    <#if dispDynamic>
        <#assign defUrlInfo = sri.makeUrlByType(.node["@dynamic-transition"], "transition", .node, "false")>
        <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
        <#assign depNodeList = .node["depends-on"]>
        <m-script>
            function populate_${dispFieldId}() {
                <#if .node["@depends-optional"]! != "true">
                    var hasAllParms = true;
                    <#list depNodeList as depNode>if (!$('#<@fieldIdByName depNode["@field"]/>').val()) { hasAllParms = false; } </#list>
                    if (!hasAllParms) { <#-- alert("not has all parms"); --> return; }
                </#if>
                $.ajax({ type:"POST", url:"${defUrlInfo.url}", data:{ moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                    <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local depNodeParm = depNode["@parameter"]!depNodeField><#local _void = defUrlParameterMap.remove(depNodeParm)!>, "${depNodeParm}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                    <#t><#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${defUrlParameterMap.get(parameterKey)}"</#if></#list>
                    <#t>}, dataType:"text" }).done(function(defaultText) { if (defaultText && defaultText.length) {
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
            <#-- <#if !fieldValue?has_content>populate_${dispFieldId}();</#if> -->
        </m-script>
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
    <#assign tlId><@fieldId .node/></#assign>
    <#assign allowMultiple = ec.getResource().expandNoL10n(.node["@allow-multiple"]!, "") == "true">
    <#assign allowEmpty = ec.getResource().expandNoL10n(.node["@allow-empty"]!, "") == "true">
    <#assign isDynamicOptions = .node["dynamic-options"]?has_content>
    <#assign name><@fieldName .node/></#assign>
    <#assign namePlain = ddFieldNode["@name"]>
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValuePlainString(ddFieldNode, "")>
    <#if !currentValue?has_content && .node["@no-current-selected-key"]?has_content>
        <#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"], "")></#if>
    <#if currentValue?starts_with("[")><#assign currentValue = currentValue?substring(1, currentValue?length - 1)?replace(" ", "")></#if>
    <#assign currentValueList = (currentValue?split(","))!>
    <#if currentValueList?has_content><#if allowMultiple><#assign currentValue=""><#else><#assign currentValue = currentValueList[0]></#if></#if>
    <#if !allowMultiple && !allowEmpty && !currentValue?has_content && options?has_content><#assign currentValue = options.keySet()?first></#if>
    <#-- for server-side dynamic options/etc if no currentValue get first in options and set in context so fields rendering after this have it available -->
    <#if currentValue?has_content && !ec.context.get(namePlain)?has_content && _formMap?exists && !_formMap.get(namePlain)?has_content>
        <#assign _void = _formMap.put(ddFieldNode["@name"], currentValue)!></#if>
    <#assign currentDescription = (options.get(currentValue))!>
    <#assign validationClasses = formInstance.getFieldValidationClasses(ddSubFieldNode)>
    <#assign optionsHasCurrent = currentDescription?has_content>
    <#if !optionsHasCurrent && .node["@current-description"]?has_content>
        <#assign currentDescription = ec.getResource().expand(.node["@current-description"], "")></#if>
    <#if isDynamicOptions>
        <#assign doNode = .node["dynamic-options"][0]>
        <#assign depNodeList = doNode["depends-on"]>
        <#assign doUrlInfo = sri.makeUrlByType(doNode["@transition"], "transition", doNode, "false")>
        <#assign doUrlParameterMap = doUrlInfo.getParameterMap()>
        <#if currentValue?has_content && !currentDescription?has_content><#assign currentDescription><@widgetTextValue .node true/></#assign></#if>
    </#if>
    <drop-down name="${name}" id="${tlId}" class="<#if isDynamicOptions> dynamic-options</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if><#if validationClasses?has_content> ${validationClasses}</#if>"<#rt>
            <#t><#if allowMultiple> multiple="multiple"</#if><#if allowEmpty> :allow-empty="true"</#if><#if .node["@combo-box"]! == "true"> :combo="true"</#if>
            <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if .node["@size"]?has_content> size="${.node["@size"]}"</#if>
            <#t><#if allowMultiple> :value="[<#list currentValueList as curVal><#if curVal?has_content>'${curVal}',</#if></#list>]"<#else> value="${currentValue!}"</#if>
            <#if isDynamicOptions> options-url="${doUrlInfo.url}" value-field="${doNode["@value-field"]!"value"}" label-field="${doNode["@label-field"]!"label"}"<#if doNode["@depends-optional"]! == "true"> :depends-optional="true"</#if>
                <#t> :depends-on="{<#list depNodeList as depNode><#local depNodeField = depNode["@field"]>'${depNode["@parameter"]!depNodeField}':'<@fieldIdByName depNodeField/>'<#sep>, </#list>}"
                <#t> :options-parameters="{<#list doUrlParameterMap.keySet() as parameterKey><#if doUrlParameterMap.get(parameterKey)?has_content>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(parameterKey)}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(doUrlParameterMap.get(parameterKey))}', </#if></#list>}"
                <#t><#if doNode["@server-search"]! == "true"> :server-search="true"</#if><#if doNode["@delay"]?has_content> :server-delay="${doNode["@delay"]}"</#if>
                <#t><#if doNode["@min-length"]?has_content> :server-min-length="${doNode["@min-length"]}"</#if>
                <#t><#if (.node?children?size > 1)> :options-load-init="true"</#if>
            <#t></#if>
                :options="[<#if currentValue?has_content && !allowMultiple && !optionsHasCurrent>{id:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentValue)}',text:'<#if currentDescription?has_content>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentDescription!)}<#else>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentValue)}</#if>'},</#if><#rt>
                    <#t><#list (options.keySet())! as key>{id:'<#if key?has_content>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(key)}<#else>\u00a0</#if>',text:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(options.get(key)!)}'}<#sep>,</#list>]"
            <#lt>>
            <#-- support <#if .node["@current"]! == "first-in-list"> again? -->
    </drop-down>
    <#if ec.getResource().expandNoL10n(.node["@show-not"]!, "") == "true"><span><input type="checkbox" class="form-control" name="${name}_not" value="Y"<#if ec.getWeb().parameters.get(name + "_not")! == "Y"> checked="checked"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${ec.getL10n().localize("Not")}</span></#if>
    <#-- <span>[${currentValue}]; <#list currentValueList as curValue>[${curValue!''}], </#list></span> -->
    <#if allowMultiple><input type="hidden" id="${tlId}_op" name="${name}_op" value="in"></#if>
</#macro>

<#macro file><input type="file" class="form-control" name="<@fieldName .node/>"<#if .node.@multiple! == "true"> multiple</#if><#if .node.@accept?has_content> accept="${.node.@accept}"</#if> value="${sri.getFieldValueString(.node)?html}" size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></#macro>

<#macro hidden>
    <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
    <#assign tlId><@fieldId .node/></#assign>
    <input type="hidden" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(.node?parent?parent, .node["@default-value"]!"")?html}" id="${tlId}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
</#macro>

<#macro ignored><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>

<#macro password>
    <#assign validationClasses = formInstance.getFieldValidationClasses(.node?parent)>
    <input type="password" name="<@fieldName .node/>" id="<@fieldId .node/>" class="form-control<#if validationClasses?has_content> ${validationClasses}</#if>" size="${.node.@size!"25"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if validationClasses?contains("required")> required</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</#macro>

<#macro radio>
    <#assign options = sri.getFieldOptions(.node)/>
    <#assign currentValue = sri.getFieldValueString(.node)/>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")/></#if>
    <#assign tlId><@fieldId .node/></#assign>
    <#assign curName><@fieldName .node/></#assign>
    <#list (options.keySet())! as key>
        <span id="${tlId}<#if (key_index > 0)>_${key_index}</#if>"><input type="radio" name="${curName}" value="${key?html}"<#if currentValue?has_content && currentValue==key> checked="checked"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>&nbsp;${options.get(key)!""}</span>
    </#list>
</#macro>

<#macro "range-find">
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign tlId><@fieldId .node/></#assign>
<span class="form-range-find">
    <span>${ec.getL10n().localize("From")}&nbsp;</span><input type="text" class="form-control" name="${curFieldName}_from" value="${ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!"")?html}" size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="${tlId}_from"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</span>
<span class="form-range-find">
    <span>${ec.getL10n().localize("Thru")}&nbsp;</span><input type="text" class="form-control" name="${curFieldName}_thru" value="${ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!"")?html}" size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="${tlId}_thru"<#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
</span>
</#macro>

<#macro reset><input type="reset" name="<@fieldName .node/>" value="<@fieldTitle .node?parent/>" id="<@fieldId .node/>"<#if .node["@icon"]?has_content> iconcls="ui-icon-${.node["@icon"]}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></#macro>

<#macro submit>
    <#assign confirmationMessage = ec.getResource().expand(.node["@confirmation"]!, "")/>
    <#assign buttonText><#if .node["@text"]?has_content>${ec.getResource().expand(.node["@text"], "")}<#else><@fieldTitle .node?parent/></#if></#assign>
    <#assign iconClass = .node["@icon"]!>
    <#if !iconClass?has_content><#assign iconClass = sri.getThemeIconClass(buttonText)!></#if>
    <button type="submit" name="<@fieldName .node/>" value="<@fieldName .node/>" id="<@fieldId .node/>"<#rt>
            <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}');"</#if>
            <#t><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
            <#t> class="btn btn-${ec.getResource().expandNoL10n(.node["@type"]!"primary", "")} btn-sm"
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if>><#if iconClass?has_content><i class="${iconClass}"></i> </#if>
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
    <textarea v-pre class="form-control" name="<@fieldName .node/>" id="${textAreaId}" <#if .node["@cols"]?has_content>cols="${.node["@cols"]}"<#else>style="width:100%;"</#if>
        rows="${.node["@rows"]!"3"}"<#if .node["@read-only"]! == "true"> readonly="readonly"</#if><#if .node["@maxlength"]?has_content> maxlength="${.node["@maxlength"]}"</#if>
        <#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>><#rt>
${sri.getFieldValueString(.node)?html}</textarea>
    <#if editorType == "html">
        <#-- support CSS from editorScreenThemeId with resourceTypeEnumId=STRT_STYLESHEET, like: contentsCss:['/css/mysitestyles.css','/css/anotherfile.css']; -->
        <#-- see: https://ckeditor.com/docs/ckeditor4/latest/api/CKEDITOR_config.html#cfg-contentsCss -->
        <#assign editorScreenThemeId = ec.getResource().expand(.node["@editor-theme"]!"", "")>
        <#assign editorThemeCssList = sri.getThemeValues("STRT_STYLESHEET", editorScreenThemeId)>
        <m-script src="https://cdn.ckeditor.com/4.14.1/standard-all/ckeditor.js" type="text/javascript"></m-script>
        <m-script>
        CKEDITOR.dtd.$removeEmpty['i'] = false;
        CKEDITOR.replace('${textAreaId}', { customConfig:'',<#if editorThemeCssList?has_content>contentsCss:[<#list editorThemeCssList as themeCss>'${themeCss}'<#sep>,</#list>],</#if>
            allowedContent:true, linkJavaScriptLinksAllowed:true, fillEmptyBlocks:false,
            extraAllowedContent:'p(*)[*]{*};div(*)[*]{*};li(*)[*]{*};ul(*)[*]{*};i(*)[*]{*};span(*)[*]{*}',
            width:'100%', height:'600px', removeButtons:'Image,Save,NewPage,Preview' }).on('change', function(evt) { this.updateElement(); });
        </m-script>
    <#elseif editorType == "md">
        <m-stylesheet href="https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.css"></m-stylesheet>
        <m-script src="https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.js" type="text/javascript"></m-script>
        <m-script>new SimpleMDE({ element: document.getElementById("${textAreaId}"), indentWithTabs:false, autoDownloadFontAwesome:false, autofocus:true, spellChecker:false, forceSync:true });</m-script>
    </#if>
</#macro>

<#macro "text-line">
    <#assign tlSubFieldNode = .node?parent>
    <#assign tlFieldNode = tlSubFieldNode?parent>
    <#assign tlId><@fieldId .node/></#assign>
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
        <#assign depNodeList = .node["depends-on"]>
        <text-autocomplete id="${tlId}" name="${name}" url="${acUrlInfo.url}" value="${fieldValue?html}" value-text="${valueText?html}"<#rt>
                <#t> type="${inputType}" size="${.node.@size!"30"}"
                <#t><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
                <#t><#if ec.getResource().condition(.node.@disabled!"false", "")> :disabled="true"</#if>
                <#t><#if validationClasses?has_content> validation-classes="${validationClasses}"</#if>
                <#t><#if validationClasses?contains("required")> :required="true"</#if>
                <#t><#if regexpInfo?has_content> pattern="${regexpInfo.regexp}" data-msg-pattern="${regexpInfo.message!"Invalid format"}"</#if>
                <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
                <#t><#if ownerForm?has_content> form="${ownerForm}"</#if>
                <#t><#if .node["@ac-min-length"]?has_content> :min-length="${.node["@ac-min-length"]}"</#if>
                <#t> :depends-on="{<#list depNodeList as depNode><#local depNodeField = depNode["@field"]>'${depNode["@parameter"]!depNodeField}':'<@fieldIdByName depNodeField/>'<#sep>, </#list>}"
                <#t> :ac-parameters="{<#list acUrlParameterMap.keySet() as parameterKey><#if acUrlParameterMap.get(parameterKey)?has_content>'${parameterKey}':'${acUrlParameterMap.get(parameterKey)}', </#if></#list>}"
                <#t><#if .node["@ac-delay"]?has_content> :delay="${.node["@ac-delay"]}"</#if>
                <#t><#if .node["@ac-initial-text"]?has_content> :skip-initial="true"</#if>/>
    <#else>
        <#assign tlAlign = tlFieldNode["@align"]!"left">
        <#t><input id="${tlId}" <#--v-model="fields.${name}"--> type="${inputType}" autocapitalize="off" autocomplete="off"
            <#t> name="${name}" <#if fieldValue?html == fieldValue>value="${fieldValue}"<#else>:value="'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(fieldValue)}'"</#if>
            <#t> <#if .node.@size?has_content>size="${.node.@size}"<#else>style="width:100%;"</#if><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
            <#t><#if ec.getResource().condition(.node.@disabled!"false", "")> disabled="disabled"</#if>
            <#t> class="form-control<#if validationClasses?has_content> ${validationClasses}</#if><#if tlAlign == "center"> text-center<#elseif tlAlign == "right"> text-right</#if>"
            <#t><#if validationClasses?contains("required")> required</#if><#if regexpInfo?has_content> pattern="${regexpInfo.regexp}" data-msg-pattern="${regexpInfo.message!"Invalid format"}"</#if>
            <#t><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#assign expandedMask = ec.getResource().expandNoL10n(.node["@mask"], "")!>
        <#if expandedMask?has_content><m-script>$('#${tlId}').inputmask("${expandedMask}");</m-script></#if>
        <#if .node["@default-transition"]?has_content>
            <#assign defUrlInfo = sri.makeUrlByType(.node["@default-transition"], "transition", .node, "false")>
            <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
            <#assign depNodeList = .node["depends-on"]>
            <m-script>
                function populate_${tlId}() {
                    // if ($('#${tlId}').val()) return;
                    var hasAllParms = true;
                    <#list depNodeList as depNode>if (!$('#<@fieldIdByName depNode["@field"]/>').val()) { hasAllParms = false; } </#list>
                    if (!hasAllParms) { <#-- alert("not has all parms"); --> return; }
                    $.ajax({ type:"POST", url:"${defUrlInfo.url}", data:{ moquiSessionToken: "${(ec.getWeb().sessionToken)!}"<#rt>
                            <#t><#list depNodeList as depNode><#local depNodeField = depNode["@field"]><#local _void = defUrlParameterMap.remove(depNodeField)!>, "${depNode["@parameter"]!depNodeField}": $("#<@fieldIdByName depNodeField/>").val()</#list>
                            <#t><#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>, "${parameterKey}":"${defUrlParameterMap.get(parameterKey)}"</#if></#list>
                            <#t>}, dataType:"text", success:function(defaultText) {   $('#${tlId}').val(defaultText);  } });
                }
                <#list depNodeList as depNode>
                $("#<@fieldIdByName depNode["@field"]/>").on('change', function() { populate_${tlId}(); });
                </#list>
                populate_${tlId}();
            </m-script>
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
    <#-- NOTE: noDisplay used to include: "display", "display-entity", -->
    <#assign noDisplay = ["hidden", "ignored", "password", "reset", "submit", "text-area", "link", "label"]>
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
    <#elseif widgetType == "display-entity">
        <#assign fieldValue = sri.getFieldEntityValue(widgetNode)!"">
        <#t><#if fieldValue?has_content>${fieldValue}</#if>
    <#else>
        <#-- handles text-find, ... -->
        <#t>${sri.getFieldValueString(widgetNode)}
    </#if><#t>
</#macro>
