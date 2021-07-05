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
<#macro getQuasarColor bootstrapColor><#if bootstrapColor == "success">positive<#elseif bootstrapColor == "danger">negative<#elseif bootstrapColor == "default"><#else>${bootstrapColor}</#if></#macro>
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
    <#else><m-subscreens-tabs></m-subscreens-tabs></#if>
</#if></#macro>
<#macro "subscreens-active"><m-subscreens-active></m-subscreens-active></#macro>
<#macro "subscreens-panel">
    <#if .node["@type"]! == "popup"><#-- NOTE: popup menus no longer handled here, how handled dynamically in navbar.html.ftl -->
        <m-subscreens-active></m-subscreens-active>
    <#elseif .node["@type"]! == "stack"><h1>LATER stack type subscreens-panel not yet supported.</h1>
    <#elseif .node["@type"]! == "wizard"><h1>LATER wizard type subscreens-panel not yet supported.</h1>
    <#else><#-- default to type=tab -->
        <div<#if .node["@id"]?has_content> id="${.node["@id"]}-tabpanel"</#if>>
            <m-subscreens-tabs></m-subscreens-tabs>
            <m-subscreens-active></m-subscreens-active>
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
        <m-form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
            <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
            <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></m-form-paginate>
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
    <m-container-box<#if contBoxDivId?has_content> id="${contBoxDivId}"</#if> type="${boxType}"<#if boxHeader??> title="${ec.getResource().expand(boxHeader["@title"]!"", "")?html}"</#if> :initial-open="<#if ec.getResource().expandNoL10n(.node["@initial"]!, "") == "closed">false<#else>true</#if>">
        <#-- NOTE: direct use of the m-container-box component would not use template elements but rather use the 'slot' attribute directly on the child elements which we can't do here -->
        <#if boxHeader??><template slot="header"><#recurse boxHeader></template></#if>
        <#if .node["box-toolbar"]?has_content><template slot="toolbar"><#recurse .node["box-toolbar"][0]></template></#if>
        <#if .node["box-body"]?has_content><m-box-body<#if .node["box-body"][0]["@height"]?has_content> height="${.node["box-body"][0]["@height"]}"</#if>><#recurse .node["box-body"][0]></m-box-body></#if>
        <#if .node["box-body-nopad"]?has_content><#recurse .node["box-body-nopad"][0]></#if>
    </m-container-box>
</#macro>
<#macro "container-row">
    <#assign contRowDivId><@nodeId .node/></#assign>
    <#-- was using q-col-gutter-md but causes overlap between rows as uses negative + positive margin approach, one example is Assets link on PopCommerceAdmin/dashboard.xml that gets covered by col in row below it -->
    <#-- Quasar: xs:<600 sm:600+ md:1024+ lg:1440+ xl:1920+ ==== Bootstrap3: xs:<768 sm:768+ md:992+ lg:1200+ -->
    <div class="row<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"<#if contRowDivId?has_content> id="${contRowDivId}"</#if>>
        <#list .node["row-col"] as rowColNode>
            <#assign colHasLg = rowColNode["@lg"]?has_content>
            <#assign colHasMd = rowColNode["@md"]?has_content>
            <#assign colHasSm = rowColNode["@sm"]?has_content>
            <div class="q-px-xs <#if colHasLg> col-lg-${rowColNode["@lg"]}</#if><#if colHasMd> col-md-${rowColNode["@md"]}<#elseif colHasLg> col-md-12</#if><#if colHasSm> col-sm-${rowColNode["@sm"]}<#elseif colHasLg || colHasMd> col-sm-12</#if><#if rowColNode["@xs"]?has_content> col-xs-${rowColNode["@xs"]}<#elseif colHasLg || colHasMd || colHasSm> col-xs-12</#if><#if rowColNode["@style"]?has_content> ${ec.getResource().expandNoL10n(rowColNode["@style"], "")}</#if>">
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
    <#-- TODO: somehow support at least fa icons backward compatible? won't be doing glyphicons anyway -->
    <#assign iconClass = "">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign title = ec.getResource().expand(.node["@title"], "")>
        <#if !title?has_content><#assign title = buttonText></#if>
        <#assign cdDivId><@nodeId .node/></#assign>
        <m-container-dialog id="${cdDivId}" color="<@getQuasarColor ec.getResource().expandNoL10n(.node["@type"]!"primary", "")/>" width="${.node["@width"]!""}"
                button-text="${buttonText}" button-class="${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}" title="${title}"<#if _openDialog! == cdDivId> :openDialog="true"</#if>>
            <#recurse>
        </m-container-dialog>
    </#if>
</#macro>
<#macro "dynamic-container">
    <#assign dcDivId><@nodeId .node/></#assign>
    <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true").addParameter("_dynamic_container_id", dcDivId)>
    <m-dynamic-container id="${dcDivId}" url="${urlInstance.passThroughSpecialParameters().pathWithParams}"></m-dynamic-container>
</#macro>
<#macro "dynamic-dialog">
    <#assign iconClass = "fa fa-share">
    <#if .node["@icon"]?has_content><#assign iconClass = .node["@icon"]></#if>
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign buttonText = ec.getResource().expand(.node["@button-text"], "")>
        <#assign title = ec.getResource().expand(.node["@title"], "")>
        <#if !title?has_content><#assign title = buttonText></#if>
        <#assign urlInstance = sri.makeUrlByType(.node["@transition"], "transition", .node, "true")>
        <#assign ddDivId><@nodeId .node/></#assign>
        <#if urlInstance.disableLink>
            <q-btn disabled dense outline no-caps icon="open_in_new" label="${buttonText}" color="<@getQuasarColor ec.getResource().expandNoL10n(.node["@type"]!"primary", "")/>" class="${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}"></q-btn>
        <#else>
            <m-dynamic-dialog id="${ddDivId}" url="${urlInstance.urlWithParams}" color="<@getQuasarColor ec.getResource().expandNoL10n(.node["@type"]!"primary", "")/>" width="${.node["@width"]!""}"
                    button-text="${buttonText}" button-class="${ec.getResource().expandNoL10n(.node["@button-style"]!"", "")}" title="${title}"<#if _openDialog! == ddDivId> :openDialog="true"</#if>></m-dynamic-dialog>
        </#if>
        <#-- used to use afterFormText for m-dynamic-dialog inside another form, needed now?
        <#assign afterFormText>
        <m-dynamic-dialog id="${ddDivId}" url="${urlInstance.urlWithParams}" width="${.node["@width"]!""}" button-text="${buttonText}" title="${buttonText}"<#if _openDialog! == ddDivId> :openDialog="true"</#if>></m-dynamic-dialog>
        </#assign>
        <#t>${sri.appendToAfterScreenWriter(afterFormText)}
        -->
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
    <m-tree-top id="${.node["@name"]}" items="${itemsUrl}" open-path="${ec.getResource().expandNoL10n(.node["@open-path"], "")}"
              :parameters="{<#list ajaxParms.keySet() as pKey>'${pKey}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(ajaxParms.get(pKey)!"")}'<#sep>,</#list>}"></m-tree-top>
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

    <#assign labelWrapper = linkNode["@link-type"]! == "anchor" && linkNode?ancestors("form-single")?has_content>
    <#if labelWrapper>
        <#assign fieldLabel><@fieldTitle linkNode?parent/></#assign>
        <q-field dense outlined readonly<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if>><template v-slot:control>
    </#if>

    <#if urlInstance.disableLink>
        <span>
            <q-btn dense no-caps disabled <#if linkNode["@link-type"]! != "anchor" && linkNode["@link-type"]! != "hidden-form-link">outline<#else>flat</#if><#rt>
                    <#t> class="m-link<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"
                    <#t><#if linkFormId?has_content> id="${linkFormId}"</#if><#if linkText?has_content> label="${linkText}"</#if>>
                <#if iconClass?has_content><i class="${iconClass}"></i></#if><#if linkNode["image"]?has_content><#visit linkNode["image"][0]></#if>
            </q-btn>
            <#t><#if linkNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(linkNode["@tooltip"], "")}</q-tooltip></#if>
        </span>
    <#else>
        <#assign confirmationMessage = ec.getResource().expand(linkNode["@confirmation"]!, "")/>
        <#if sri.isAnchorLink(linkNode, urlInstance)>
            <#assign linkNoParam = linkNode["@url-noparam"]! == "true">
            <#if urlInstance.isScreenUrl()><#assign linkElement = "m-link">
                <#if linkNoParam><#assign urlText = urlInstance.path/><#else><#assign urlText = urlInstance.pathWithParams/></#if>
            <#else><#assign linkElement = "a">
                <#if linkNoParam><#assign urlText = urlInstance.url/><#else><#assign urlText = urlInstance.urlWithParams/></#if>
            </#if>
            <#-- TODO: consider simplifying to use q-btn with 'to' attribute instead of m-link or for anchor type="a" + href, where we want a button (not @link-type=anchor) -->
            <${linkElement} href="${urlText}"<#if linkFormId?has_content> id="${linkFormId}"</#if><#rt>
                <#t><#if linkNode["@target-window"]?has_content> target="${linkNode["@target-window"]}"</#if>
                <#t><#if linkNode["@dynamic-load-id"]?has_content> load-id="${linkNode["@dynamic-load-id"]}"</#if>
                <#t><#if confirmationMessage?has_content><#if linkElement == "m-link"> :confirmation="'${confirmationMessage?js_string}'"<#else> onclick="return confirm('${confirmationMessage?js_string}')"</#if></#if>
                <#-- TODO non q-btn approach might simulate styles like old stuff, initial attempt failed though: <#if linkNode["@link-type"]! != "anchor">btn btn-${linkNode["@btn-type"]!"primary"} btn-sm</#if> -->
                <#if linkNode["@link-type"]! != "anchor">
                    <#t>>
                    <q-btn dense outline no-caps color="<@getQuasarColor linkNode["@btn-type"]!"primary"/>"<#rt>
                        <#t> class="m-link<#if linkNode["@style"]?has_content> ${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>">
                <#else>
                    <#t> class="<#if linkNode["@style"]?has_content> ${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>">
                </#if>
                <#t><#if linkNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(linkNode["@tooltip"], "")}</q-tooltip></#if>
                <#t><#if iconClass?has_content><i class="${iconClass} q-icon<#if linkText?? && linkText?trim?has_content> on-left</#if>"></i> </#if><#rt>
                <#t><#if linkNode["image"]?has_content><#visit linkNode["image"][0]><#else>${linkText}</#if>
                <#t><#if badgeMessage?has_content> <q-badge class="on-right" transparent>${badgeMessage}</q-badge></#if>
                <#if linkNode["@link-type"]! != "anchor"></q-btn></#if>
            <#t></${linkElement}>
        <#else>
            <#if linkFormId?has_content>
            <#rt><q-btn dense outline no-caps type="submit" form="${linkFormId}" id="${linkFormId}_button" color="<@getQuasarColor linkNode["@btn-type"]!"primary"/>"
                    <#t> class="<#if linkNode["@style"]?has_content>${ec.getResource().expandNoL10n(linkNode["@style"], "")}</#if>"
                    <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if>>
                    <#t><#if linkNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(linkNode["@tooltip"], "")}</q-tooltip></#if>
                <#t><#if iconClass?has_content><i class="${iconClass} q-icon<#if linkText?? && linkText?trim?has_content> on-left</#if>"></i> </#if>
                <#if linkNode["image"]?has_content>
                    <#t><img src="${sri.makeUrlByType(imageNode["@url"],imageNode["@url-type"]!"content",null,"true")}"<#if imageNode["@alt"]?has_content> alt="${imageNode["@alt"]}"</#if>/>
                <#else>
                    <#t>${linkText}
                </#if>
                <#t><#if badgeMessage?has_content> <q-badge class="on-right" transparent>${badgeMessage}</q-badge></#if>
            <#t></q-btn>
            </#if>
        </#if>
    </#if>

    <#if labelWrapper>
        </template></q-field>
    </#if>
</#macro>
<#macro linkFormForm linkNode linkFormId linkText urlInstance>
    <#if !urlInstance.disableLink && !sri.isAnchorLink(linkNode, urlInstance)>
        <#if urlInstance.getTargetTransition()?has_content><#assign linkFormType = "m-form"><#else><#assign linkFormType = "m-form-link"></#if>
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
                    <#rt><q-btn dense no-caps type="submit" <#if linkNode["@link-type"]! == "hidden-form-link">flat<#else>outline</#if>
                            <#t> color="<@getQuasarColor linkNode["@btn-type"]!"primary"/>" class="m-link<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>"
                            <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}')"</#if>>
                        <#t><#if linkNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(linkNode["@tooltip"], "")}</q-tooltip></#if>
                        <#t><#if iconClass?has_content><i class="${iconClass} q-icon<#if linkText?? && linkText?trim?has_content> on-left</#if>"></i> </#if>${linkText}
                        <#t><#if badgeMessage?has_content> <q-badge class="on-right" transparent>${badgeMessage}</q-badge></#if>
                    <#t></q-btn>
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

        <#-- NOTE: do not use auto-close or v-close-popup because it closes dialogs as well as the menu! -->
        <q-btn-dropdown dense outline no-caps color="<@getQuasarColor .node["@btn-type"]!"primary"/>"<#rt>
                <#lt><#if .node["@style"]?has_content> class="${ec.getResource().expandNoL10n(.node["@style"], "")}"</#if>>
            <template v-slot:label>
                <#if .node["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node["@tooltip"], "")}</q-tooltip></#if>
                <#if iconClass?has_content><i class="${iconClass} q-icon<#if linkText?? && linkText?trim?has_content> on-left</#if>"></i></#if>
                <#if .node["image"]?has_content><#visit .node["image"][0]><#else>${linkText}</#if>
                <#if badgeMessage?has_content><q-badge class="on-right" transparent>${badgeMessage}</q-badge></#if>
            </template>

            <q-list>
            <#list .node?children as childNode>
                <q-item><q-item-section>
                    <#visit childNode>
                </q-item-section></q-item>
            </#list>
            </q-list>
        </q-btn-dropdown>
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
        <#if urlInstance.getTargetTransition()?has_content><#assign formSingleType = "m-form"><#else><#assign formSingleType = "m-form-link"></#if>
        <#assign fieldsJsName = "formProps.fields">
    <#else>
        <#assign formSingleType = "m-form">
        <#assign fieldsJsName = "formProps.fields">
    </#if>
    <#assign formDisabled = urlInstance.disableLink>

    <#-- TODO: handle disabled forms, for Quasar looks like will need to disable each field, maybe with a property on m-form and m-form-link (and something else for plain form?) -->
    <#--  disabled="disabled"</#if> <#if urlInstance.disableLink> :disabled="true"</#if> -->
    <#if !skipStart>
    <div class="q-my-md"><${formSingleType} name="${formSingleId}" id="${formSingleId}" action="${urlInstance.path}"<#if formSingleNode["@focus-field"]?has_content> focus-field="${formSingleNode["@focus-field"]}"</#if><#rt>
            <#t><#if formSingleNode["@body-parameters"]?has_content> :body-parameter-names="[<#list formSingleNode["@body-parameters"]?split(",") as bodyParm>'${bodyParm}'<#sep>,</#list>]"</#if>
            <#t><#if formSingleNode["@background-message"]?has_content> submit-message="${formSingleNode["@background-message"]?html}"</#if>
            <#t><#if formSingleNode["@background-reload-id"]?has_content> submit-reload-id="${formSingleNode["@background-reload-id"]}"</#if>
            <#t><#if formSingleNode["@background-hide-id"]?has_content> submit-hide-id="${formSingleNode["@background-hide-id"]}"</#if>
            <#t><#if formSingleNode["@exclude-empty-fields"]! == "true"> :exclude-empty-fields="true"</#if>
            <#lt><#if _formListSelectedForm!false> :parentCheckboxSet="formProps"</#if>
            <#if fieldsJsName?has_content> v-slot:default="formProps" :fields-initial="${Static["org.moqui.util.WebUtilities"].fieldValuesEncodeHtmlJsSafe(sri.getFormFieldValues(formSingleNode))}"</#if>>
    </#if>
    <#if formSingleNode["field-layout"]?has_content>
        <#recurse formSingleNode["field-layout"][0]/>
    <#else>
        <#list formSingleNode["field"] as fieldNode><@formSingleSubField fieldNode formSingleId/></#list>
    </#if>
    <#if !skipEnd></${formSingleType}></div></#if>
    <#t>${sri.popContext()}<#-- context was pushed for the form-single so pop here at the end -->
    <#if sri.doBoundaryComments()><!-- END   form-single[@name=${.node["@name"]}] --></#if>
    <#assign ownerForm = ""><#-- clear ownerForm so later form fields don't pick it up -->
    <#assign formSingleId = "">
    <#assign fieldsJsName = "">
    <#assign formDisabled = false>
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
    <div class="q-my-sm big-row"<#if .node["@justify"]?has_content> style="justify-content:${.node["@justify"]};"</#if>>
    <#if .node["@title"]?has_content>
        <div class="q-mx-sm q-my-auto big-row-item">${ec.getResource().expand(.node["@title"], "")}</div>
    </#if>
        ${rowContent}
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
        <div class="q-mx-sm q-my-auto big-row-item">
    <#else>
        <div class="q-ma-sm <#if containerStyle?has_content> ${containerStyle}</#if>">
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
        </div><!-- /big-row-item -->
    <#else>
        </div>
    </#if>
</#macro>

<#-- =========================================================== -->
<#-- ======================= Form List ========================= -->
<#-- =========================================================== -->

<#macro paginationHeader formListInfo formId isHeaderDialog>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign listName = formNode["@list"]>
    <#assign allColInfoList = formListInfo.getAllColInfo()>
    <#assign mainColInfoList = formListInfo.getMainColInfo()>
    <#assign numColumns = (mainColInfoList?size)!100>
    <#if numColumns == 0><#assign numColumns = 100></#if>
    <#if isRowSelection!false><#assign numColumns = numColumns + 1></#if>
    <#assign isSavedFinds = formNode["@saved-finds"]! == "true">
    <#assign isSelectColumns = formNode["@select-columns"]! == "true">
    <#assign isPaginated = (!(formNode["@paginate"]! == "false") && context[listName + "Count"]?? && (context[listName + "Count"]! > 0) &&
            (!formNode["@paginate-always-show"]?has_content || formNode["@paginate-always-show"]! == "true" || (context[listName + "PageMaxIndex"] > 0)))>
    <#assign currentFindUrl = sri.getScreenUrlInstance().cloneUrlInstance().removeParameter("pageIndex").removeParameter("moquiFormName").removeParameter("moquiSessionToken").removeParameter("lastStandalone").removeParameter("formListFindId")>
    <#assign currentFindUrlParms = currentFindUrl.getParameterMap()>
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
    <#assign userDefaultFormListFindId = formListInfo.getUserDefaultFormListFindId(ec)!"">
    <#assign origFormDisabled = formDisabled!false>
    <#assign formDisabled = false>
    <#if isHeaderDialog>
        <#assign haveFilters = false>
        <#assign curFindSummary>
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
        </#assign>
    </#if>
    <#if (isHeaderDialog || isSavedFinds || isSelectColumns || isPaginated) && hideNav! != "true">
        <tr class="form-list-nav-row"><th colspan="${numColumns}"><div class="row">
            <div class="col-xs-12 col-sm-6"><div class="row">
            <#if isSavedFinds>
                <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                <#if userFindInfoList?has_content>
                    <#assign activeUserFindName = ""/>
                    <#if ec.getContext().formListFindId?has_content>
                        <#list userFindInfoList as userFindInfo>
                            <#if userFindInfo.formListFind.formListFindId == ec.getContext().formListFindId>
                                <#assign activeUserFindName = userFindInfo.description/></#if></#list>
                    </#if>
                    <q-btn-dropdown dense outline no-caps label="<#if activeUserFindName?has_content>${activeUserFindName?html}<#else>${ec.getL10n().localize("Select Find")}</#if>" color="<#if activeUserFindName?has_content>info</#if>"><q-list dense>
                        <q-item clickable v-close-popup><q-item-section>
                            <m-link href="${sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", "_clear").pathWithParams}">${ec.getL10n().localize("Clear Current Find")}</m-link>
                        </q-item-section></q-item>
                        <#list userFindInfoList as userFindInfo>
                            <#assign formListFind = userFindInfo.formListFind>
                            <#assign findParameters = userFindInfo.findParameters>
                            <#-- use only formListFindId now that ScreenRenderImpl picks it up and auto adds configured parameters:
                            <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)> -->
                            <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", formListFind.formListFindId)>
                            <q-item clickable v-close-popup><q-item-section>
                                <m-link href="${doFindUrl.pathWithParams}">${userFindInfo.description?html}</m-link>
                            </q-item-section></q-item>
                        </#list>
                    </q-list></q-btn-dropdown>
                </#if>
            </#if>
            <#if isHeaderDialog>
                <#assign headerFormId = formId + "_header">
                <#assign headerFormButtonText = ec.getL10n().localize("Find Options")>
                <m-container-dialog id="${formId + "_hdialog"}" title="${headerFormButtonText}">
                    <template v-slot:button><q-btn dense outline no-caps label="${headerFormButtonText}" icon="search"></q-btn></template>
                    <#-- Find Parameters Form -->
                    <#assign curUrlInstance = sri.getCurrentScreenUrl()>
                    <#assign skipFormSave = skipForm!false>
                    <#assign skipForm = false>
                    <#assign fieldsJsName = "formProps.fields">
                    <#assign orderByOptions>
                        <#list formNode["field"] as fieldNode><#if fieldNode["header-field"]?has_content>
                            <#assign headerFieldNode = fieldNode["header-field"][0]>
                            <#assign showOrderBy = (headerFieldNode["@show-order-by"])!>
                            <#if showOrderBy?has_content && showOrderBy != "false">
                                <#assign caseInsensitive = showOrderBy == "case-insensitive">
                                <#assign orderFieldName = fieldNode["@name"]>
                                <#assign orderFieldTitle><@fieldTitle headerFieldNode/></#assign>
                                <#t>{value:'${caseInsensitive?string("^", "") + orderFieldName}',label:'${orderFieldTitle} ${ec.getL10n().localize("(Asc)")}'},
                                <#t>{value:'${"-" + caseInsensitive?string("^", "") + orderFieldName}',label:'${orderFieldTitle} ${ec.getL10n().localize("(Desc)")}'},
                            </#if>
                        </#if></#list>
                    </#assign>
                    <m-form-link name="${headerFormId}" id="${headerFormId}" action="${curUrlInstance.path}" v-slot:default="formProps"<#rt>
                            <#t> :fields-initial="${Static["org.moqui.util.WebUtilities"].fieldValuesEncodeHtmlJsSafe(sri.getFormListHeaderValues(formNode))}">
                        <div class="q-mx-sm">
                            <q-btn dense outline no-caps name="clearParameters" @click.prevent="formProps.clearForm" label="${ec.getL10n().localize("Clear Parameters")}"></q-btn>

                            <#-- Always add an orderByField to select one or more columns to order by -->
                            <q-select dense outlined options-dense multiple clearable emit-value map-options v-model="formProps.fields.orderByField"
                                    name="orderByField" id="${headerFormId}_orderByField" stack-label label="${ec.getL10n().localize("Order By")}"
                                    :options="[${orderByOptions}]"></q-select>
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
                    </m-form-link>
                    <#assign skipForm = skipFormSave>
                    <#-- TODO: anything needed for per-row or multi forms? -->
                    <#assign fieldsJsName = "">
                </m-container-dialog>
            </#if>

            <#if isSelectColumns>
                <#assign selectColumnsDialogId = formId + "_SelColsDialog">
                <#assign selectColumnsSortableId = formId + "_SelColsSortable">
                <#assign fieldsNotInColumns = formListInfo.getFieldsNotReferencedInFormListColumn()>
                <#assign hiddenChildren>
                    <#list fieldsNotInColumns as fieldNode>
                        <#assign fieldSubNode = (fieldNode["header-field"][0])!(fieldNode["default-field"][0])!>
                        <#assign curFieldTitle><@fieldTitle fieldSubNode/></#assign>
                        <#t>{id:'${fieldNode["@name"]}',label:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(curFieldTitle)}'}
                    <#sep>,</#list>
                </#assign>
                <#assign columnFieldInfo>
                    <#list allColInfoList as columnFieldList>
                        <#t>{id:'column_${columnFieldList_index}',label:'${ec.l10n.localize("Column")} ${columnFieldList_index + 1}',children:[
                        <#list columnFieldList as fieldNode>
                            <#assign fieldSubNode = (fieldNode["header-field"][0])!(fieldNode["default-field"][0])!>
                            <#assign curFieldTitle><@fieldTitle fieldSubNode/></#assign>
                            <#t>{id:'${fieldNode["@name"]}',label:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(curFieldTitle)}'}
                        <#sep>,</#list>
                        <#t>]}
                    <#sep>,</#list>
                </#assign>
                <m-container-dialog id="${selectColumnsDialogId}" title="${ec.l10n.localize("Column Fields")}">
                    <template v-slot:button><q-btn dense outline no-caps label="${ec.getL10n().localize("Columns")}" icon="table_chart"></q-btn></template>
                    <m-form-column-config id="${formId}_SelColsForm" action="${sri.buildUrl("formSelectColumns").path}"
                        <#if currentFindUrlParms?has_content> :find-parameters="{<#list currentFindUrlParms.keySet() as parmName>'${parmName}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentFindUrlParms.get(parmName)!)}'<#sep>,</#list>}"</#if>
                        :columns-initial="[{id:'hidden', label:'${ec.l10n.localize("Do Not Display")}', children:[${hiddenChildren}]},${columnFieldInfo}]"
                        form-location="${formListInfo.getFormLocation()}">
                    </m-form-column-config>
                </m-container-dialog>
            </#if>

            <#if isSavedFinds>
                <#assign savedFormButtonText = ec.getL10n().localize("Saved Finds")>
                <m-container-dialog id="${formId + "_sfdialog"}" title="${savedFormButtonText}">
                    <template v-slot:button><q-btn dense outline no-caps label="${savedFormButtonText}" icon="find_in_page"></q-btn></template>
                    <#assign activeFormListFind = formListInfo.getFormInstance().getActiveFormListFind(ec)!>
                    <#assign formSaveFindUrl = sri.buildUrl("formSaveFind").path>
                    <#assign descLabel = ec.getL10n().localize("Description")>

                    <#if activeFormListFind?has_content>
                        <#assign screenScheduled = formListInfo.getScreenForm().getFormListFindScreenScheduled(activeFormListFind.formListFindId, ec)!>
                        <div><strong>${ec.getL10n().localize("Active Saved Find:")}</strong> ${activeFormListFind.description?html}
                            <#if userDefaultFormListFindId == activeFormListFind.formListFindId><span class="text-info">(${ec.getL10n().localize("My Default")})</span></#if></div>
                        <#if screenScheduled?has_content>
                            <p>(Scheduled for <#if screenScheduled.renderMode! == 'xsl-fo'>PDF<#else>${screenScheduled.renderMode!?upper_case}</#if><#rt>
                                <#t> ${Static["org.moqui.impl.service.ScheduledJobRunner"].getCronDescription(screenScheduled.cronExpression, ec.user.getLocale(), true)!})</p>
                        <#else>
                            <m-form class="form-inline" id="${formId}_SCHED" action="${formSaveFindUrl}" v-slot:default="formProps"
                                    :fields-initial="{formListFindId:'${activeFormListFind.formListFindId}', screenPath:'${sri.getScreenUrlInstance().path}', renderMode:'', cronSelected:''}">
                                <m-drop-down v-model="formProps.fields.renderMode" name="renderMode" label="${ec.getL10n().localize("Mode")}" id="${formId}_SCHED_renderMode"
                                             :options="[{value:'xlsx',label:'XLSX'},{value:'csv',label:'CSV'},{value:'xsl-fo',label:'PDF'}]"></m-drop-down>
                                <m-drop-down v-model="formProps.fields.cronSelected" name="cronSelected" label="${ec.getL10n().localize("Schedule")}" id="${formId}_SCHED_cronSelected"
                                             :options="[{value:'0 0 6 ? * MON-FRI',label:'Monday-Friday'},{value:'0 0 6 ? * *',label:'Every Day'},{value:'0 0 6 ? * MON',label:'Monday Only'},{value:'0 0 6 1 * ?',label:'Monthly'}]"></m-drop-down>
                                <q-btn dense outline no-caps type="submit" name="ScheduleFind" onclick="return confirm('${ec.getL10n().localize("Setup a schedule to send this saved find to you by email?")}');" label="${ec.getL10n().localize("Schedule")}"></q-btn>
                            </m-form>
                        </#if>
                    </#if>
                    <#if currentFindUrlParms?has_content>
                        <#if activeFormListFind?has_content><hr></#if>
                        <p>${curFindSummary!""}</p>

                        <m-form class="form-inline" id="${formId}_NewFind" action="${formSaveFindUrl}" v-slot:default="formProps"
                                :fields-initial="{formLocation:'${formListInfo.getSavedFindFullLocation()}',_findDescription:'',<#rt>
                        <#t><#list currentFindUrlParms.keySet() as parmName>'${parmName}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentFindUrlParms.get(parmName)!)}',</#list>}">
                            <div class="big-row">
                                <div class="q-my-auto big-row-item"><q-input v-model="formProps.fields._findDescription" dense outlined stack-label label="${descLabel}" size="30" name="_findDescription" id="${formId}_NewFind_description" required="required"></q-input></div>
                                <div class="on-right q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" label="${ec.getL10n().localize("Save New Find")}"></q-btn></div>
                            </div>
                        </m-form>
                    <#else>
                        <div style="margin:12px 0;"><strong>${ec.getL10n().localize("No find parameters (or default), choose some in Find Options to save a new find or update existing")}</strong></div>
                    </#if>
                    <#assign userFindInfoList = formListInfo.getUserFormListFinds(ec)>
                    <#list userFindInfoList as userFindInfo>
                        <#assign formListFind = userFindInfo.formListFind>
                        <#assign findParameters = userFindInfo.findParameters>
                        <#-- use only formListFindId now that ScreenRenderImpl picks it up and auto adds configured parameters:
                        <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameters(findParameters)> -->
                        <#assign doFindUrl = sri.buildUrl(sri.getScreenUrlInstance().path).addParameter("formListFindId", formListFind.formListFindId)>
                        <#assign saveFindFormId = formId + "_SaveFind" + userFindInfo_index>
                        <#if currentFindUrlParms?has_content>
                            <div class="big-row">
                                <m-form id="${saveFindFormId}" name="${saveFindFormId}" action="${formSaveFindUrl}" v-slot:default="formProps"
                                        :fields-initial="{formLocation:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(formListInfo.getSavedFindFullLocation())}', formListFindId:'${formListFind.formListFindId}',<#rt>
                                            <#t>_findDescription:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(formListFind.description?html)}',
                                            <#t><#list currentFindUrlParms.keySet() as parmName>'${parmName}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentFindUrlParms.get(parmName)!)}',</#list>}">
                                    <div class="q-my-auto big-row-item"><q-input v-model="formProps.fields._findDescription" dense outlined stack-label label="${descLabel}" size="30" name="_findDescription" id="${saveFindFormId}_description" required="required"></q-input></div>
                                    <div class="on-right q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" name="UpdateFind" label="${ec.getL10n().localize("Update")}">
                                            <q-tooltip>Update saved find using description and current find parameters</q-tooltip>
                                        </q-btn></div>
                                    <#if userFindInfo.isByUserId == "true">
                                        <div class="q-my-auto big-row-item"><q-btn dense flat no-caps type="submit" name="DeleteFind" color="negative" icon="delete_forever" onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');"></q-btn></div>
                                    </#if>
                                    <div class="q-my-auto big-row-item"><q-btn dense outline no-caps to="${doFindUrl.pathWithParams}" label="${ec.getL10n().localize("Do Find")}"></q-btn></div>
                                    <#if userDefaultFormListFindId == formListFind.formListFindId>
                                        <div class="q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" name="ClearDefault" color="info" label="${ec.getL10n().localize("Clear Default")}"></q-btn></div>
                                    <#else>
                                        <div class="q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" name="MakeDefault" label="${ec.getL10n().localize("Make Default")}"></q-btn></div>
                                    </#if>
                                </m-form>
                            </div>
                        <#else>
                            <div class="big-row">
                                <div class="q-my-auto big-row-item on-left"><q-input dense outlined readonly value="${formListFind.description?html}"></q-input></div>
                                <div class="q-my-auto big-row-item"><q-btn dense outline no-caps to="${doFindUrl.pathWithParams}" label="${ec.getL10n().localize("Do Find")}"></q-btn></div>
                                <m-form id="${saveFindFormId}" action="${formSaveFindUrl}" :no-validate="true"
                                        :fields-initial="{formListFindId:'${formListFind.formListFindId}', formLocation:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(formListInfo.getSavedFindFullLocation())}'}">
                                    <#if userFindInfo.isByUserId == "true">
                                        <div class="q-my-auto big-row-item"><q-btn dense flat no-caps type="submit" name="DeleteFind" color="negative" icon="delete_forever"
                                                onclick="return confirm('${ec.getL10n().localize("Delete")} ${formListFind.description?js_string}?');"></q-btn></div>
                                    </#if>
                                    <#if userDefaultFormListFindId == formListFind.formListFindId>
                                        <div class="q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" name="ClearDefault" color="info" label="${ec.getL10n().localize("Clear Default")}"></q-btn></div>
                                    <#else>
                                        <div class="q-my-auto big-row-item"><q-btn dense outline no-caps type="submit" name="MakeDefault" label="${ec.getL10n().localize("Make Default")}"></q-btn></div>
                                    </#if>
                                </m-form>
                            </div>
                        </#if>
                    </#list>
                </m-container-dialog>
            </#if>

            <#if formNode["@show-csv-button"]! == "true">
                <#assign csvLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("renderMode", "csv")
                        .addParameter("pageNoLimit", "true").addParameter("lastStandalone", "true").addParameter("saveFilename", formNode["@name"] + ".csv")>
                <q-btn dense outline type="a" href="${csvLinkUrl.getUrlWithParams()}" label="${ec.getL10n().localize("CSV")}"></q-btn>
            </#if>
            <#if formNode["@show-xlsx-button"]! == "true" && ec.screen.isRenderModeValid("xlsx")>
                <#assign xlsxLinkUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("renderMode", "xlsx")
                        .addParameter("pageNoLimit", "true").addParameter("lastStandalone", "true").addParameter("saveFilename", formNode["@name"] + ".xlsx")>
                <q-btn dense outline type="a" href="${xlsxLinkUrl.getUrlWithParams()}" label="${ec.getL10n().localize("XLS")}"></q-btn>
            </#if>
            <#if formNode["@show-text-button"]! == "true">
                <#assign showTextDialogId = formId + "_TextDialog">
                <#assign textLinkUrl = sri.getScreenUrlInstance()>
                <#assign textLinkUrlParms = textLinkUrl.getParameterMap()>
                <m-container-dialog id="${showTextDialogId}" button-text="${ec.getL10n().localize("Text")}" title="${ec.getL10n().localize("Export Fixed-Width Plain Text")}">
                    <#-- NOTE: don't use m-form, most commonly results in download and if not won't be html -->
                    <form id="${formId}_Text" method="post" action="${textLinkUrl.getUrl()}">
                        <input type="hidden" name="renderMode" value="text">
                        <input type="hidden" name="pageNoLimit" value="true">
                        <input type="hidden" name="lastStandalone" value="true">
                        <#list textLinkUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${textLinkUrlParms.get(parmName)!?html}"></#list>
                        <#-- TODO quasar components and layout, lower priority (not commonly used) -->
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
                </m-container-dialog>
            </#if>
            <#if formNode["@show-pdf-button"]! == "true">
                <#assign showPdfDialogId = formId + "_PdfDialog">
                <#assign pdfLinkUrl = sri.getScreenUrlInstance()>
                <#assign pdfLinkUrlParms = pdfLinkUrl.getParameterMap()>
                <m-container-dialog id="${showPdfDialogId}" button-text="${ec.getL10n().localize("PDF")}" title="${ec.getL10n().localize("Generate PDF")}">
                    <#-- NOTE: don't use m-form, most commonly results in download and if not won't be html -->
                    <form id="${formId}_Pdf" method="post" action="${ec.web.getWebappRootUrl(false, null)}/fop${pdfLinkUrl.getPath()}">
                        <input type="hidden" name="pageNoLimit" value="true">
                        <#list pdfLinkUrlParms.keySet() as parmName>
                            <input type="hidden" name="${parmName}" value="${pdfLinkUrlParms.get(parmName)!?html}"></#list>
                        <#-- TODO quasar components and layout, lower priority (not commonly used) -->
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
                </m-container-dialog>
            </#if>

            <#if (context[listName + "Count"]!(context[listName].size())!0) == 0>
                <#if context.getSharedMap().get("_entityListNoSearchParms")!false == true>
                    <strong class="text-warning on-right q-my-auto">${ec.getL10n().localize("Find Options required to view results")}</strong>
                <#else>
                    <strong class="text-warning on-right q-my-auto">${ec.getL10n().localize("No results found")}</strong>
                </#if>
            </#if>
            </div></div>

            <#if isPaginated>
            <div class="col-xs-12 col-sm-6"><div class="row">
                <q-space></q-space>
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
                    <span class="on-left q-my-auto"><q-btn-dropdown dense outline no-caps label="${context[listName + "PageSize"]?c}"><q-list dense>
                        <#list [10,20,50,100,200,500] as curPageSize>
                            <#assign pageSizeUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageSize", curPageSize?c)>
                            <q-item clickable v-close-popup><q-item-section>
                                <m-link href="${pageSizeUrl.pathWithParams}">${curPageSize?c}</m-link>
                            </q-item-section></q-item>
                        </#list>
                    </q-list></q-btn-dropdown></span>
                </#if>

                <#assign curPageMaxIndex = context[listName + "PageMaxIndex"]>
                <#if (curPageMaxIndex > 4)><m-form-go-page id-val="${formId}" :max-index="${curPageMaxIndex?c}"></m-form-go-page></#if>
                <m-form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
                    <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
                    <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></m-form-paginate>
            </div></div>
            </#if>
        </div></th></tr>

        <#if isHeaderDialog>
        <tr><th colspan="${numColumns}" style="font-weight: normal">
            ${curFindSummary!""}
            <#if haveFilters>
                <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
                <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
                <#assign curUrlInstance = sri.getCurrentScreenUrl()>
                <m-form-link name="${headerFormId}_clr" id="${headerFormId}_clr" action="${curUrlInstance.path}"
                         :fields-initial="{<#list hiddenParameterKeys as hiddenParameterKey>'${hiddenParameterKey}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(hiddenParameterMap.get(hiddenParameterKey)!)}'<#sep>,</#list>}">
                    <q-btn dense flat type="submit" icon="clear" color="negative">
                        <q-tooltip>Reset to Default</q-tooltip></q-btn>
                </m-form-link>
            </#if>
        </th></tr>
        </#if>
    </#if>
    <#assign formDisabled = origFormDisabled>
</#macro>
<#macro formListSelectedRowCard rowSelectionNode>
    <#-- render action forms, optionally inside dialog -->
    <q-card flat bordered><q-card-section horizontal class="q-pa-md">
        <#list rowSelectionNode["action"]! as actionNode>
            <#assign dialogNodes = actionNode["dialog"]!>
            <#assign formSingleNode = actionNode["form-single"][0]>

            <#-- FUTURE: consider disable-condition and disable-message, not needed now (and more flexible to use conditional-field in the form-single) -->
            <#-- FUTURE: dynamic disable if no rows selected? maybe not, some might be designed to operate with no rows selected... -->

            <#if dialogNodes?has_content>
                <#assign dialogNode = dialogNodes[0]>
                <#assign buttonText = ec.getResource().expand(dialogNode["@button-text"], "")>
                <#assign title = ec.getResource().expand(dialogNode["@title"], "")>
                <#if !title?has_content><#assign title = buttonText></#if>
                <m-container-dialog color="<@getQuasarColor ec.getResource().expandNoL10n(dialogNode["@button-type"]!"primary", "")/>"
                        width="${dialogNode["@width"]!""}" title="${title}" button-text="${buttonText}"
                        button-class="${ec.getResource().expandNoL10n(dialogNode["@button-style"]!"", "")}">
            </#if>

            <#assign _formListSelectedForm = true>
            <#visit formSingleNode>
            <#assign _formListSelectedForm = false>

            <#if dialogNodes?has_content>
                </m-container-dialog>
            </#if>
        </#list>
    </q-card-section></q-card>
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
    <#assign rowSelectionNode = formInstance.getRowSelectionNode()!>
    <#assign isRowSelection = rowSelectionNode?has_content>
    <#assign numColumns = (mainColInfoList?size)!100>
    <#if numColumns == 0><#assign numColumns = 100></#if>
    <#if isRowSelection><#assign numColumns = numColumns + 1></#if>
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
    <#assign listName = formNode["@list"]>
    <#assign isServerStatic = formInstance.isServerStatic(sri.getRenderMode())>
    <#assign formDisabled = formListUrlInfo.disableLink>

<#if isServerStatic><#-- client rendered, static -->
    <#-- TODO: form-list server-static needs to be revisited still for Quasar -->
    <#assign hiddenParameterMap = sri.getFormHiddenParameters(formNode)>
    <#assign hiddenParameterKeys = hiddenParameterMap.keySet()>
    <m-form-list name="${formName}" id="${formId}" rows="${formName}" action="${formListUrlInfo.path}" :multi="${isMulti?c}"<#rt>
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
                <tr><td colspan="${numColumns}" class="m-form-list-sub-row-cell"><div class="form-list-sub-rows"><table class="table table-striped table-hover table-condensed${tableStyle}"><thead>
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
    </m-form-list>
<#else><#-- server rendered, non-static -->
    <#assign listObject = formListInfo.getListObject(true)!>
    <#-- use raw data, need all for non-simple fields, transform only when needed for m-form.fields-initial: <#assign listObject = sri.getFormListRowValues(formListInfo)!> -->
    <#assign listHasContent = listObject?has_content>

    <#-- start/header -->
    <#if !skipStart>
        <#if isRowSelection>
            <#assign checkboxIdField = rowSelectionNode["@id-field"]>
            <#assign checkboxKeyValues = Static["org.moqui.util.CollectionUtilities"].getMapArrayListValues(listObject, checkboxIdField, false)>
        <#else>
            <#assign checkboxIdField = "">
        </#if>
        <#if isMulti>
            <m-form name="${formId}" id="${formId}" action="${formListUrlInfo.path}" v-slot:default="formProps"
                    <#t><#if checkboxIdField?has_content> checkbox-parameter="${rowSelectionNode["@parameter"]!checkboxIdField}" :checkbox-list-mode="${rowSelectionNode["@list-mode"]!"false"}"</#if>
                    <#if checkboxIdField?has_content> :checkbox-values="[<#list checkboxKeyValues as keyValue>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(keyValue)}'<#sep>,</#list>]"</#if>
                    :fields-initial="${Static["org.moqui.util.WebUtilities"].fieldValuesEncodeHtmlJsSafe(sri.makeFormListMultiMap(formListInfo, listObject, formListUrlInfo))}">
        <#elseif isRowSelection>
            <m-checkbox-set :checkbox-count="${((listObject.size())!0)?c}" v-slot:default="formProps"
                    <#t> checkbox-parameter="${rowSelectionNode["@parameter"]!checkboxIdField}" :checkbox-list-mode="${rowSelectionNode["@list-mode"]!"false"}"
                    :checkbox-values="[<#list checkboxKeyValues as keyValue>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(keyValue)}'<#sep>,</#list>]">
        </#if>

        <div class="q-my-sm q-table__container q-table__card q-table--horizontal-separator q-table--dense q-table--flat" :class="{'q-table--dark':$q.dark.isActive, 'q-table__card--dark':$q.dark.isActive, 'q-dark':$q.dark.isActive,}">
        <div class="table q-table ${tableStyle}" id="${formId}_table">
        <#if !skipHeader>
            <div class="thead">
                <@paginationHeader formListInfo formId isHeaderDialog/>
                <div class="tr">
                    <#if isRowSelection>
                        <div class="th"><span class="q-my-auto">
                            <q-checkbox size="sm" v-model="formProps.checkboxAllState" @input="formProps.setCheckboxAllState">
                                <q-tooltip>{{formProps.checkboxAllState ? '${ec.getL10n().localize("Unselect All")}' : '${ec.getL10n().localize("Select All")}'}}</q-tooltip></q-checkbox>
                            <q-btn dense flat icon="build" :color="formProps.checkboxStates && formProps.checkboxStates.includes(true) ? 'success' : ''">
                                <q-tooltip>${ec.getL10n().localize("Row Actions")}</q-tooltip>
                                <q-menu anchor="top left" self="bottom left"><@formListSelectedRowCard rowSelectionNode/></q-menu>
                            </q-btn>
                        </span></div>
                    </#if>

                    <#if needHeaderForm && !isHeaderDialog>
                        <#assign ownerForm = headerFormId>
                        <#assign fieldsJsName = "formProps.fields">
                        <#assign headerUrlInstance = sri.getCurrentScreenUrl()>
                        <m-form-link name="${headerFormId}" id="${headerFormId}" action="${headerUrlInstance.path}" v-slot:default="formProps"<#rt>
                                <#t> :fields-initial="${Static["org.moqui.util.WebUtilities"].fieldValuesEncodeHtmlJsSafe(sri.getFormListHeaderValues(formNode))}">
                    </#if>

                    <#list mainColInfoList as columnFieldList><div class="th text-left"><#list columnFieldList as fieldNode>
                        <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                    </#list></div><#-- /th --></#list>

                    <#if needHeaderForm && !isHeaderDialog>
                        <#assign fieldsJsName = "">
                        </m-form-link>
                    </#if>
                </div><#-- /tr -->
                <#if hasSubColumns>
                    <#-- TODO: per-row form and sub-columns used together will result in invalid html -->
                    <tr><td colspan="${numColumns}" class="form-list-sub-row-cell"><div class="form-list-sub-rows"><table class="table table-striped table-hover table-condensed${tableStyle}"><thead>
                        <#list subColInfoList as subColFieldList><th class="text-left">
                            <#list subColFieldList as fieldNode>
                                <div><@formListHeaderField fieldNode isHeaderDialog/></div>
                            </#list>
                        </th></#list>
                    </thead></table></div></td></tr>
                </#if>
                <#assign ownerForm = "">
            </div><#-- /thead -->
        </#if>
        <div class="tbody">
        <#assign ownerForm = formId>
    </#if>
    <#-- first-row fields -->
    <#if formListInfo.hasFirstRow()>
        <#-- TODO change to wrap row, use something like sri.makeFormListSingleMap() which eliminates use inline of hiddenParameterKeys, hiddenParameterMap -->
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
        <#if formListInfo.isSecondRowForm()>
            <#-- TODO change to wrap row, use something like sri.makeFormListSingleMap() which eliminates use inline of hiddenParameterKeys, hiddenParameterMap -->
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

    <#-- the main list -->
    <#if listHasContent><#list listObject as listEntry>
        <#assign listEntryIndex = listEntry_index>
        <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
        <#t>${sri.startFormListRow(formListInfo, listEntry, listEntry_index, listEntry_has_next)}

        <div class="tr">

        <#if isRowSelection>
            <div class="td">
            <#if listEntry[checkboxIdField]?has_content>
                <div class="q-my-auto"><q-checkbox size="xs" v-model="formProps.checkboxStates[${listEntry_index?c}]"></q-checkbox></div></#if>
            </div>
        </#if>
        <#if !(isMulti || skipForm)>
            <#assign ownerForm = formId + "_" + listEntry_index>
            <#assign fieldsJsName = "formProps.fields">
            <m-form name="${formId}_${listEntry_index}" id="${formId}_${listEntry_index}" action="${formListUrlInfo.path}" v-slot:default="formProps"
                    :fields-initial="${Static["org.moqui.util.WebUtilities"].fieldValuesEncodeHtmlJsSafe(sri.makeFormListSingleMap(formListInfo, listEntry, formListUrlInfo))}">
        </#if>
        <#if isMulti>
            <#assign ownerForm = formId>
            <#assign fieldsJsName = "formProps.fields">
        </#if>

        <#-- actual columns -->
        <#list mainColInfoList as columnFieldList>
            <div class="td">
            <#list columnFieldList as fieldNode>
                <@formListSubField fieldNode true false isMulti false/>
            </#list>
            </div>
        </#list>
        <#if hasSubColumns><#assign aggregateSubList = listEntry["aggregateSubList"]!><#if aggregateSubList?has_content>
            </tr>
            <tr><td colspan="${numColumns}" class="form-list-sub-row-cell"><div class="form-list-sub-rows q-table__container q-table__card q-table--horizontal-separator q-table--dense q-table--flat"><table class="q-table ${tableStyle}">
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

        <#if !(isMulti || skipForm)>
            </m-form>
        </#if>

        </div><#-- /tr -->
        <#t>${sri.endFormListRow()}
        <#assign ownerForm = "">
        <#assign fieldsJsName = "">
        <#assign listEntryIndex = "">
    </#list></#if>
    ${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->

    <#-- last-row fields -->
    <#if formListInfo.hasLastRow()>
        <#-- TODO change to wrap row, use something like sri.makeFormListSingleMap() which eliminates use inline of hiddenParameterKeys, hiddenParameterMap -->
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

        <#t>${sri.pushSingleFormMapContext(formNode["@map-last-row"]!"")}
        <#assign ownerForm = formId + "_last">
        <#assign listEntryIndex = "last">
        <div class="tr last">
            <#list mainColInfoList as columnFieldList>
                <div class="td">
                    <#list columnFieldList as fieldNode>
                            <@formListSubLast fieldNode true/>
                        </#list>
                </div>
            </#list>
        </div>
        <#assign ownerForm = formId>
        <#assign listEntryIndex = "">
        <#t>${sri.popContext()}<#-- context was pushed for the form so pop here at the end -->
    </#if>

    <#-- end/footer -->
    <#if !skipEnd>
        <#if isMulti && listHasContent>
            <tr><td colspan="${numColumns}">
                <#list formNode["field"] as fieldNode><@formListSubField fieldNode false false true true/></#list>
            </td></tr>
        </#if>
        <#-- footer pagination control -->
        <#if isPaginated?? && isPaginated>
            <tr class="form-list-nav-row"><th colspan="${numColumns}"><div class="row">
                <q-space></q-space>
                <#if formNode["@show-all-button"]! == "true" || formNode["@show-page-size"]! == "true">
                    <span class="on-left q-my-auto"><q-btn-dropdown dense outline no-caps label="${context[listName + "PageSize"]?c}"><q-list dense>
                        <#list [10,20,50,100,200,500] as curPageSize>
                            <#assign pageSizeUrl = sri.getScreenUrlInstance().cloneUrlInstance().addParameter("pageSize", curPageSize?c)>
                            <q-item clickable v-close-popup><q-item-section>
                                <m-link href="${pageSizeUrl.pathWithParams}">${curPageSize?c}</m-link>
                            </q-item-section></q-item>
                        </#list>
                    </q-list></q-btn-dropdown></span>
                </#if>
                <#assign curPageMaxIndex = context[listName + "PageMaxIndex"]>
                <#if (curPageMaxIndex > 4)><m-form-go-page id-val="${formId}" :max-index="${curPageMaxIndex?c}"></m-form-go-page></#if>
                <m-form-paginate :paginate="{ count:${context[listName + "Count"]?c}, pageIndex:${context[listName + "PageIndex"]?c},<#rt>
                    <#t> pageSize:${context[listName + "PageSize"]?c}, pageMaxIndex:${context[listName + "PageMaxIndex"]?c},
                    <#lt> pageRangeLow:${context[listName + "PageRangeLow"]?c}, pageRangeHigh:${context[listName + "PageRangeHigh"]?c} }"></m-form-paginate>
            </div></th></tr>
        </#if>
        </div><#-- /tbody -->
        </div><#-- /table -->
        </div><#-- /table wrapper -->

        <#if isMulti>
            <#assign ownerForm = "">
            </m-form>
        <#elseif isRowSelection>
            </m-checkbox-set>
        </#if>
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

<#macro fieldName widgetNode suffix=""><#assign fieldNode=widgetNode?parent?parent/>${fieldNode["@name"]?html}${suffix}<#if isMulti?exists && isMulti && listEntryIndex?has_content && listEntryIndex?matches("\\d*")>_${listEntryIndex}</#if></#macro>
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
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <#assign useWrapper = (.node["@no-wrapper"]!"false") != "true">
    <#if useWrapper>
    <q-field dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if><#if containerStyle?has_content> class="${containerStyle}"</#if><#if formDisabled!false> disable</#if>>
        <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
        <template v-slot:control>
    </#if>
            <#list (options.keySet())! as key>
                <q-checkbox size="xs" val="${key?html}" label="${(options.get(key)!"")?html}" name="${curName}" id="${tlId}<#if (key_index > 0)>_${key_index}</#if>"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                    <#lt><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curName}"<#else> value="${key?html}"<#if allChecked! == "true"> checked="checked"<#elseif currentValue?has_content && (currentValue==key || currentValue.contains(key))> checked="checked"</#if></#if>></q-checkbox>
            </#list>
    <#if useWrapper>
        </template>
    </q-field>
    </#if>
</#macro>

<#macro "date-find">
    <#if .node["@type"]! == "time"><#assign size=9><#assign maxlength=13><#assign defaultFormat="HH:mm">
    <#elseif .node["@type"]! == "date"><#assign size=10><#assign maxlength=10><#assign defaultFormat="yyyy-MM-dd">
    <#else><#assign size=16><#assign maxlength=23><#assign defaultFormat="yyyy-MM-dd HH:mm">
    </#if>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign curFieldTitle><@fieldTitle .node?parent/></#assign>
    <#assign fieldValueFrom = ec.getL10n().format(ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!""), defaultFormat)>
    <#assign fieldValueThru = ec.getL10n().format(ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!""), defaultFormat)>
    <span class="form-date-find">
      <m-date-time id="<@fieldId .node/>_from" name="${curFieldName}_from" value="${fieldValueFrom?html}" type="${.node["@type"]!""}" size="${.node["@size"]!""}"<#rt>
          <#t> label="${curFieldTitle} ${ec.getL10n().localize("From")}"
          <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
          <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>></m-date-time>
    </span>
    <span class="form-date-find">
      <m-date-time id="<@fieldId .node/>_thru" name="${curFieldName}_thru" value="${fieldValueThru?html}" type="${.node["@type"]!""}" size="${.node["@size"]!""}"<#rt>
          <#t> label="${curFieldTitle} ${ec.getL10n().localize("Thru")}"
          <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
          <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>></m-date-time>
    </span>
</#macro>

<#macro "date-period">
    <#assign tlId><@fieldId .node/></#assign>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign curFieldTitle><@fieldTitle .node?parent/></#assign>
    <#assign allowEmpty = .node["@allow-empty"]!"true">
    <#if .node["@time"]! == "true"><#assign fromThruType = "date-time"><#else><#assign fromThruType = "date"></#if>
    <m-date-period name="${curFieldName}" id="${tlId}" :allow-empty="${allowEmpty}" from-thru-type="${fromThruType}" label="${curFieldTitle}"
        <#if fieldsJsName?has_content>
            :fields="${fieldsJsName}"
        <#else>
            <#assign fvOffset = ec.getContext().get(curFieldName + "_poffset")!>
            <#assign fvPeriod = ec.getContext().get(curFieldName + "_period")!?lower_case>
            <#assign fvDate = ec.getContext().get(curFieldName + "_pdate")!"">
            <#assign fvFromDate = ec.getContext().get(curFieldName + "_from")!"">
            <#assign fvThruDate = ec.getContext().get(curFieldName + "_thru")!"">
            :fields="{'${curFieldName}_poffset':'${fvOffset}','${curFieldName}_period':'${fvPeriod}','${curFieldName}_pdate':'${fvDate}','${curFieldName}_from':'${fvFromDate}','${curFieldName}_thru':'${fvThruDate}'}"
        </#if>
        <#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></m-date-period>
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
    <#assign curName><@fieldName .node/></#assign>
    <#assign validationClasses = formInstance.getFieldValidationClasses(dtSubFieldNode)>
    <m-date-time id="<@fieldId .node/>" name="${curName}" type="${.node["@type"]!""}" size="${.node["@size"]!""}" label="<@fieldTitle dtSubFieldNode/>"<#if formDisabled!> disable</#if><#rt>
        <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curName}"<#else> value="${sri.getFieldValueString(dtFieldNode, .node["@default-value"]!"", javaFormat)?html}"</#if>
        <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
        <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if javaFormat?has_content> format="<@getMomentDateFormat javaFormat/>"</#if>
        <#t><#if validationClasses?contains("required")> required="required"</#if><#if .node.@rules?has_content> :rules="[${.node.@rules}]"</#if>
        <#t> auto-year="${.node["@auto-year"]!"true"}" :minuteStep="${.node["@minute-stepping"]!"5"}"></m-date-time>
</#macro>

<#macro display>
    <#assign dispFieldId><@fieldId .node/></#assign>
    <#assign dispFieldName><@fieldName .node/></#assign>
    <#assign dispSubFieldNode = .node?parent>
    <#assign dispFieldNode = dispSubFieldNode?parent>
    <#assign dispFormNode = dispFieldNode?parent>
    <#assign dispAlign = dispFieldNode["@align"]!"left">
    <#assign dispHidden = (!.node["@also-hidden"]?has_content || .node["@also-hidden"] == "true") && !(skipForm!false)>
    <#assign dispDynamic = .node["@dynamic-transition"]?has_content>
    <#assign labelWrapper = dispFormNode?node_name == "form-single">
    <#assign fieldLabel><@fieldTitle dispSubFieldNode/></#assign>
    <#assign fieldValue = "">
    <#if fieldsJsName?has_content>
        <#assign format = .node["@format"]!>
        <#assign dispFieldNameDisplay><@fieldName .node "_display"/></#assign>
        <#-- TODO: format is a Vue filter, applied with {{ ... | format }}, how to format only ${fieldsJsName}.${dispFieldName} and not ${fieldsJsName}.${dispFieldNameDisplay} ??? for now no format -->
        <#assign fieldValue>{{${fieldsJsName}.${dispFieldNameDisplay} || ${fieldsJsName}.${dispFieldName}}}</#assign>
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
        <#if dispDynamic && !fieldValue?has_content><#assign fieldValue><@widgetTextValue .node true/></#assign></#if>
    </#if>

    <#if dispDynamic>
        <#assign defUrlInfo = sri.makeUrlByType(.node["@dynamic-transition"], "transition", .node, "false")>
        <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
        <#assign depNodeList = .node["depends-on"]>
    </#if>

    <m-display name="${dispFieldName}" id="${dispFieldId}_display"<#if fieldLabel?has_content> label="${fieldLabel}"</#if><#if labelWrapper> label-wrapper</#if><#rt>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${dispFieldName}" :display="${fieldsJsName}.${dispFieldNameDisplay}" :fields="${fieldsJsName}"
                <#t><#elseif labelWrapper && fieldValue?has_content> display="<#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html}</#if>"</#if>
            <#t><#if dispSubFieldNode["@tooltip"]?has_content> tooltip="${ec.getResource().expand(dispSubFieldNode["@tooltip"], "")}"</#if>
            <#if dispDynamic> value-url="${defUrlInfo.url}" <#if .node["@depends-optional"]! == "true"> :depends-optional="true"</#if>
                <#t> :depends-on="{<#list depNodeList as depNode><#local depNodeField = depNode["@field"]>'${depNode["@parameter"]!depNodeField}':'${depNodeField}'<#sep>, </#list>}"
                <#t> :value-parameters="{<#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(parameterKey)}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(defUrlParameterMap.get(parameterKey))}', </#if></#list>}"
            <#t></#if>
            class="${sri.getFieldValueClass(dispFieldNode)}<#if .node["@currency-unit-field"]?has_content> currency</#if><#if dispAlign == "center"> text-center<#elseif dispAlign == "right"> text-right</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>">
        <#if !labelWrapper && fieldValue?has_content && (!fieldsJsName?has_content)>
            <template v-slot:default><#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html?replace("\n", "<br>")}</#if></template>
        </#if>
    </m-display>

    <#if dispHidden && !fieldsJsName?has_content>
        <#if dispDynamic>
            <#assign hiddenValue><@widgetTextValue .node true "value"/></#assign>
            <input type="hidden" id="${dispFieldId}" name="${dispFieldName}" value="${hiddenValue}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#else>
            <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
            <#-- don't default to fieldValue for the hidden input value, will only be different from the entry value if @text is used, and we don't want that in the hidden value -->
            <input type="hidden" id="${dispFieldId}" name="${dispFieldName}" <#if fieldsJsName?has_content>:value="${fieldsJsName}.${dispFieldName}"<#else>value="${sri.getFieldValuePlainString(dispFieldNode, "")?html}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        </#if>
    </#if>
</#macro>
<#macro "display-entity">
    <#assign dispFieldId><@fieldId .node/></#assign>
    <#assign dispFieldName><@fieldName .node/></#assign>
    <#assign dispSubFieldNode = .node?parent>
    <#assign dispFieldNode = dispSubFieldNode?parent>
    <#assign dispFormNode = dispFieldNode?parent>
    <#assign dispAlign = dispFieldNode["@align"]!"left">
    <#assign dispHidden = (!.node["@also-hidden"]?has_content || .node["@also-hidden"] == "true") && !(skipForm!false)>

    <#if fieldsJsName?has_content>
        <#assign fieldValue>{{(${fieldsJsName}.<@fieldName .node "_display"/> || ${fieldsJsName}.${dispFieldName})}}</#assign>
    <#else>
        <#assign fieldValue = sri.getFieldEntityValue(.node)!/>
    </#if>

    <#if dispFormNode?node_name == "form-single">
        <#assign fieldLabel><@fieldTitle dispSubFieldNode/></#assign>
        <#t><q-input dense outlined readonly<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if> id="${dispFieldId}_display"
                <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${dispFieldName}_display"</#if>
                <#t>class="<#if dispAlign == "center"> text-center<#elseif dispAlign == "right"> text-right</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>">
            <#if dispSubFieldNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(dispSubFieldNode["@tooltip"], "")}</q-tooltip></#if>
            <#-- TODO not sure if this will be used, might be for plain forms (not m-form or m-form-link) but need to find way to display like v-model as this ends up looking funny -->
            <#t><#if !fieldsJsName?has_content && fieldValue?has_content><#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html?replace("\n", "<br>")}</#if><#else>&nbsp;</#if>
        <#t></q-input>
    <#else>
        <#t><span class="text-inline<#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if>">
            <#if dispSubFieldNode["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(dispSubFieldNode["@tooltip"], "")}</q-tooltip></#if>
            <#if fieldValue?has_content><#if .node["@encode"]! == "false">${fieldValue}<#else>${fieldValue?html?replace("\n", "<br>")}</#if><#else>&nbsp;</#if></span>
    </#if>

    <#-- don't default to fieldValue for the hidden input value, will only be different from the entry value if @text is used, and we don't want that in the hidden value -->
    <#t><#if dispHidden && !fieldsJsName?has_content><input type="hidden" id="<@fieldId .node/>" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(.node?parent?parent, "")?html}"<#if ownerForm?has_content> form="${ownerForm}"</#if>></#if>
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
    <#assign fieldLabel><@fieldTitle ddSubFieldNode/></#assign>
    <m-drop-down name="${name}" id="${tlId}"<#if fieldLabel?has_content> label="${fieldLabel}"</#if><#if formDisabled!> disable</#if><#rt>
            <#t> class="<#if isDynamicOptions>dynamic-options</#if><#if .node["@style"]?has_content> ${ec.getResource().expandNoL10n(.node["@style"], "")}</#if><#if validationClasses?has_content> ${validationClasses}</#if>"<#rt>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${name}" :fields="${fieldsJsName}"<#else><#if allowMultiple> :value="[<#list currentValueList as curVal><#if curVal?has_content>'${curVal}',</#if></#list>]"<#else> value="${currentValue!}"</#if></#if>
            <#t><#if allowMultiple> :multiple="true"</#if><#if allowEmpty> :allow-empty="true"</#if><#if .node["@combo-box"]! == "true"> :combo="true"</#if>
            <#t><#if .node["@required-manual-select"]! == "true"> :required-manual-select="true"</#if>
            <#t><#if .node?parent["@tooltip"]?has_content> tooltip="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if>
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if .node["@size"]?has_content> size="${.node["@size"]}"</#if>
            <#if isDynamicOptions> options-url="${doUrlInfo.url}" value-field="${doNode["@value-field"]!"value"}" label-field="${doNode["@label-field"]!"label"}"<#if doNode["@depends-optional"]! == "true"> :depends-optional="true"</#if>
                <#t> :depends-on="{<#list depNodeList as depNode><#local depNodeField = depNode["@field"]>'${depNode["@parameter"]!depNodeField}':'${depNodeField}'<#sep>, </#list>}"
                <#t> :options-parameters="{<#list doUrlParameterMap.keySet() as parameterKey><#if doUrlParameterMap.get(parameterKey)?has_content>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(parameterKey)}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(doUrlParameterMap.get(parameterKey))}', </#if></#list>}"
                <#t><#if doNode["@server-search"]! == "true"> :server-search="true"</#if><#if doNode["@delay"]?has_content> :server-delay="${doNode["@delay"]}"</#if>
                <#t><#if doNode["@min-length"]?has_content> :server-min-length="${doNode["@min-length"]}"</#if>
                <#t><#if (.node?children?size > 1)> :options-load-init="true"</#if>
            </#if>
                :options="[<#if currentValue?has_content && !allowMultiple && !optionsHasCurrent>{value:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentValue)}',label:'<#if currentDescription?has_content>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentDescription!)}<#else>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(currentValue)}</#if>'},</#if><#rt>
                    <#t><#list (options.keySet())! as key>{value:'<#if key?has_content>${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(key)}</#if>',label:'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(options.get(key)!)}'}<#sep>,</#list>]"
            <#lt>>
            <#-- support <#if .node["@current"]! == "first-in-list"> again? -->
            <#if ec.getResource().expandNoL10n(.node["@show-not"]!, "") == "true">
            <template v-slot:after>
                <q-checkbox size="xs" name="${name}_not" label="${ec.getL10n().localize("Not")}"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                    <#t><#if fieldsJsName?has_content> true-value="Y" false-value="N" v-model="${fieldsJsName}.${name}_not"<#else> value="Y"<#if ec.getWeb().parameters.get(name + "_not")! == "Y"> checked="checked"</#if></#if>></q-checkbox>
            </template>
            </#if>
    </m-drop-down>
</#macro>

<#macro file>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <q-file dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}"</#if><#if formDisabled!> disable</#if>
            name="<@fieldName .node/>" size="${.node.@size!"30"}"<#if .node.@multiple! == "true"> multiple</#if><#if .node.@accept?has_content> accept="${.node.@accept}"</#if><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
    </q-file>
</#macro>

<#macro hidden>
    <#-- if the form is client rendered don't render anything hidden fields; NOTE: could also render and populate with value, but not needed (unless a need comes up...) -->
    <#if fieldsJsName?has_content><#return/></#if>
    <#-- use getFieldValuePlainString() and not getFieldValueString() so we don't do timezone conversions, etc -->
    <#assign tlId><@fieldId .node/></#assign>
    <input type="hidden" name="<@fieldName .node/>" value="${sri.getFieldValuePlainString(.node?parent?parent, .node["@default-value"]!"")?html}" id="${tlId}"<#if ownerForm?has_content> form="${ownerForm}"</#if>>
</#macro>

<#macro ignored><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>

<#macro password>
    <#assign validationClasses = formInstance.getFieldValidationClasses(.node?parent)>
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <q-input dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if> type="password" name="${curFieldName}" id="<@fieldId .node/>"<#if formDisabled!> disable</#if><#rt>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}"</#if>
            <#t> class="form-control<#if validationClasses?has_content> ${validationClasses}</#if>" size="${.node.@size!"25"}"
            <#t><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
            <#t><#if validationClasses?contains("required")> required</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
        <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
    </q-input>
</#macro>

<#macro radio>
    <#assign options = sri.getFieldOptions(.node)/>
    <#assign currentValue = sri.getFieldValueString(.node)/>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")/></#if>
    <#assign tlId><@fieldId .node/></#assign>
    <#assign curName><@fieldName .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <q-field dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if><#if formDisabled!> disable</#if>>
        <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
        <template v-slot:control>
        <#list (options.keySet())! as key>
            <q-radio size="xs" val="${key?html}" label="${(options.get(key)!"")?html}" name="${curName}" id="${tlId}<#if (key_index > 0)>_${key_index}</#if>"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                <#lt><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curName}"<#else> value="${key?html}"<#if currentValue?has_content && currentValue==key> checked="checked"</#if></#if>></q-radio>
        </#list>
        </template>
    </q-field>
</#macro>

<#macro "range-find">
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign tlId><@fieldId .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <#assign curTooltip = ec.getResource().expand(.node?parent["@tooltip"]!, "")>
<div class="row">
    <q-input dense outlined stack-label label="${fieldLabel} From" name="${curFieldName}_from" id="${tlId}_from"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
            <#t> size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}_from"<#else> value="${ec.getContext().get(curFieldName + "_from")!?default(.node["@default-value-from"]!"")?html}"</#if>>
        <#if curTooltip?has_content><q-tooltip>${curTooltip}</q-tooltip></#if>
    </q-input>
    <q-input class="q-pl-xs" dense outlined stack-label label="${fieldLabel} Thru" name="${curFieldName}_thru" id="${tlId}_thru"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
            <#t> size="${.node.@size!"10"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}_thru"<#else> value="${ec.getContext().get(curFieldName + "_thru")!?default(.node["@default-value-thru"]!"")?html}"</#if>>
        <#if curTooltip?has_content><q-tooltip>${curTooltip}</q-tooltip></#if>
    </q-input>
</div>
</#macro>

<#macro reset><q-btn dense outline type="reset" name="<@fieldName .node/>" value="<@fieldTitle .node?parent/>" id="<@fieldId .node/>"<#if .node["@icon"]?has_content> iconcls="ui-icon-${.node["@icon"]}"</#if><#if .node?parent["@tooltip"]?has_content> data-toggle="tooltip" title="${ec.getResource().expand(.node?parent["@tooltip"], "")}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>></#macro>

<#macro submit>
    <#assign confirmationMessage = ec.getResource().expand(.node["@confirmation"]!, "")/>
    <#assign buttonText><#if .node["@text"]?has_content>${ec.getResource().expand(.node["@text"], "")}<#else><@fieldTitle .node?parent/></#if></#assign>
    <#assign iconClass = .node["@icon"]!>
    <#if !iconClass?has_content><#assign iconClass = sri.getThemeIconClass(buttonText)!></#if>
    <q-btn dense outline no-caps type="submit" name="<@fieldName .node/>" value="<@fieldName .node/>" id="<@fieldId .node/>"<#rt>
            <#t> color="<@getQuasarColor .node["@type"]!"primary"/>"<#if formDisabled!> disabled</#if>
            <#t><#if confirmationMessage?has_content> onclick="return confirm('${confirmationMessage?js_string}');"</#if>
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if><#if !.node["image"]?has_content> label="${buttonText}"</#if>>
        <#if iconClass?has_content><i class="${iconClass}"></i></#if>
        <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
    <#if .node["image"]?has_content><#assign imageNode = .node["image"][0]>
        <img src="${sri.makeUrlByType(imageNode["@url"],imageNode["@url-type"]!"content",null,"true")}" alt="<#if imageNode["@alt"]?has_content>${imageNode["@alt"]}<#else><@fieldTitle .node?parent/></#if>"<#if imageNode["@width"]?has_content> width="${imageNode["@width"]}"</#if><#if imageNode["@height"]?has_content> height="${imageNode["@height"]}"</#if>>
    </#if>
    </q-btn>
</#macro>

<#macro "text-area">
    <#assign name><@fieldName .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <#assign editorType = ec.getResource().expand(.node["@editor-type"]!"", "")>
    <#if editorType == "html">
        <#-- support CSS from editorScreenThemeId with resourceTypeEnumId=STRT_STYLESHEET, like: contentsCss:['/css/mysitestyles.css','/css/anotherfile.css']; -->
        <#-- see: https://ckeditor.com/docs/ckeditor4/latest/api/CKEDITOR_config.html#cfg-contentsCss -->
        <#assign editorScreenThemeId = ec.getResource().expand(.node["@editor-theme"]!"", "")>
        <#assign editorThemeCssList = sri.getThemeValues("STRT_STYLESHEET", editorScreenThemeId)>
        <m-ck-editor<#if fieldsJsName?has_content> v-model="${fieldsJsName}.${name}"</#if>
                :config="{ customConfig:'',<#if editorThemeCssList?has_content>contentsCss:[<#list editorThemeCssList as themeCss>'${themeCss}'<#sep>,</#list>],</#if>
                    allowedContent:true, linkJavaScriptLinksAllowed:true, fillEmptyBlocks:false,
                    extraAllowedContent:'p(*)[*]{*};div(*)[*]{*};li(*)[*]{*};ul(*)[*]{*};i(*)[*]{*};span(*)[*]{*}',
                    width:'100%', height:'600px', removeButtons:'Image,Save,NewPage,Preview' }"></m-ck-editor>
    <#elseif editorType == "md">
        <m-simple-mde<#if fieldsJsName?has_content> v-model="${fieldsJsName}.${name}"</#if>
                :config="{ indentWithTabs:false, autoDownloadFontAwesome:false, autofocus:true, spellChecker:false }"></m-simple-mde>
    <#else>
        <q-input type="textarea" dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if> name="${name}" for="<@fieldId .node/>"
                <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${name}"</#if><#if formDisabled!> disable</#if>
                <#t><#if .node["@cols"]?has_content> cols="${.node["@cols"]}"<#else> style="width:100%;"</#if>
                <#t> rows="${.node["@rows"]!"3"}"<#if .node["@read-only"]! == "true"> readonly="readonly"</#if>
                <#t><#if .node["@maxlength"]?has_content> maxlength="${.node["@maxlength"]}"</#if><#if ownerForm?has_content> form="${ownerForm}"</#if>>
            <#if .node?parent["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node?parent["@tooltip"], "")}</q-tooltip></#if>
            <#if !fieldsJsName?has_content>${sri.getFieldValueString(.node)?html}</#if>
        </q-input>
    </#if>
</#macro>

<#macro "text-line">
    <#assign tlSubFieldNode = .node?parent>
    <#assign tlFieldNode = tlSubFieldNode?parent>
    <#assign tlId><@fieldId .node/></#assign>
    <#assign name><@fieldName .node/></#assign>
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#assign validationClasses = formInstance.getFieldValidationClasses(tlSubFieldNode)>
    <#assign validationRules = formInstance.getFieldValidationJsRules(tlSubFieldNode)!>
    <#-- NOTE: removed number type (<#elseif validationClasses?contains("number")>number) because on Safari, maybe others, ignores size and behaves funny for decimal values -->
    <#if .node["@ac-transition"]?has_content>
        <#assign acUrlInfo = sri.makeUrlByType(.node["@ac-transition"], "transition", .node, "false")>
        <#assign acUrlParameterMap = acUrlInfo.getParameterMap()>
        <#assign acShowValue = .node["@ac-show-value"]! == "true">
        <#assign acUseActual = .node["@ac-use-actual"]! == "true">
        <#if .node["@ac-initial-text"]?has_content><#assign valueText = ec.getResource().expand(.node["@ac-initial-text"]!, "")>
            <#else><#assign valueText = fieldValue></#if>
        <#assign depNodeList = .node["depends-on"]>
        <strong class="text-negative">text-line with @ac-transition is not supported, use drop-down with dynamic-options.@server-search</strong>
        <#--
        <text-autocomplete id="${tlId}" name="${name}" url="${acUrlInfo.url}" value="${fieldValue?html}" value-text="${valueText?html}"<#rt>
                <#t> type="<#if validationClasses?contains("email")>email<#elseif validationClasses?contains("url")>url<#else>text</#if>" size="${.node.@size!"30"}"
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
        -->
    <#else>
        <#assign tlAlign = tlFieldNode["@align"]!"left">
        <#assign fieldLabel><@fieldTitle tlSubFieldNode/></#assign>
        <#if .node["@default-transition"]?has_content>
            <#assign defUrlInfo = sri.makeUrlByType(.node["@default-transition"], "transition", .node, "false")>
            <#assign defUrlParameterMap = defUrlInfo.getParameterMap()>
            <#assign depNodeList = .node["depends-on"]>
        </#if>
        <#assign inputType><#if .node["@input-type"]?has_content>${.node["@input-type"]}<#else><#rt>
            <#lt><#if validationClasses?contains("email")>email<#elseif validationClasses?contains("url")>url<#else>text</#if></#if></#assign>
        <#-- TODO: possibly transform old mask values (for RobinHerbots/Inputmask used in vapps/vuet) -->
        <#assign expandedMask = ec.getResource().expandNoL10n(.node["@mask"]!"", "")!>
        <m-text-line dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if> id="${tlId}" type="${inputType}"<#rt>
                <#t> name="${name}"<#if .node.@prefix?has_content> prefix="${ec.resource.expand(.node.@prefix, "")}"</#if>
                <#t> <#if fieldsJsName?has_content>v-model="${fieldsJsName}.${name}" :fields="${fieldsJsName}"<#else><#if fieldValue?html == fieldValue>value="${fieldValue}"<#else>:value="'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(fieldValue)}'"</#if></#if>
                <#t><#if .node.@size?has_content> size="${.node.@size}"<#else> style="width:100%;"</#if><#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if>
                <#t><#if formDisabled! || ec.getResource().condition(.node.@disabled!"false", "")> disable</#if>
                <#t> class="<#if validationClasses?has_content>${validationClasses}</#if><#if tlAlign == "center"> text-center<#elseif tlAlign == "right"> text-right</#if>"
                <#t><#if validationClasses?contains("required")> required</#if><#if regexpInfo?has_content> pattern="${regexpInfo.regexp}" data-msg-pattern="${regexpInfo.message!"Invalid format"}"</#if>
                <#t><#if expandedMask?has_content> mask="${expandedMask}" fill-mask="_"</#if>
                <#t><#if .node["@default-transition"]?has_content>
                    <#t> default-url="${defUrlInfo.path}" :default-load-init="true"<#if .node["@depends-optional"]! == "true"> :depends-optional="true"</#if>
                    <#t> :depends-on="{<#list depNodeList as depNode><#local depNodeField = depNode["@field"]>'${depNode["@parameter"]!depNodeField}':'${depNodeField}'<#sep>, </#list>}"
                    <#t> :default-parameters="{<#list defUrlParameterMap.keySet() as parameterKey><#if defUrlParameterMap.get(parameterKey)?has_content>'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(parameterKey)}':'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(defUrlParameterMap.get(parameterKey))}', </#if></#list>}"
                <#t></#if>
                <#t><#if validationRules?has_content || .node.@rules?has_content>
                    <#t> :rules="[<#if .node.@rules?has_content>${.node.@rules},</#if><#list validationRules! as valRule>value => ${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(valRule.expr)}||'${Static["org.moqui.util.WebUtilities"].encodeHtmlJsSafe(valRule.message)}'<#sep>,</#list>]"
                <#t></#if>
                <#lt><#if ownerForm?has_content> form="${ownerForm}"</#if><#if tlSubFieldNode["@tooltip"]?has_content> tooltip="${ec.getResource().expand(tlSubFieldNode["@tooltip"], "")?html}"</#if>></m-text-line>
    </#if>
</#macro>

<#macro "text-find">
<div class="row">
    <#assign curFieldName><@fieldName .node/></#assign>
    <#assign fieldLabel><@fieldTitle .node?parent/></#assign>
    <#assign hideOptions = .node["@hide-options"]!"false">
    <q-input dense outlined<#if fieldLabel?has_content> stack-label label="${fieldLabel}"</#if> name="${curFieldName}"<#rt>
            <#t> size="${.node.@size!"30"}"<#if .node.@maxlength?has_content> maxlength="${.node.@maxlength}"</#if> id="<@fieldId .node/>"
            <#t><#if ownerForm?has_content> form="${ownerForm}"</#if>
            <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}"<#else> value="${sri.getFieldValueString(.node)?html}"</#if>>
        <#if .node["@tooltip"]?has_content><q-tooltip>${ec.getResource().expand(.node["@tooltip"], "")}</q-tooltip></#if>
        <#if hideOptions != "true" && (hideOptions != "ignore-case" || hideOptions != "operator")>
        <template v-slot:after>
            <#if hideOptions != "operator">
                <#assign defaultOperator = .node["@default-operator"]!"contains">
                <q-checkbox class="on-left" size="xs" name="${curFieldName}_not" label="${ec.getL10n().localize("Not")}"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                    <#t><#if fieldsJsName?has_content> true-value="Y" false-value="N" v-model="${fieldsJsName}.${curFieldName}_not"<#else>
                    <#t> value="Y"<#if ec.getWeb().parameters.get(curFieldName + "_not")! == "Y"> checked="checked"</#if></#if>></q-checkbox>
                <q-select class="on-left" dense outlined options-dense emit-value map-options name="${curFieldName}_op"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                    <#t><#if fieldsJsName?has_content> v-model="${fieldsJsName}.${curFieldName}_op"<#else> value="${ec.web.parameters.get(curFieldName + "_op")!defaultOperator!""}"</#if>
                    <#t> :options="[{value:'equals',label:'${ec.getL10n().localize("Equals")}'},{value:'like',label:'${ec.getL10n().localize("Like")}'},{value:'contains',label:'${ec.getL10n().localize("Contains")}'},{value:'begins',label:'${ec.getL10n().localize("Begins With")}'},{value:'empty',label:'${ec.getL10n().localize("Empty")}'}]"></q-select>
            </#if>
            <#if hideOptions != "ignore-case">
                <#assign ignoreCase = (ec.getWeb().parameters.get(curFieldName + "_ic")! == "Y") || !(.node["@ignore-case"]?has_content) || (.node["@ignore-case"] == "true")>
                <q-checkbox size="xs" name="${curFieldName}_ic" label="${ec.getL10n().localize("Ignore Case")}"<#if ownerForm?has_content> form="${ownerForm}"</#if><#rt>
                    <#t><#if fieldsJsName?has_content> true-value="Y" false-value="N" v-model="${fieldsJsName}.${curFieldName}_ic"<#else> value="Y"<#if ignoreCase> checked="checked"</#if></#if>></q-checkbox>
            </#if>
        </template>
        </#if>
    </q-input>
</div>
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
