<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" font-family="Helvetica, sans-serif" font-size="9pt">
    <fo:layout-master-set>
        <#-- Letter -->
        <fo:simple-page-master master-name="letter-portrait" page-width="8.5in" page-height="11in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/><#-- margin-top="0.5in" margin-bottom="0.5in" -->
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
        <fo:simple-page-master master-name="letter-landscape" page-width="11in" page-height="8.5in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
        <#-- Legal -->
        <fo:simple-page-master master-name="legal-portrait" page-width="8.5in" page-height="14in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
        <fo:simple-page-master master-name="legal-landscape" page-width="14in" page-height="8.5in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
        <#-- Tabloid, Double-Letter (11x17) -->
        <fo:simple-page-master master-name="tabloid-portrait" page-width="11in" page-height="17in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>
        <fo:simple-page-master master-name="tabloid-landscape" page-width="17in" page-height="11in"
                               margin-top="0.25in" margin-bottom="0.25in" margin-left="0.25in" margin-right="0.25in">
            <fo:region-body margin-top="0in" margin-bottom="0in"/>
            <#-- <fo:region-before extent="0.5in"/><fo:region-after extent="0.5in"/> -->
        </fo:simple-page-master>

        <#-- ISO 216 A4 -->
        <fo:simple-page-master master-name="a4-portrait" page-width="210mm" page-height="297mm"
                               margin-top="15mm" margin-bottom="15mm" margin-left="15mm" margin-right="15mm">
            <fo:region-body margin-top="0mm" margin-bottom="0mm"/>
        </fo:simple-page-master>
        <fo:simple-page-master master-name="a4-landscape" page-width="297mm" page-height="210mm"
                               margin-top="15mm" margin-bottom="15mm" margin-left="15mm" margin-right="15mm">
            <fo:region-body margin-top="0mm" margin-bottom="0mm"/>
        </fo:simple-page-master>
        <#-- ISO 216 A3 -->
        <fo:simple-page-master master-name="a3-portrait" page-width="297mm" page-height="420mm"
                               margin-top="15mm" margin-bottom="15mm" margin-left="15mm" margin-right="15mm">
            <fo:region-body margin-top="0mm" margin-bottom="0mm"/>
        </fo:simple-page-master>
        <fo:simple-page-master master-name="a3-landscape" page-width="420mm" page-height="297mm"
                               margin-top="15mm" margin-bottom="15mm" margin-left="15mm" margin-right="15mm">
            <fo:region-body margin-top="0mm" margin-bottom="0mm"/>
        </fo:simple-page-master>
    </fo:layout-master-set>

    <fo:page-sequence master-reference="${layoutMaster!"letter-portrait"}">
        <#-- better to have more space for actual content, these aren't really all that useful:
        <fo:static-content flow-name="xsl-region-before">
            <fo:block font-size="14pt" text-align="center" margin-bottom="14pt" border-bottom="thin solid black">
                ${documentTitle?if_exists?xml}
            </fo:block>
        </fo:static-content>
        <fo:static-content flow-name="xsl-region-after" font-size="8pt">
            <fo:block text-align="center" border-top="thin solid black">- <fo:page-number/> -</fo:block>
        </fo:static-content>
        -->

        <fo:flow flow-name="xsl-region-body">
