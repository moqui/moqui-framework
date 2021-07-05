<#assign screenDocList = sri.getScreenUrlInfo().getTargetScreen().getScreenDocumentInfoList()>
<nav class="navbar navbar-inverse"><#-- navbar-fixed-top navbar-static-top -->
  <div class="container-fluid">
    <!-- Brand and toggle get grouped for better mobile display -->
    <header class="navbar-header">
        <button type="button" class="navbar-toggle" data-toggle="collapse" data-target=".navbar-ex1-collapse">
            <span class="sr-only">Toggle navigation</span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
        </button>
        <#assign headerLogoList = sri.getThemeValues("STRT_HEADER_LOGO")>
        <#if headerLogoList?has_content><a href="${sri.buildUrl("/apps").getUrl()}" class="navbar-brand"><img src="${sri.buildUrl(headerLogoList?first).getUrl()}" alt="Home"></a></#if>
        <#assign headerTitleList = sri.getThemeValues("STRT_HEADER_TITLE")>
        <#if headerTitleList?has_content><div class="navbar-brand">${ec.resource.expand(headerTitleList?first, "")}</div></#if>
    </header>
    <div id="navbar-buttons" class="collapse navbar-collapse navbar-ex1-collapse">
        <ul id="header-menus" class="nav navbar-nav">
            <#-- NOTE: menu drop-downs are appended here using JS as subscreens render so this is empty -->
        </ul>
        <div id="navbar-menu-crumbs">
            <#-- NOTE: non-menu bread crumbs are appended here using JS as subscreens render so this is empty -->
        </div>
        <a class="navbar-text" href="${sri.getScreenUrlInstance().getUrlWithParams()}">${html_title!(ec.resource.expand(sri.screenUrlInfo.targetScreen.getDefaultMenuName()!"Page", ""))}</a>
        <#-- logout button -->
        <a href="${sri.buildUrl("/Login/logout").url}" data-toggle="tooltip" data-original-title="${ec.l10n.localize("Logout")} ${(ec.getUser().getUserAccount().userFullName)!}" data-placement="bottom" class="btn btn-danger btn-sm navbar-btn navbar-right">
            <i class="fa fa-power-off"></i>
        </a>
        <#-- screen documentation/help -->
        <#if screenDocList?has_content>
            <div id="document-menu" class="nav navbar-right dropdown">
                <a id="document-menu-link" href="#" class="dropdown-toggle btn btn-info btn-sm navbar-btn" data-toggle="dropdown" title="Documentation">
                    <i class="fa fa-question-circle"></i></a>
                <ul class="dropdown-menu"><#list screenDocList as screenDoc>
                    <li><a href="#" onclick="showScreenDocDialog('${screenDoc.index}')">${screenDoc.title}</a></li>
                </#list></ul>
            </div>
        </#if>
        <#-- dark/light switch -->
        <a href="#" onclick="switchDarkLight();" data-toggle="tooltip" data-original-title="${ec.l10n.localize("Switch Dark/Light")}" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right">
            <i class="fa fa-adjust"></i>
        </a>
        <#-- header navbar items from the theme -->
        <#assign navbarItemList = sri.getThemeValues("STRT_HEADER_NAVBAR_ITEM")>
        <#list navbarItemList! as navbarItem>
            <#assign navbarItemTemplate = navbarItem?interpret>
            <@navbarItemTemplate/>
        </#list>
        <#-- screen history menu -->

        <#assign screenHistoryList = ec.web.getScreenHistory()>
        <div id="history-menu" class="nav navbar-right dropdown">
            <a id="history-menu-link" href="#" class="dropdown-toggle btn btn-default btn-sm navbar-btn" data-toggle="dropdown" title="${ec.l10n.localize("History")}">
                <i class="fa fa-list"></i></a>
            <ul class="dropdown-menu"><#list screenHistoryList as screenHistory><#if (screenHistory_index >= 25)><#break></#if>
                <li><a href="${screenHistory.url}">
                    <#if screenHistory.image?has_content>
                        <#if screenHistory.imageType == "icon">
                            <i class="${screenHistory.image}" style="padding-right: 8px;"></i>
                        <#elseif screenHistory.imageType == "url-plain">
                            <img src="${screenHistory.image}" alt="${screenHistory.name}" width="18" style="padding-right: 4px;"/>
                        <#else>
                            <img src="${sri.buildUrl(screenHistory.image).url}" alt="${screenHistory.name}" height="18" style="padding-right: 4px;"/>
                        </#if>
                    <#else>
                        <i class="fa fa-link" style="padding-right: 8px;"></i>
                    </#if>
                    ${screenHistory.name}
                </a></li>
            </#list></ul>
        </div>
    </div>
  </div>
</nav>

<script>
    $('.navbar [data-toggle="tooltip"]').tooltip({ placement:'bottom', trigger:'hover' });
    $('#history-menu-link').tooltip({ placement:'bottom', trigger:'hover' });
    $('#document-menu-link').tooltip({ placement:'bottom', trigger:'hover' });
    function switchDarkLight() {
        $("body").toggleClass("bg-dark");
        $("body").toggleClass("bg-light");
        var currentStyle = $("body").hasClass("bg-dark") ? "bg-dark" : "bg-light";
        $.ajax({ type:'POST', url:'${sri.buildUrl("/apps/setPreference").url}', data:{ 'moquiSessionToken': '${ec.web.sessionToken}','preferenceKey': 'OUTER_STYLE', 'preferenceValue': currentStyle }, dataType:'json' });
    }
</script>

<#if screenDocList?has_content>
    <#assign screenDocDialogText>
    <div id="screen-document-dialog" class="modal dynamic-dialog" aria-hidden="true" style="display: none;" tabindex="-1">
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-hidden="true">&times;</button>
                    <h4 class="modal-title">${ec.l10n.localize("Documentation")}</h4>
                </div>
                <div class="modal-body" id="screen-document-dialog-body">
                    <img src="/images/wait_anim_16x16.gif" alt="Loading...">
                </div>
                <div class="modal-footer"><button type="button" class="btn btn-primary" data-dismiss="modal">${ec.l10n.localize("Close")}</button></div>
            </div>
        </div>
    </div>
    <script>
        function showScreenDocDialog(docIndex) {
            $("#screen-document-dialog").modal("show");
            $("#screen-document-dialog-body").load('${sri.buildUrlFromTarget("screenDoc").url}?docIndex=' + docIndex);
        }
        $("#screen-document-dialog").on("hidden.bs.modal", function () { var jqEl = $("#screen-document-dialog-body"); jqEl.empty(); jqEl.append('<img src="/images/wait_anim_16x16.gif" alt="Loading...">'); });
    </script>
    </#assign>
    <#t>${sri.appendToAfterScreenWriter(screenDocDialogText)}
</#if>
