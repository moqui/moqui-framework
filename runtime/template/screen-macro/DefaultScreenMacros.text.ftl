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
<#-- truncate or pad the textValue plus one space at the end so it is exactly characters chars long -->
<#macro paddedValue textValue characters=cellCharWidth!lineCharactersNum!0 leftPad=cellLeftPad!false wrapLine=cellWrapLine!0>
    <#if characters == 0><#return></#if>
    <#assign textLength = textValue?length>
    <#assign startChar = wrapLine * characters>
    <#assign endChar = (wrapLine + 1) * characters>
    <#if (textLength > endChar)><#assign cellWrapOverflow = true></#if>
    <#if (endChar > textLength)><#assign endChar = textLength></#if>
    <#if (startChar >= textLength)><#assign outValue = ""><#else><#assign outValue = textValue?substring(startChar, endChar)></#if>
    <#if (outValue?length < characters)>
        <#if leftPad><#assign outValue = outValue?left_pad(characters)>
            <#else><#assign outValue = outValue?right_pad(characters)></#if>
    </#if>
    <#t>${outValue}
</#macro>
<#macro @element></#macro>
<#macro screen><#recurse></#macro>
<#macro widgets>
    <#if !lineCharacters?has_content><#assign lineCharacters = "132"></#if>
    <#assign lineCharactersNum = lineCharacters?number>
    <#-- NOTE: pageLines is optional, if 0 don't do page breaks -->
    <#if pageLines?has_content><#assign pageLinesNum = pageLines?number><#else><#assign pageLinesNum = 0></#if>
    <#assign lineWrapBool = ("true" == lineWrap!)>
    <#recurse>
</#macro>
<#macro "fail-widgets"><#recurse></#macro>

<#-- ================ Subscreens ================ -->
<#macro "subscreens-menu"></#macro>
<#macro "subscreens-active">${sri.renderSubscreen()}</#macro>
<#macro "subscreens-panel">${sri.renderSubscreen()}</#macro>

<#-- ================ Section ================ -->
<#macro section>${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-iterate">${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-include">${sri.renderSection(.node["@name"])}</#macro>

<#-- ================ Containers ================ -->
<#macro container><#recurse><#-- new line after container -->

</#macro>

<#macro "container-box">
    <#assign cellCharWidth = lineCharactersNum>
    <#recurse .node["box-header"][0]>
    <#assign cellCharWidth = 0>

    <#if .node["box-body"]?has_content><#recurse .node["box-body"][0]></#if>
    <#if .node["box-body-nopad"]?has_content><#recurse .node["box-body-nopad"][0]></#if>
</#macro>
<#macro "container-row"><#list .node["row-col"] as rowColNode><#recurse rowColNode></#list>
</#macro>
<#macro "container-panel">
    <#if .node["panel-header"]?has_content><#recurse .node["panel-header"][0]></#if>
    <#if .node["panel-left"]?has_content><#recurse .node["panel-left"][0]></#if>
    <#recurse .node["panel-center"][0]>
    <#if .node["panel-right"]?has_content><#recurse .node["panel-right"][0]></#if>
    <#if .node["panel-footer"]?has_content><#recurse .node["panel-footer"][0]></#if>
</#macro>
<#macro "container-dialog">${ec.resource.expand(.node["@button-text"], "")} </#macro>

<#-- ================== Standalone Fields ==================== -->
<#macro link><#if .node?parent?node_name?ends_with("-field") && (.node["@link-type"]! == "anchor" || .node["@link-type"]! == "hidden-form-link")>
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
        <#t><@paddedValue linkText/>
    </#if>
</#if></#macro>

<#macro image><@paddedValue .node["@alt"]!""/></#macro>
<#macro label><#assign labelValue = ec.resource.expand(.node["@text"], "")><@paddedValue labelValue/></#macro>
<#macro parameter><#-- do nothing, used directly in other elements --></#macro>

<#-- ====================================================== -->
<#-- ======================= Form ========================= -->
<#macro "form-single">
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formNode = sri.getFormNode(.node["@name"])>
    <#t>${sri.pushSingleFormMapContext(formNode["@map"]!"fieldValues")}
    <#list formNode["field"] as fieldNode>
        <#t><@formSingleSubField fieldNode/>${"\n"}
    </#list>
    <#t>${sri.popContext()}<#-- context was pushed for the form-single so pop here at the end -->

</#macro>
<#macro formSingleSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <#t><@formSingleWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#t><@formSingleWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formSingleWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode["submit"]?has_content ||
            fieldSubNode?parent["@hide"]! == "true"><#return></#if>
    <#assign curTitle><@fieldTitle fieldSubNode/></#assign>
    <#assign cellCharWidth = lineCharactersNum*0.8>
    <#t><@paddedValue curTitle lineCharactersNum*0.2 true/>: <#recurse fieldSubNode/>
</#macro>

<#macro headerRows formListColumnList columnCharWidths>
    <#list 0..5 as hdrFieldInColIndex>
        <#assign hasMoreFields = false>
        <#list formListColumnList as columnFieldList>
            <#assign cellCharWidth = columnCharWidths.get(columnFieldList_index)>
            <#if (cellCharWidth > 0)>
                <#t><#if (columnFieldList_index > 0)>|</#if>
                <#assign curColumnFieldSize = columnFieldList.size()>
                <#if (hdrFieldInColIndex >= curColumnFieldSize)>
                    <#t>${" "?left_pad(cellCharWidth)}
                <#else>
                    <#assign fieldNode = columnFieldList.get(hdrFieldInColIndex)!>
                    <#assign cellLeftPad = (fieldNode["@align"]! == "right" || fieldNode["@align"]! == "center")>
                    <#t><@formListHeaderField fieldNode cellCharWidth cellLeftPad/>
                </#if>
                <#if (curColumnFieldSize > (hdrFieldInColIndex + 1))><#assign hasMoreFields = true></#if>
            </#if>
        </#list>
        <#t>${"\n"}
        <#assign lineCount = lineCount+1>
        <#if !hasMoreFields><#break></#if>
    </#list>
    <#list formListColumnList as columnFieldList>
        <#assign cellCharWidth = columnCharWidths.get(columnFieldList_index)>
        <#if (cellCharWidth > 0)>
            <#t><#if (columnFieldList_index > 0)>+</#if>
            <#t><#list 1..cellCharWidth as charNum>-</#list>
        </#if>
    </#list>
    <#t>${"\n"}
    <#assign lineCount = lineCount+1>
</#macro>
<#macro "form-list">
    <#-- Use the formNode assembled based on other settings instead of the straight one from the file: -->
    <#assign formInstance = sri.getFormInstance(.node["@name"])>
    <#assign formListInfo = formInstance.makeFormListRenderInfo()>
    <#assign formNode = formListInfo.getFormNode()>
    <#assign formListColumnList = formListInfo.getAllColInfo()>
    <#assign listObject = formListInfo.getListObject(false)!>
    <#assign listName = formNode["@list"]>
    <#assign columnCharWidths = formListInfo.getFormListColumnCharWidths(lineCharactersNum)>
    <#-- <#t><#list 1..lineCharactersNum as charNum><#assign charNumMod10 = charNum % 10><#if charNumMod10 == 0>*<#else>${charNumMod10}</#if></#list> -->
    <#assign lineCount = 1>
    <@headerRows formListColumnList columnCharWidths/>
    <#list listObject as listEntry>
        <#assign listEntryIndex = listEntry_index>
        <#-- NOTE: the form-list.@list-entry attribute is handled in the ScreenForm class through this call: -->
        <#t>${sri.startFormListRow(formListInfo, listEntry, listEntryIndex, listEntry_has_next)}
        <#list 0..5 as fieldInColIndex>
            <#assign hasMoreFields = false>
            <#list 0..10 as lineWrapCounter>
                <#assign cellWrapOverflow = false>
                <#assign cellWrapLine = lineWrapCounter>
                <#list formListColumnList as columnFieldList>
                    <#assign cellCharWidth = columnCharWidths.get(columnFieldList_index)>
                    <#if (cellCharWidth > 0)>
                        <#t><#if (columnFieldList_index > 0)>|</#if>
                        <#assign curColumnFieldSize = columnFieldList.size()>
                        <#if (fieldInColIndex >= curColumnFieldSize)>
                            <#t>${" "?left_pad(cellCharWidth)}
                        <#else>
                            <#assign fieldNode = columnFieldList.get(fieldInColIndex)!>
                            <#assign cellLeftPad = (fieldNode["@align"]! == "right" || fieldNode["@align"]! == "center")>
                            <#t><@formListSubField fieldNode/>
                        </#if>
                        <#if (curColumnFieldSize > (fieldInColIndex + 1))><#assign hasMoreFields = true></#if>
                    </#if>
                </#list>
                <#t><#-- :${lineCount} -->${"\n"}
                <#if (pageLinesNum > 0 && lineCount == pageLinesNum)>
                    <#assign lineCount = 1>
                    <@headerRows formListColumnList columnCharWidths/>
                <#else>
                    <#assign lineCount = lineCount+1>
                </#if>
                <#if !cellWrapOverflow || !lineWrapBool><#break></#if>
            </#list>
            <#if !hasMoreFields><#break></#if>
        </#list>
        <#t>${sri.endFormListRow()}
    </#list>
    <#t>${sri.safeCloseList(listObject)}<#-- if listObject is an EntityListIterator, close it -->
    <#t>${"\n"}
</#macro>
<#macro formListHeaderField fieldNode cellCharWidth cellLeftPad>
    <#if fieldNode["header-field"]?has_content>
        <#assign fieldSubNode = fieldNode["header-field"][0]>
    <#elseif fieldNode["default-field"]?has_content>
        <#assign fieldSubNode = fieldNode["default-field"][0]>
    <#else>
        <#-- this only makes sense for fields with a single conditional -->
        <#assign fieldSubNode = fieldNode["conditional-field"][0]>
    </#if>
    <#assign curTitle><@fieldTitle fieldSubNode/></#assign>
    <#t><@paddedValue curTitle cellCharWidth!0 cellLeftPad!false 0/>
</#macro>
<#macro formListSubField fieldNode>
    <#list fieldNode["conditional-field"] as fieldSubNode>
        <#if ec.resource.condition(fieldSubNode["@condition"], "")>
            <#t><@formListWidget fieldSubNode/>
            <#return>
        </#if>
    </#list>
    <#if fieldNode["default-field"]?has_content>
        <#t><@formListWidget fieldNode["default-field"][0]/>
        <#return>
    </#if>
</#macro>
<#macro formListWidget fieldSubNode>
    <#if fieldSubNode["ignored"]?has_content || fieldSubNode["hidden"]?has_content || fieldSubNode["submit"]?has_content ||
            fieldSubNode?parent["@hide"]! == "true"><#return></#if>
    <#t><#recurse fieldSubNode>
</#macro>
<#macro "row-actions"><#-- do nothing, these are run by the SRI --></#macro>

<#macro fieldTitle fieldSubNode><#assign titleValue><#if fieldSubNode["@title"]?has_content>${fieldSubNode["@title"]}<#else><#list fieldSubNode?parent["@name"]?split("(?=[A-Z])", "r") as nameWord>${nameWord?cap_first?replace("Id", "ID")}<#if nameWord_has_next> </#if></#list></#if></#assign>${ec.l10n.localize(titleValue)}</#macro>

<#macro "field"><#-- shouldn't be called directly, but just in case --><#recurse></#macro>
<#macro "conditional-field"><#-- shouldn't be called directly, but just in case --><#recurse></#macro>
<#macro "default-field"><#-- shouldn't be called directly, but just in case --><#recurse></#macro>

<#-- ================== Form Field Widgets ==================== -->

<#macro "check">
    <#assign options = sri.getFieldOptions(.node)!>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#if !currentValue?has_content><#assign currentValue = ec.getResource().expandNoL10n(.node["@no-current-selected-key"]!, "")/></#if>
    <#t><@paddedValue (options.get(currentValue))!(currentValue)/>
</#macro>

<#macro "date-find"></#macro>
<#macro "date-time">
    <#assign javaFormat = .node["@format"]!>
    <#if !javaFormat?has_content>
        <#if .node["@type"]! == "time"><#assign javaFormat="HH:mm">
        <#elseif .node["@type"]! == "date"><#assign javaFormat="yyyy-MM-dd">
        <#else><#assign javaFormat="yyyy-MM-dd HH:mm"></#if>
    </#if>
    <#assign fieldValue = sri.getFieldValueString(.node?parent?parent, .node["@default-value"]!"", javaFormat)>
    <#t><@paddedValue fieldValue/>
</#macro>

<#macro "display">
    <#assign fieldValue = "">
    <#assign dispFieldNode = .node?parent?parent>
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
    <#t><@paddedValue fieldValue/>
</#macro>
<#macro "display-entity">
    <#assign fieldValue = sri.getFieldEntityValue(.node)>
    <#t><@paddedValue fieldValue/>
</#macro>

<#macro "drop-down">
    <#assign options = sri.getFieldOptions(.node)>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]!></#if>
    <#t><@paddedValue (options.get(currentValue))!(currentValue)/>
</#macro>

<#macro "file"></#macro>
<#macro "hidden"></#macro>
<#macro "ignored"><#-- shouldn't ever be called as it is checked in the form-* macros --></#macro>
<#macro "password"></#macro>

<#macro "radio">
    <#assign options = sri.getFieldOptions(.node)!>
    <#assign currentValue = sri.getFieldValueString(.node)>
    <#if !currentValue?has_content><#assign currentValue = .node["@no-current-selected-key"]!></#if>
    <#t><@paddedValue (options.get(currentValue))!(currentValue)/>
</#macro>

<#macro "range-find"></#macro>
<#macro "reset"></#macro>

<#macro "submit">
    <#assign fieldValue><@fieldTitle .node?parent/></#assign>
    <#t><@paddedValue fieldValue/>
</#macro>

<#macro "text-area">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@paddedValue fieldValue/>
</#macro>

<#macro "text-line">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@paddedValue fieldValue/>
</#macro>

<#macro "text-find">
    <#assign fieldValue = sri.getFieldValueString(.node)>
    <#t><@paddedValue fieldValue/>
</#macro>
