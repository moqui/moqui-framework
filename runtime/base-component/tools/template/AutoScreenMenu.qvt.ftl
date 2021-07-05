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
    <q-tabs id="auto-edit-tabs" dense no-caps align="left" active-color="primary" indicator-color="primary" :value="$root.currentParameters.den||'current'">
        <#assign urlInstance = sri.buildUrlInfo("AutoEditMaster").getInstance(sri, false).addParameter("aen", aen).addParameters(masterPrimaryKeyMap).removeParameter("den")>
        <q-tab name="current" label="${ec.entity.getEntityDefinition(aen).getPrettyName(null, null)}" @click="$root.setUrl('${urlInstance.pathWithParams}')"></q-tab>
        <#list relationshipInfoList as relationshipInfo>
            <#assign curKeyMap = relationshipInfo.getTargetParameterMap(context)>
            <#if curKeyMap?has_content>
                <#assign urlInstance = baseUrlInfo.getInstance(sri, false).addParameters(masterPrimaryKeyMap)
                        .addParameter("den", relationshipInfo.riRelatedEntityName()).addParameter("aen", aen).addParameters(curKeyMap)>
                <q-tab name="${relationshipInfo.riRelatedEntityName()}" label="${relationshipInfo.riPrettyName()}" @click="$root.setUrl('${urlInstance.pathWithParams}')"></q-tab>
            </#if>
        </#list>
    </q-tabs>
    <q-separator class="q-mb-md"></q-separator>
    <#--
    <ul id="auto-edit-tabs" class="nav nav-tabs" role="tablist">
        <li :class="{active:!this.$root.currentParameters.den}">
            <m-link href="${urlInstance.pathWithParams}">${ec.entity.getEntityDefinition(aen).getPrettyName(null, null)}</m-link></li>
    <#list relationshipInfoList as relationshipInfo>
        <#assign curKeyMap = relationshipInfo.getTargetParameterMap(context)>
        <#if curKeyMap?has_content>
            <#assign urlInstance = baseUrlInfo.getInstance(sri, false).addParameters(masterPrimaryKeyMap)
                    .addParameter("den", relationshipInfo.riRelatedEntityName()).addParameter("aen", aen).addParameters(curKeyMap)>
            <li :class="{active:this.$root.currentParameters.den=='${relationshipInfo.riRelatedEntityName()}'}">
                <m-link href="${urlInstance.pathWithParams}">${relationshipInfo.riPrettyName()}</m-link></li>
        </#if>
    </#list>
    </ul>
    -->
    <m-subscreens-active></m-subscreens-active>
</div>
