${sri.getAfterScreenWriterText()}

<#-- Footer JavaScript -->
<#list footer_scripts?if_exists as scriptLocation>
    <#assign srcUrl = sri.buildUrl(scriptLocation).url>
    <script src="${srcUrl}<#if !scriptLocation?starts_with("http") && !srcUrl?contains("?")>?v=${ec.web.getResourceDistinctValue()}</#if>" type="text/javascript"></script>
</#list>
<#assign scriptText = sri.getScriptWriterText()>
<#if scriptText?has_content>
    <script>
    ${scriptText}
    $(window).on('unload', function(){}); // Does nothing but break the bfcache
    </script>
</#if>
</body>
</html>
