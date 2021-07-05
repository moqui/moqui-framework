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

<#-- ==================== Includes ==================== -->
<#macro "include-screen">${sri.renderIncludeScreen(.node["@location"], .node["@share-scope"]!)}</#macro>

<#-- ============== Render Mode Elements ============== -->
<#macro "render-mode">
    <#if .node["text"]?has_content>
        <#list .node["text"] as textNode><#if !textNode["@type"]?has_content || textNode["@type"] == "any"><#local textToUse = textNode/></#if></#list>
        <#list .node["text"] as textNode><#if textNode["@type"]?has_content && textNode["@type"]?split(",")?seq_contains(sri.getRenderMode())><#local textToUse = textNode></#if></#list>
        <#if textToUse??><@renderText textNode=textToUse/></#if>
    </#if>
</#macro>
<#macro text>
    <#if !.node["@type"]?has_content || (.node["@type"]?split(",")?seq_contains(sri.getRenderMode()))><@renderText textNode=.node/></#if>
</#macro>
<#macro renderText textNode>
    <#if textNode["@location"]?has_content>
        <#assign textLocation = ec.getResource().expandNoL10n(textNode["@location"], "")>
        <#if sri.doBoundaryComments() && textNode["@no-boundary-comment"]! != "true"><!-- BEGIN render-mode.text[@location=${textLocation}][@template=${textNode["@template"]!"true"}] --></#if>
        <#-- NOTE: this still won't encode templates that are rendered to the writer -->
        <#if .node["@encode"]! == "true">${sri.renderText(textLocation, textNode["@template"]!)?html}<#else>${sri.renderText(textLocation, textNode["@template"]!)}</#if>
        <#if sri.doBoundaryComments() && textNode["@no-boundary-comment"]! != "true"><!-- END   render-mode.text[@location=${textLocation}][@template=${textNode["@template"]!"true"}] --></#if>
    </#if>
    <#assign inlineTemplateSource = textNode.@@text!>
    <#if inlineTemplateSource?has_content>
        <#if sri.doBoundaryComments() && textNode["@no-boundary-comment"]! != "true"><!-- BEGIN render-mode.text[inline][@template=${textNode["@template"]!"true"}] --></#if>
        <#if !textNode["@template"]?has_content || textNode["@template"] == "true">
            <#assign inlineTemplate = [inlineTemplateSource, sri.getActiveScreenDef().location + ".render_mode.text"]?interpret>
            <@inlineTemplate/>
        <#else>
            <#if .node["@encode"]! == "true">${inlineTemplateSource?html}<#else>${inlineTemplateSource}</#if>
        </#if>
        <#if sri.doBoundaryComments() && textNode["@no-boundary-comment"]! != "true"><!-- END   render-mode.text[inline][@template=${textNode["@template"]!"true"}] --></#if>
    </#if>
</#macro>

