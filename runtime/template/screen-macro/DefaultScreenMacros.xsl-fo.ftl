<#--
This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License.

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
<#macro attributeValue textValue>${Static["org.moqui.util.StringUtilities"].encodeForXmlAttribute(textValue, true)}</#macro>

<#macro @element><fo:block>=== Doing nothing for element ${.node?node_name}, not yet implemented. ===</fo:block></#macro>

<#macro screen><#recurse></#macro>
<#macro widgets>
    <#if !layoutMaster?has_content><#assign layoutMaster = "letter-portrait"></#if>
    <#-- calculate width in pixels for layout masters defined in Header.xsl-fo.ftl, based on 72dpi -->
    <#switch layoutMaster>
        <#case "letter-landscape"><#case "tabloid-portrait"><#assign layoutWidthIn = 10.5><#break>
        <#case "legal-landscape"><#assign layoutWidthIn = 13.5><#break>
        <#case "tabloid-landscape"><#assign layoutWidthIn = 16.5><#break>
        <#case "a4-portrait"><#assign layoutWidthIn = 7.8><#break>
        <#case "a4-landscape"><#case "a3-portrait"><#assign layoutWidthIn = 11.2><#break>
        <#case "a3-landscape"><#assign layoutWidthIn = 16><#break>
        <#-- default applies to letter-portrait, legal-portrait -->
        <#default><#assign layoutWidthIn = 8>
    </#switch>
    <#assign layoutWidthPx = layoutWidthIn * 72>
    <#-- using a 9pt font 6px per character should be plenty (12cpi) - fits for Courier, Helvetica could be less on average (like 4.5) but make sure enough space -->
    <#assign pixelsPerChar = 6>
    <#assign lineCharactersNum = layoutWidthPx / pixelsPerChar>
    <#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
    <#recurse>
    <#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].widgets --></#if>
</#macro>
<#macro "fail-widgets">
    <#if sri.doBoundaryComments()><!-- BEGIN screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
    <#recurse>
    <#if sri.doBoundaryComments()><!-- END   screen[@location=${sri.getActiveScreenDef().location}].fail-widgets --></#if>
</#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">${sri.renderSubscreen()}</#macro>
<#macro "subscreens-panel">${sri.renderSubscreen()}</#macro>

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
<#macro container><#recurse></#macro>
<#macro "container-box">
    <fo:block font-size="12pt" font-weight="bold"><#recurse .node["box-header"][0]></fo:block>
    <#if .node["box-body"]?has_content><#recurse .node["box-body"][0]></#if>
    <#if .node["box-body-nopad"]?has_content><#recurse .node["box-body-nopad"][0]></#if>
</#macro>
<#macro "container-row">
    <#-- TODO: get fancier with xs, sm, md, ld attributes and make actual table columns -->
    <#list .node["row-col"] as rowColNode><#recurse rowColNode></#list>
</#macro>

<#macro "container-panel">
    <#-- NOTE: consider putting header and footer in table spanning 3 columns -->
    <#if .node["panel-header"]?has_content>
    <fo:block><#recurse .node["panel-header"][0]>
    </fo:block>
    </#if>
    <fo:table border="solid black" table-layout="fixed" width="100%">
        <fo:table-body><fo:table-row>
            <#if .node["panel-left"]?has_content>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-left"][0]>
            </fo:block></fo:table-cell></#if>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-center"][0]>
            </fo:block></fo:table-cell>
            <#if .node["panel-right"]?has_content>
            <fo:table-cell padding="3pt"><fo:block><#recurse .node["panel-right"][0]>
            </fo:block></fo:table-cell></#if>
        </fo:table-row></fo:table-body>
    </fo:table>
    <#if .node["panel-footer"]?has_content>
    <fo:block><#recurse .node["panel-footer"][0]>
    </fo:block>
    </#if>
</#macro>
<#macro "container-dialog"><#-- maybe better to do nothing: <#recurse> --></#macro>

<#-- ==================== Includes ==================== -->
<#macro "include-screen">
    <#if sri.doBoundaryComments()><!-- BEGIN include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]!}] --></#if>
    ${sri.renderIncludeScreen(.node["@location"], .node["@share-scope"]!)}
    <#if sri.doBoundaryComments()><!-- END   include-screen[@location=${.node["@location"]}][@share-scope=${.node["@share-scope"]!}] --></#if>
</#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link>
    <fo:block><@linkFormLink .node/></fo:block>
</#macro>
<#macro linkFormLink linkNode>
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
        <#assign urlInstance = sri.makeUrlByType(linkNode["@url"], linkNode["@url-type"]!"transition", linkNode, linkNode["@expand-transition-url"]!"true")>
        <#if linkNode["@url-noparam"]! == "true"><#assign urlText = urlInstance.url/><#else><#assign urlText = urlInstance.urlWithParams/></#if>
        <#assign urlText = urlText?replace("/apps/", "/vapps/")/>
        <fo:basic-link external-destination="${urlText?xml}" color="blue"><@attributeValue linkText/></fo:basic-link>
    </#if>
</#macro>

<#macro image>
    <#-- TODO: make real xsl-fo image -->
    <img src="${sri.makeUrlByType(.node["@url"],.node["@url-type"]!"content",null,"true")}" alt="${.node["@alt"]!"image"}"<#if .node["@id"]?has_content> id="${.node["@id"]}"</#if><#if .node["@width"]?has_content> width="${.node["@width"]}"</#if><#if .node["@height"]?has_content> height="${.node["@height"]}"</#if>/>
</#macro>
<#macro label>
    <#-- TODO: handle label type somehow, ie bigger font, bold, etc for h?, strong -->
    <#if .node["@condition"]?has_content><#assign conditionResult = ec.getResource().condition(.node["@condition"], "")><#else><#assign conditionResult = true></#if>
    <#if conditionResult>
        <#assign textMap = "">
        <#if .node["@text-map"]?has_content><#assign textMap = ec.getResource().expression(.node["@text-map"], "")!></#if>
        <#if textMap?has_content><#assign labelValue = ec.getResource().expand(.node["@text"], "", textMap)>
            <#else><#assign labelValue = ec.getResource().expand(.node["@text"], "")/></#if>
        <#if labelValue?trim?has_content><fo:block>${labelValue?xml}</fo:block></#if>
    </#if>
</#macro>
<#macro parameter><#-- do nothing, used directly in other elements --></#macro>

<#-- ====================================================== -->
<#-- ======================= Form ========================= -->
<#-- ====================================================== -->

<#macro "form-single">
    <#if sri.doBoundaryComments()><!-- BEGIN form-single[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formNode = formInstance.getFormNode()>
    <#t>${sri.pushSingleFormMapContext(formNode["@map"]!"fieldValues")}
    <#assign curFieldWidthIn = layoutWidthIn>
    <#assign inFieldRowBig = false>
    <#if formNode["field-layout"]?has_content>
        <#recurse formNode["field-layout"][0]/>
    <#else>
        <#list formNode["field"] as fieldNode><@formSingleSubField fieldNode/></#list>
    </#if>
    <#t>${sri.popContext()}<#-- context was pushed for the form-single so pop here at the end -->
    <#if sri.doBoundaryComments()><!-- END   form-single[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro "field-ref">
    <#assign fieldRef = .node["@name"]>
    <#assign fieldNode = formInstance.getFieldNode(fieldRef)!>
    <#if fieldNode??>
        <@formSingleSubField fieldNode/>
    <#else>
    <fo:block>Error: could not find field with name ${fieldRef} referred to in a field-ref.@name attribute.</fo:block>
    </#if>
</#macro>
<#macro "fields-not-referenced">
    <#assign nonReferencedFieldList = formInstance.getFieldLayoutNonReferencedFieldList()>
    <#list nonReferencedFieldList as nonReferencedField>
        <@formSingleSubField nonReferencedField/></#list>
</#macro>
<#macro "field-row">
    <#assign frbChildren = .node?children>
    <#assign curFieldWidthIn = layoutWidthIn/frbChildren?size>
    <fo:table table-layout="fixed" width="${layoutWidthIn}in"><fo:table-body><fo:table-row>
    <#list .node?children as rowChildNode>
        <fo:table-cell wrap-option="wrap" padding="2pt" width="${curFieldWidthIn}in">
            <#visit rowChildNode/>
        </fo:table-cell>
    </#list>
    </fo:table-row></fo:table-body></fo:table>
    <#assign curFieldWidthIn = layoutWidthIn>
</#macro>
<#macro "field-row-big">
    <#assign frbChildren = .node?children>
    <#assign inFieldRowBig = true>
    <#if .node["@title"]?has_content><#assign curFieldWidthIn = (layoutWidthIn*0.8)/frbChildren?size>
        <#else><#assign curFieldWidthIn = layoutWidthIn/frbChildren?size></#if>
    <fo:table table-layout="fixed" width="${layoutWidthIn}in"><fo:table-body><fo:table-row>
        <#if .node["@title"]?has_content>
            <fo:table-cell wrap-option="wrap" padding="2pt" width="${layoutWidthIn*0.2}in">
                <fo:block text-align="right" font-weight="bold">${ec.getResource().expand(.node["@title"], "")}</fo:block>
            </fo:table-cell>
        </#if>
        <#list frbChildren as rowChildNode>
            <fo:table-cell wrap-option="wrap" padding="2pt" width="${curFieldWidthIn}in">
                <#visit rowChildNode/>
            </fo:table-cell>
        </#list>
    </fo:table-row></fo:table-body></fo:table>
    <#assign curFieldWidthIn = layoutWidthIn>
    <#assign inFieldRowBig = false>
</#macro>
<#macro "field-group">
    <fo:block font-weight="bold">${ec.getL10n().localize(.node["@title"]!("Fields"))}</fo:block>
    <#recurse .node/>
</#macro>
<#macro "field-accordion"><#recurse .node/></#macro>

<#macro formSingleSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <@formSingleWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formSingleWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content && (fieldSubNode?parent["@hide"]! != "false")><#return></#if>
    <#if fieldSubNode["hidden"]?has_content && (fieldSubNode?parent["@hide"]! != "false")><#recurse fieldSubNode/><#return></#if>
    <#if fieldSubNode?parent["@hide"]! == "true"><#return></#if>
    <#if fieldSubNode["submit"]?has_content><#return></#if>
    <#assign curFieldTitle><@fieldTitle fieldSubNode/></#assign>
    <#assign skipFieldTitle = inFieldRowBig && !curFieldTitle?has_content>
    <fo:table table-layout="fixed" width="${curFieldWidthIn}in"><fo:table-body><fo:table-row>
        <#if !skipFieldTitle><fo:table-cell width="${curFieldWidthIn*0.2}in" padding="2pt"><fo:block text-align="right" font-weight="bold">${curFieldTitle}</fo:block></fo:table-cell></#if>
        <fo:table-cell width="<#if skipFieldTitle>${curFieldWidthIn}<#else>${curFieldWidthIn*0.8}</#if>in" padding="2pt"><fo:block><#recurse fieldSubNode/></fo:block></fo:table-cell>
    </fo:table-row></fo:table-body></fo:table>
</#macro>
<#macro set><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>

<#macro "form-list">
<#if sri.doBoundaryComments()><!-- BEGIN form-list[@name=${.node["@name"]}] --></#if>
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formListInfo = formInstance.makeFormListRenderInfo()>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign formListColumnList = formListInfo.getAllColInfo()>
    <#assign listObject = formListInfo.getListObject(false)!>
    <#assign listName = formNode["@list"]>

    <#assign isMulti = formNode["@multi"]! == "true">
    <#assign isMultiFinalRow = false>
    <#assign urlInfo = sri.makeUrlByType(formNode["@transition"], "transition", null, "false")>
    <#if !listObject?has_content><#return></#if>
    <#assign columnCharWidths = formListInfo.getFormListColumnCharWidths(lineCharactersNum)>

    <#if !(formNode["@paginate"]! == "false") && context[listName + "Count"]?? && (context[listName + "Count"]! > 0)>
        <fo:block>${context[listName + "PageRangeLow"]} - ${context[listName + "PageRangeHigh"]} / ${context[listName + "Count"]}</fo:block>
    </#if>
    <fo:table table-layout="fixed" width="100%">
        <fo:table-header border-bottom="thin solid black">
            <fo:table-row>
                <#list formListColumnList as columnFieldList>
                    <#assign cellCharWidth = columnCharWidths.get(columnFieldList_index)>
                    <#if (cellCharWidth > 0)>
                        <#assign cellPixelWidth = cellCharWidth * pixelsPerChar>
                        <fo:table-cell wrap-option="wrap" padding="2pt" width="${cellPixelWidth}px">
                        <#list columnFieldList as fieldNode>
                            <fo:block text-align="${fieldNode["@align"]!"left"}" font-weight="bold"><@formListHeaderField fieldNode/></fo:block>
                        </#list>
                        </fo:table-cell>
                    </#if>
                </#list>
            </fo:table-row>
        </fo:table-header>
        <fo:table-body>
            <#list listObject as listEntry>
                <#assign listEntryIndex = listEntry_index>
                <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
                ${sri.startFormListRow(formListInfo, listEntry, listEntryIndex, listEntry_has_next)}
                <fo:table-row<#if listEntryIndex % 2 == 0> background-color="#EEEEEE"</#if>>
                    <#list formListColumnList as columnFieldList>
                        <#assign cellCharWidth = columnCharWidths.get(columnFieldList_index)>
                        <#if (cellCharWidth > 0)>
                            <fo:table-cell wrap-option="wrap" padding="2pt">
                            <#list columnFieldList as fieldNode>
                                <@formListSubField fieldNode/>
                            </#list>
                            </fo:table-cell>
                        </#if>
                    </#list>
                </fo:table-row>
                ${sri.endFormListRow()}
            </#list>
        </fo:table-body>
        <#t>${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
    </fo:table>
<#if sri.doBoundaryComments()><!-- END   form-list[@name=${.node["@name"]}] --></#if>
</#macro>
<#macro formListHeaderField fieldNode>
    <#if fieldNode["header-field"]?has_content>
        <#assign fieldSubNode = fieldNode["header-field"][0]>
    <#elseif fieldNode["default-field"]?has_content>
        <#assign fieldSubNode = fieldNode["default-field"][0]>
    <#else>
        <#-- this only makes sense for fields with a single conditional -->
        <#assign fieldSubNode = fieldNode["conditional-field"][0]>
    </#if>
    <@fieldTitle fieldSubNode/>
</#macro>
<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <@formListWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <@formListWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode["submit"]?has_content ||
            fieldSubNode?parent["@hide"]! == "true"><#return></#if>

    <#local fieldNode = fieldSubNode?parent>
    <fo:block text-align="${fieldNode["@align"]!"left"}">
        <#list fieldSubNode?children as widgetNode>
            <#if widgetNode?node_name == "link">
                <#t><@linkFormLink widgetNode/>
            <#else>
                <#t><#visit widgetNode>
            </#if>
        </#list>
    </fo:block>
</#macro>
<#macro "row-actions"><#-- do nothing, these are run by the SRI --></#macro>

<#macro fieldName widgetNode><#assign fieldNode=widgetNode?parent?parent/>${fieldNode["@name"]?html}<#if isMulti?? && isMulti && listEntryIndex??>_${listEntryIndex}</#if></#macro>
<#macro fieldId widgetNode><#assign fieldNode=widgetNode?parent?parent/>${fieldNode?parent["@name"]}_${fieldNode["@name"]}<#if listEntryIndex??>_${listEntryIndex}</#if></#macro>
<#macro fieldTitle fieldSubNode><#assign titleValue><#if fieldSubNode["@title"]?has_content>${ec.getResource().expand(fieldSubNode["@title"], "")}<#else><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>${ec.l10n.localize(titleValue)}</#macro>

<#macro field><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "conditional-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>
<#macro "default-field"><#-- shouldn't be called directly, but just in case --><#recurse/></#macro>

<#-- ========================================================== -->
<#-- ================== Form Field Widgets ==================== -->
<#-- ========================================================== -->

<#macro check>
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValue(.node?parent?parent, "")>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]!/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)?default(currentValue)}</#if>
</#macro>

<#macro "date-find"></#macro>
<#macro "date-period"></#macro>
<#macro "date-time">
    <#assign javaFormat = .node["@format"]!>
    <#if !javaFormat?has_content>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", javaFormat)>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro display>
    <#assign fieldValue = ""/>
    <#if .node["@text"]?has_content>
        <#assign fieldValue = ec.resource.expand(.node["@text"], "")>
        <#if .node["@currency-unit-field"]?has_content>
            <#assign fieldValue = ec.l10n.formatCurrency(fieldValue, ec.resource.expression(.node["@currency-unit-field"], ""))>
        </#if>
    <#elseif .node["@currency-unit-field"]?has_content>
        <#assign fieldValue = ec.l10n.formatCurrency(sri.getFieldValue(.node?parent?parent, ""), ec.resource.expression(.node["@currency-unit-field"], ""))>
    <#else>
        <#assign fieldValue = sri.getFieldValueString(.node)>
    </#if>
    <#t><@attributeValue fieldValue/>
</#macro>
<#macro "display-entity">
    <#assign fieldValue = ""/><#assign fieldValue = sri.getFieldEntityValue(.node)/>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "drop-down">
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]!></#if>
    <#t><@attributeValue (options.get(currentValue))!(currentValue)/>
</#macro>

<#macro file></#macro>
<#macro hidden></#macro>
<#macro ignored><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>
<#macro password></#macro>

<#macro radio>
    <#assign options = {"":""}/><#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node)/>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]!/></#if>
    <#t><#if currentValue?has_content>${options.get(currentValue)!(currentValue)}</#if>
</#macro>

<#macro "range-find"></#macro>
<#macro reset></#macro>

<#macro submit>
    <#assign fieldValue><@fieldTitle .node?parent/></#assign>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-area">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-line">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@attributeValue fieldValue/>
</#macro>

<#macro "text-find">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@attributeValue fieldValue/>
</#macro>
