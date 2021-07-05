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
<#assign baseUrlInfo = sri.buildUrlInfo("AutoEditDetail")>
<div id="auto-menu">
    <ul id="auto-edit-tabs" class="nav nav-tabs" role="tablist">
        <#assign urlInstance = sri.buildUrlInfo("AutoEditMaster").getInstance(sri, false).addParameter("aen", aen).addParameters(masterPrimaryKeyMap).removeParameter("den")>
        <li class="<#if urlInstance.inCurrentScreenPath>active</#if>">
            <a href="${urlInstance.minimalPathUrlWithParams}">${ec.entity.getEntityDefinition(aen).getPrettyName(null, null)}</a></li>
    <#list relationshipInfoList as relationshipInfo>
        <#assign curKeyMap = relationshipInfo.getTargetParameterMap(context)>
        <#if curKeyMap?has_content>
            <#assign urlInstance = baseUrlInfo.getInstance(sri, false).addParameters(masterPrimaryKeyMap)
                    .addParameter("den", relationshipInfo.riRelatedEntityName()).addParameter("aen", aen).addParameters(curKeyMap)>
            <li class="<#if urlInstance.inCurrentScreenPath && relationshipInfo.riRelatedEntityName() == den!>active</#if>">
                <a href="${urlInstance.minimalPathUrlWithParams}">${relationshipInfo.riPrettyName()}</a></li>
        </#if>
    </#list>
    </ul>
    ${sri.renderSubscreen()}
</div>
