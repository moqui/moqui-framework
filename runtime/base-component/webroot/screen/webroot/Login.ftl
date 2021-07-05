<div id="browser-warning" class="hidden text-center" style="margin-bottom: 80px;">
    <h4 class="text-danger">Your browser is not supported, please use a recent version of one of the following:</h4>
    <div class="row" style="font-size: 4em;">
        <div class="col-sm-2"></div>
        <div class="col-sm-2"><a href="https://www.google.com/chrome/"><i class="fa fa-chrome"></i></a></div>
        <div class="col-sm-2"><a href="https://www.mozilla.org/firefox/"><i class="fa fa-firefox"></i></a></div>
        <div class="col-sm-2"><a href="https://www.apple.com/safari/"><i class="fa fa-safari"></i></a></div>
        <div class="col-sm-2"><a href="https://www.microsoft.com/windows/microsoft-edge"><i class="fa fa-edge"></i></a></div>
        <div class="col-sm-2"></div>
    </div>
</div>
<!-- currently general/common HTML5 and ES5 support is currently required, so check for IE and older browsers -->
<!-- TODO: check for older versions of various browsers, or for HTML5 features like input/etc.@form attribute, ES5 stuff -->
<script>
    var UA = window.navigator.userAgent.toLowerCase();
    var isIE = UA && /msie|trident/.test(UA);
    if (isIE) $("#browser-warning").removeClass("hidden");
</script>

<div class="tab-content">
    <div id="login" class="tab-pane active">
        <form method="post" action="${sri.buildUrl("login").url}" class="form-signin" id="login_form">
            <p class="text-muted text-center">${ec.l10n.localize("Enter your username and password to sign in")}</p>
            <#-- not needed for this request: <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}"> -->
            <input type="text" name="username" value="${((ec.getWeb().getErrorParameters().get("username"))!"")?html}" placeholder="${ec.l10n.localize("Username")}" required="required" class="form-control top" id="login_form_username">
            <input type="password" name="password" placeholder="${ec.l10n.localize("Password")}" required="required" class="form-control bottom">
            <button class="btn btn-lg btn-primary btn-block" type="submit">${ec.l10n.localize("Sign in")}</button>
        </form>
        <script>$("#login_form_username").focus();</script>
    </div>
    <div id="reset" class="tab-pane">
        <form method="post" action="${sri.buildUrl("resetPassword").url}" class="form-signin" id="reset_form">
            <p class="text-muted text-center">${ec.l10n.localize("Enter your username to reset and email your password")}</p>
            <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
            <input type="text" name="username" placeholder="${ec.l10n.localize("Username")}" required="required" class="form-control">
            <button class="btn btn-lg btn-danger btn-block" type="submit">${ec.l10n.localize("Reset &amp; Email Password")}</button>
        </form>
    </div>
    <div id="change" class="tab-pane">
        <form method="post" action="${sri.buildUrl("changePassword").url}" class="form-signin" id="change_form">
            <p class="text-muted text-center">${ec.l10n.localize("Enter details to change your password")}</p>
            <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
            <input type="text" name="username" value="${(ec.getWeb().getErrorParameters().get("username"))!""}" placeholder="${ec.l10n.localize("Username")}" required="required" class="form-control top">
            <input type="password" name="oldPassword" placeholder="${ec.l10n.localize("Old Password")}" required="required" class="form-control middle">
            <input type="password" name="newPassword" placeholder="${ec.l10n.localize("New Password")}" required="required" class="form-control middle">
            <input type="password" name="newPasswordVerify" placeholder="${ec.l10n.localize("New Password Verify")}" required="required" class="form-control bottom">
            <button class="btn btn-lg btn-danger btn-block" type="submit">${ec.l10n.localize("Change Password")}</button>
        </form>
    </div>
</div>
<div class="text-center">
    <ul class="list-inline">
        <li><a class="text-muted" href="#login" data-toggle="tab">${ec.l10n.localize("Login")}</a></li>
        <li><a class="text-muted" href="#reset" data-toggle="tab">${ec.l10n.localize("Reset Password")}</a></li>
        <li><a class="text-muted" href="#change" data-toggle="tab">${ec.l10n.localize("Change Password")}</a></li>
    </ul>
</div>
