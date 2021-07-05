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

<#--
    Print data persisted in a FormResponse record with FormResponseAnswer records

    Accepts a formResponseInfoList where each Map in the List is the result from the
        org.moqui.impl.ScreenServices.get#FormResponse service
-->

<#macro formResponse formResponseInfo fieldsPage>
    <#assign dbForm = formResponseInfo.dbForm>
    <#assign dbFormFieldList = formResponseInfo.dbFormFieldList>
    <#assign responseMap = formResponseInfo.responseMap>

    <#list dbFormFieldList as dbFormField><#if !dbFormField.printPageNumber?has_content || dbFormField.printPageNumber == fieldsPage>
        <#assign responseValue = responseMap[dbFormField.fieldName]!"">
        <#if responseValue?contains("\n")><#assign responseValue><#list responseValue?split("\n") as responseValuePart>
            <fo:block>${responseValuePart}</fo:block>
        </#list></#assign></#if>
        <fo:block-container absolute-position="absolute"<#if dbFormField.printTop?has_content> top="${dbFormField.printTop}"</#if><#if dbFormField.printLeft?has_content> left="${dbFormField.printLeft}"</#if><#if dbFormField.printBottom?has_content> bottom="${dbFormField.printBottom}"</#if><#if dbFormField.printRight?has_content> right="${dbFormField.printRight}"</#if><#if dbFormField.printWidth?has_content> width="${dbFormField.printWidth}"</#if><#if dbFormField.printHeight?has_content> height="${dbFormField.printHeight}"</#if>>
            <fo:block<#if dbFormField.printTextAlign?has_content> text-align="${dbFormField.printTextAlign}"</#if><#if dbFormField.printVerticalAlign?has_content> vertical-align="${dbFormField.printVerticalAlign}"</#if><#if dbFormField.printFontSize?has_content> font-size="${dbFormField.printFontSize}"</#if><#if dbFormField.printFontFamily?has_content> font-family="${dbFormField.printFontFamily}"</#if>>${responseValue}</fo:block>
        </fo:block-container>
    </#if></#list>

    <#-- TODO handle file/image field types
    <#if paymentInfo.paymentSignaturePrimaryLocation?has_content>
        <fo:external-graphic src="${paymentInfo.paymentSignaturePrimaryLocation}" content-height="0.33in" content-width="scale-to-fit" scaling="uniform"/>
    </#if>
     -->
</#macro>

<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Courier, monospace" font-size="9pt">
    <fo:layout-master-set>
        <fo:simple-page-master master-name="letter-portrait" page-width="8.5in" page-height="11in"
                               margin-top="0in" margin-bottom="0in" margin-left="0in" margin-right="0in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="1in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
    </fo:layout-master-set>

    <#list formResponseInfoList as formResponseInfo>
        <#assign printRepeatCount = formResponseInfo.dbForm.printRepeatCount!1>
        <#assign useNewPage = formResponseInfo.dbForm.printRepeatNewPage!"N" == "Y">
        <fo:page-sequence master-reference="letter-portrait" font-size="${formResponseInfo.dbForm.printFontSize!"9pt"}" font-family="${formResponseInfo.dbForm.printFontFamily!"Courier, monospace"}">
            <fo:flow flow-name="xsl-region-body">
        <#list 1..printRepeatCount as repeatNum>
            <#list 1..formResponseInfo.dbFormFieldPages as fieldsPage>
                <#if formResponseInfo.dbForm.printContainerWidth?has_content>
                    <fo:inline-container width="${formResponseInfo.dbForm.printContainerWidth}" height="${formResponseInfo.dbForm.printContainerHeight!"11in"}">
                        <@formResponse formResponseInfo fieldsPage/>
                    </fo:inline-container>
                <#else>
                    <fo:block-container width="8.5in" height="${formResponseInfo.dbForm.printContainerHeight!"11in"}">
                        <@formResponse formResponseInfo fieldsPage/>
                    </fo:block-container>
                </#if>
                <#if fieldsPage_has_next>
                    </fo:flow>
                </fo:page-sequence>
                <fo:page-sequence master-reference="letter-portrait" font-size="${formResponseInfo.dbForm.printFontSize!"9pt"}" font-family="${formResponseInfo.dbForm.printFontFamily!"Courier, monospace"}">
                    <fo:flow flow-name="xsl-region-body">
                </#if>
            </#list>
            <#if useNewPage>
                </fo:flow>
            </fo:page-sequence>
            <fo:page-sequence master-reference="letter-portrait" font-size="${formResponseInfo.dbForm.printFontSize!"9pt"}" font-family="${formResponseInfo.dbForm.printFontFamily!"Courier, monospace"}">
                <fo:flow flow-name="xsl-region-body">
            </#if>
        </#list>
            </fo:flow>
        </fo:page-sequence>
    </#list>
</fo:root>
