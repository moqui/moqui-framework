/* This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License. */

// some globals for all Vue components to directly use the moqui object (for methods, constants, etc) and the window object
Vue.prototype.moqui = moqui;
Vue.prototype.moment = moment;
Vue.prototype.window = window;

moqui.urlExtensions = { js:'qjs', vue:'qvue', vuet:'qvt' }

// simple stub for define if it doesn't exist (ie no require.js, etc); mimic pattern of require.js define()
if (!window.define) window.define = function(name, deps, callback) {
    if (!moqui.isString(name)) { callback = deps; deps = name; name = null; }
    if (!moqui.isArray(deps)) { callback = deps; deps = null; }
    if (moqui.isFunction(callback)) { return callback(); } else { return callback }
};
Vue.filter('decodeHtml', moqui.htmlDecode);
Vue.filter('format', moqui.format);

moqui.getQuasarColor = function(bootstrapColor) {
    // Quasar colors (https://quasar.dev/style/color-palette): primary, secondary, accent, dark, positive, negative, info, warning
    // success => positive, danger => negative
    if (bootstrapColor === 'success') return 'positive';
    if (bootstrapColor === 'danger') return 'negative';
    return bootstrapColor;
};

/* ========== notify and error handling ========== */
moqui.notifyOpts = { timeout:1500, type:'positive' };
moqui.notifyOptsInfo = { timeout:5000, type:'info' };
moqui.notifyOptsError = { timeout:15000, type:'negative' };
moqui.notifyMessages = function(messages, errors, validationErrors) {
    var notified = false;
    if (messages) {
        if (moqui.isArray(messages)) {
            for (var mi=0; mi < messages.length; mi++) {
                var messageItem = messages[mi];
                if (moqui.isPlainObject(messageItem)) {
                    var msgType = moqui.getQuasarColor(messageItem.type);
                    if (!msgType || !msgType.length) msgType = 'info';
                    moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsInfo, { type:msgType, message:messageItem.message }));
                    moqui.webrootVue.addNotify(messageItem.message, msgType);
                } else {
                    moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsInfo, { message:messageItem }));
                    moqui.webrootVue.addNotify(messageItem, 'info');
                }
                notified = true;
            }
        } else {
            moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsInfo, { message:messages }));
            moqui.webrootVue.addNotify(messages, 'info');
            notified = true;
        }
    }
    if (errors) {
        if (moqui.isArray(errors)) {
            for (var ei=0; ei < errors.length; ei++) {
                moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsError, { message:errors[ei] }));
                moqui.webrootVue.addNotify(errors[ei], 'negative');
                notified = true;
            }
        } else {
            moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsError, { message:errors }));
            moqui.webrootVue.addNotify(errors, 'negative');
            notified = true;
        }
    }
    if (validationErrors) {
        if (moqui.isArray(validationErrors)) {
            for (var vei=0; vei < validationErrors.length; vei++) { moqui.notifyValidationError(validationErrors[vei]); notified = true; }
        } else { moqui.notifyValidationError(validationErrors); notified = true; }
    }
    return notified;
};
moqui.notifyValidationError = function(valError) {
    var message = valError;
    if (moqui.isPlainObject(valError)) {
        message = valError.message;
        if (valError.fieldPretty && valError.fieldPretty.length) message = message + " (for field " + valError.fieldPretty + ")";
    }
    moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsError, { message:message }));
    moqui.webrootVue.addNotify(message, 'negative');
};
moqui.handleAjaxError = function(jqXHR, textStatus, errorThrown, responseText) {
    var resp;
    if (responseText) {
        resp = responseText;
    } else if (jqXHR.responseType === 'blob') {
        var reader = new FileReader();
        reader.onload = function(evt) {
            var bodyText = evt.target.result;
            moqui.handleAjaxError(jqXHR, textStatus, errorThrown, bodyText);
        };
        reader.readAsText(jqXHR.response);
        return;
    } else {
        resp = jqXHR.responseText;
    }

    var respObj;
    try { respObj = JSON.parse(resp); } catch (e) { /* ignore error, don't always expect it to be JSON */ }
    console.warn('ajax ' + textStatus + ' (' + jqXHR.status + '), message ' + errorThrown /*+ '; response: ' + resp*/);
    // console.error('resp [' + resp + '] respObj: ' + JSON.stringify(respObj));
    var notified = false;
    if (jqXHR.status === 401) {
        notified = moqui.notifyMessages(null, "No user authenticated");
    } else {
        if (respObj && moqui.isPlainObject(respObj)) {
            notified = moqui.notifyMessages(respObj.messageInfos, respObj.errors, respObj.validationErrors);
            // console.log("got here notified ", notified);
        } else if (resp && moqui.isString(resp) && resp.length) {
            notified = moqui.notifyMessages(resp);
        }
    }

    // reload on 401 (Unauthorized) so server can remember current URL and redirect to login screen
    if (jqXHR.status === 401) {
        if (moqui.webrootVue) { window.location.href = moqui.webrootVue.currentLinkUrl; } else { window.location.reload(true); }
    } else if (jqXHR.status === 0) {
        if (errorThrown.indexOf('abort') < 0) {
            var msg = 'Could not connect to server';
            moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsError, { message:msg }));
            moqui.webrootVue.addNotify(msg, 'negative');
        }
    } else if (!notified) {
        console.log("got here 2 notified ", notified);
        var errMsg = 'Error: ' + errorThrown + ' (' + textStatus + ')';
        moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsError, { message:errMsg }));
        moqui.webrootVue.addNotify(errMsg, 'negative');
    }
};
/* Override moqui.notifyGrowl */
moqui.notifyGrowl = function(jsonObj) {
    if (!jsonObj) return;
    // TODO: jsonObj.link, jsonObj.icon
    moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOptsInfo, { type:jsonObj.type, message:jsonObj.title }));
    moqui.webrootVue.addNotify(jsonObj.title, jsonObj.type);
};

/* ========== component loading methods ========== */
moqui.componentCache = new moqui.LruMap(50);

moqui.handleLoadError = function (jqXHR, textStatus, errorThrown) {
    if (textStatus === 'abort') {
        console.warn('load aborted: ' + textStatus + ' (' + jqXHR.status + '), message ' + errorThrown);
        return;
    }
    moqui.webrootVue.loading = 0;
    moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
};
// NOTE: this may eventually split to change the activeSubscreens only on currentPathList change (for screens that support it)
//     and if ever needed some sort of data refresh if currentParameters changes
moqui.loadComponent = function(urlInfo, callback, divId) {
    var jsExt = moqui.urlExtensions.js, vueExt = moqui.urlExtensions.vue, vuetExt = moqui.urlExtensions.vuet;

    var path, extraPath, search, bodyParameters, renderModes;
    if (typeof urlInfo === 'string') {
        var questIdx = urlInfo.indexOf('?');
        if (questIdx > 0) { path = urlInfo.slice(0, questIdx); search = urlInfo.slice(questIdx+1); }
        else { path = urlInfo; }
    } else {
        path = urlInfo.path; extraPath = urlInfo.extraPath; search = urlInfo.search;
        bodyParameters = urlInfo.bodyParameters; renderModes = urlInfo.renderModes;
    }
    // if Quasar says it's mobile then tell the server via _uiType parameter
    console.log("Load Component " + JSON.stringify(urlInfo) + " Window Width " + window.innerWidth + " Quasar Platform: " + JSON.stringify(Quasar.Platform.is) + " search: " + search);
    if ((window.innerWidth <= 600 || Quasar.Platform.is.mobile) && (!search || search.indexOf("_uiType") === -1)) {
        search = (search || '') + '&_uiType=mobile';
    }

    /* NOTE DEJ 20200718: uncommented componentCache but leaving comment in place in case remains an issue (makes user experience much smoother):
     * CACHE DISABLED: issue with more recent Vue JS where cached components don't re-render when assigned so screens don't load
     * to reproduce: make a screen like a dashboard slow loading with a Thread.sleep(5000), from another screen select it
     * in the menu and before it loads click on a link for another screen, won't load and gets into a bad state where
     * nothing in the same path will load, need to somehow force it to re-render;
     * note that vm.$forceUpdate() in m-subscreens-active component before return false did not work
    // check cache
    // console.info('component lru ' + JSON.stringify(moqui.componentCache.lruList));
    */
    var cachedComp = moqui.componentCache.get(path);
    if (cachedComp) {
        console.info('found cached component for path ' + path + ': ' + JSON.stringify(cachedComp));
        callback(cachedComp);
        return;
    }

    // prep url
    var url = path;

    // does the screen support vue? use http-vue-loader
    if (urlInfo.renderModes && urlInfo.renderModes.indexOf(vueExt) >= 0) url += ('.' + vueExt);
    if (url.slice(-vueExt.length) === vueExt) {
        console.info("loadComponent vue " + url + (divId ? " id " + divId : ''));
        var vueAjaxSettings = { type:"GET", url:url, error:moqui.handleLoadError, success: function(resp, status, jqXHR) {
                if (jqXHR.status === 205) {
                    var redirectTo = jqXHR.getResponseHeader("X-Redirect-To")
                    moqui.webrootVue.setUrl(redirectTo);
                    return;
                }
                // console.info(resp);
                if (!resp) { callback(moqui.NotFound); }
                var isServerStatic = (jqXHR.getResponseHeader("Cache-Control").indexOf("max-age") >= 0);
                if (moqui.isString(resp) && resp.length > 0) {
                    var vueCompObj = httpVueLoader.parse(resp, url.substr(0, url.lastIndexOf('/')+1));
                    if (isServerStatic) { moqui.componentCache.put(path, vueCompObj); }
                    callback(vueCompObj);
                } else { callback(moqui.NotFound); }
            }};
        if (bodyParameters && !$.isEmptyObject(bodyParameters)) { vueAjaxSettings.type = "POST"; vueAjaxSettings.data = bodyParameters; }
        return $.ajax(vueAjaxSettings);
    }

    // look for JavaScript
    var isJsPath = (path.slice(-jsExt.length) === jsExt);
    if (!isJsPath && urlInfo.renderModes && urlInfo.renderModes.indexOf(jsExt) >= 0) {
        // screen supports js explicitly so do that
        url += ('.' + jsExt);
        isJsPath = true;
    }
    if (!isJsPath) url += ('.' + vuetExt);
    if (extraPath && extraPath.length > 0) url += ('/' + extraPath);
    if (search && search.length > 0) url += ('?' + search);

    console.info("loadComponent " + url + (divId ? " id " + divId : ''));
    var ajaxSettings = { type:"GET", url:url, error:moqui.handleLoadError, success: function(resp, status, jqXHR) {
        if (jqXHR.status === 205) {
            var redirectTo = jqXHR.getResponseHeader("X-Redirect-To")
            moqui.webrootVue.setUrl(redirectTo);
            return;
        }
        // console.info(resp);
        if (!resp) { callback(moqui.NotFound); }
        var cacheControl = jqXHR.getResponseHeader("Cache-Control");
        var isServerStatic = (cacheControl && cacheControl.indexOf("max-age") >= 0);
        if (moqui.isString(resp) && resp.length > 0) {
            if (isJsPath || resp.slice(0,7) === 'define(') {
                console.info("loaded JS from " + url + (divId ? " id " + divId : ""));
                var jsCompObj = eval(resp);
                if (jsCompObj.template) {
                    if (isServerStatic) { moqui.componentCache.put(path, jsCompObj); }
                    callback(jsCompObj);
                } else {
                    var htmlUrl = (path.slice(-jsExt.length) === jsExt ? path.slice(0, -jsExt.length) : path) + '.' + vuetExt;
                    $.ajax({ type:"GET", url:htmlUrl, error:moqui.handleLoadError, success: function (htmlText) {
                        jsCompObj.template = htmlText;
                        if (isServerStatic) { moqui.componentCache.put(path, jsCompObj); }
                        callback(jsCompObj);
                    }});
                }
            } else {
                var templateText = resp.replace(/<script/g, '<m-script').replace(/<\/script>/g, '</m-script>').replace(/<link/g, '<m-stylesheet');
                console.info("loaded HTML template from " + url + (divId ? " id " + divId : "") /*+ ": " + templateText*/);
                // using this fixes encoded values in attributes and such that Vue does not decode (but is decoded in plain HTML),
                //     but causes many other problems as all needed encoding is lost too: moqui.decodeHtml(templateText)
                var compObj = { template: '<div' + (divId && divId.length > 0 ? ' id="' + divId + '"' : '') + '>' + templateText + '</div>' };
                if (isServerStatic) { moqui.componentCache.put(path, compObj); }
                callback(compObj);
            }
        } else if (moqui.isPlainObject(resp)) {
            if (resp.screenUrl && resp.screenUrl.length) { moqui.webrootVue.setUrl(resp.screenUrl); }
            else if (resp.redirectUrl && resp.redirectUrl.length) { window.location.replace(resp.redirectUrl); }
        } else { callback(moqui.NotFound); }
    }};
    if (bodyParameters && !$.isEmptyObject(bodyParameters)) { ajaxSettings.type = "POST"; ajaxSettings.data = bodyParameters; }
    return $.ajax(ajaxSettings);
};

/* ========== placeholder components ========== */
moqui.NotFound = Vue.extend({ template: '<div id="current-page-root"><h4>Screen not found at {{this.$root.currentPath}}</h4></div>' });
moqui.EmptyComponent = Vue.extend({ template: '<div id="current-page-root"><div class="spinner"><div>&nbsp;</div></div></div>' });

/* ========== inline components ========== */
Vue.component('m-link', {
    props: { href:{type:String,required:true}, loadId:String, confirmation:String },
    template: '<a :href="linkHref" @click.prevent="go" class="q-link"><slot></slot></a>',
    methods: { go: function(event) {
        if (event.button !== 0) { return; }
        if (this.confirmation && this.confirmation.length) { if (!window.confirm(this.confirmation)) { return; } }
        if (this.loadId && this.loadId.length > 0) {
            this.$root.loadContainer(this.loadId, this.href);
        } else {
            if (event.ctrlKey || event.metaKey) {
                window.open(this.linkHref, "_blank");
            } else {
                this.$root.setUrl(this.linkHref);
            }
        }
    }},
    computed: { linkHref: function () { return this.$root.getLinkPath(this.href); } }
});
// NOTE: router-link simulates the Vue Router RouterLink component (somewhat, at least enough for Quasar to use with its various 'to' attributes on q-btn, etc)
Vue.component('router-link', {
    props: { to:{type:String,required:true} },
    template: '<a :href="linkHref" @click.prevent="go"><slot></slot></a>',
    methods: {
        go: function(event) {
            if (event.button !== 0) { return; }
            if (event.ctrlKey || event.metaKey) {
                window.open(this.linkHref, "_blank");
            } else {
                this.$root.setUrl(this.linkHref);
            }
        }
    },
    computed: {
        linkHref: function () { return this.$root.getLinkPath(this.to); },
        isActive: function () {
            var path = this.to;
            var questIdx = path.indexOf('?');
            if (questIdx > 0) { path = path.slice(0, questIdx); }
            var activePath = this.$root.currentPath;
            console.warn("router-link path [" + path + "] active path [" + activePath + "]");
            return (activePath.startsWith(path));
        },
        // TODO: this should be equals instead of startsWith()
        isExactActive: function () { return this.isActive; }
    }
});

Vue.component('m-script', {
    props: { src:String, type:{type:String,'default':'text/javascript'} },
    template: '<div :type="type" style="display:none;"><slot></slot></div>',
    created: function() { if (this.src && this.src.length > 0) { moqui.loadScript(this.src); } },
    mounted: function() {
        var innerText = this.$el.innerText;
        if (innerText && innerText.trim().length > 0) {
            // console.info('running: ' + innerText);
            moqui.retryInlineScript(innerText, 1);
            /* these don't work on initial load (with script elements that have @src followed by inline script)
            // eval(innerText);
            var parent = this.$el.parentElement; var s = document.createElement('script');
            s.appendChild(document.createTextNode(this.$el.innerText)); parent.appendChild(s);
            */
        }
        // maybe better not to, nice to see in dom: $(this.$el).remove();
    }
});
Vue.component('m-stylesheet', {
    name: "mStylesheet",
    props: { href:{type:String,required:true}, rel:{type:String,'default':'stylesheet'}, type:{type:String,'default':'text/css'} },
    template: '<div :type="type" style="display:none;"></div>',
    created: function() { moqui.loadStylesheet(this.href, this.rel, this.type); }
});
/* ========== layout components ========== */
Vue.component('m-container-box', {
    name: "mContainerBox",
    props: { type:{type:String,'default':'default'}, title:String, initialOpen:{type:Boolean,'default':true} },
    data: function() { return { isBodyOpen:this.initialOpen }},
    // TODO: handle type better, have text color (use text- additional styles instead of Bootstrap to Quasar mapping), can collor the border too?
    template:
    '<q-card flat bordered class="q-ma-sm m-container-box">' +
        '<q-card-actions @click.self="toggleBody">' +
            '<h5 v-if="title && title.length" @click="toggleBody" :class="\'text-\' + type">{{title}}</h5>' +
            '<slot name="header"></slot>' +
            '<q-space></q-space>' +
            '<slot name="toolbar"></slot>' +
        '</q-card-actions>' +
        '<q-card-section :class="{in:isBodyOpen}"><slot></slot></q-card-section>' +
    '</q-card>',
    methods: { toggleBody: function() { this.isBodyOpen = !this.isBodyOpen; } }
});
Vue.component('m-box-body', {
    name: "mBoxBody",
    props: { height:String },
    data: function() { return this.height ? { dialogStyle:{'max-height':this.height+'px', 'overflow-y':'auto'}} : {dialogStyle:{}}},
    template: '<div class="q-pa-xs" :style="dialogStyle"><slot></slot></div>'
});
Vue.component('m-dialog', {
    name: "mDialog",
    props: { draggable:{type:Boolean,'default':true}, value:{type:Boolean,'default':false}, id:String, color:String, width:{type:String}, title:{type:String} },
    data: function() { return { isShown:false }; },
    template:
    '<q-dialog v-bind:value="value" v-on:input="$emit(\'input\', $event)" :id="id" @show="onShow" @hide="onHide" :maximized="$q.platform.is.mobile">' +
        '<q-card ref="dialogCard" flat bordered :style="{width:((width||760)+\'px\'),\'max-width\':($q.platform.is.mobile?\'100vw\':\'90vw\')}">' +
            '<q-card-actions ref="dialogHeader" :style="{cursor:(draggable?\'move\':\'default\')}">' +
                '<h5 class="q-pl-sm non-selectable">{{title}}</h5><q-space></q-space>' +
                '<q-btn icon="close" flat round dense v-close-popup></q-btn>' +
            '</q-card-actions><q-separator></q-separator>' +
            '<q-card-section ref="dialogBody"><slot></slot></q-card-section>' +
        '</q-card>' +
    '</q-dialog>',
    methods: {
        onShow: function() {
            if (this.draggable) { this.$refs.dialogHeader.$el.addEventListener("mousedown", this.onGrab); }
            this.focusFirst();
            this.$emit("onShow");
        },
        onHide: function() {
            if (this.draggable) {
                document.removeEventListener("mousemove", this.onDrag);
                document.removeEventListener("mouseup", this.onLetGo);
                this.$refs.dialogHeader && this.$refs.dialogHeader.$el.removeEventListener("mousedown", this.onGrab);
            }
            this.$emit("onHide");
        },
        onDrag: function(e) {
            var targetEl = this.$refs.dialogCard.$el;
            var originalStyles = window.getComputedStyle(targetEl);
            var newLeft = parseInt(originalStyles.left) + e.movementX;
            var newTop = parseInt(originalStyles.top) + e.movementY;

            var windowWidth = window.innerWidth / 2; var windowHeight = window.innerHeight / 2;
            var elWidth = targetEl.offsetWidth / 2; var elHeight = targetEl.offsetHeight / 2;
            var minLeft = -(windowWidth - elWidth - 10);
            var maxLeft = (windowWidth - elWidth - 10);
            var minTop = -(windowHeight - elHeight - 10);
            var maxTop = (windowHeight - elHeight - 10);
            if (newLeft < minLeft) { newLeft = minLeft; } else if (newLeft > maxLeft) { newLeft = maxLeft; }
            if (newTop < minTop) { newTop = minTop; } else if (newTop > maxTop) { newTop = maxTop; }

            targetEl.style.left = newLeft + "px";
            targetEl.style.top = newTop + "px";
        },
        onLetGo: function() {
            document.removeEventListener("mousemove", this.onDrag);
            document.removeEventListener("mouseup", this.onLetGo);
        },
        onGrab: function() {
            document.addEventListener("mousemove", this.onDrag);
            document.addEventListener("mouseup", this.onLetGo);
        },
        focusFirst: function() {
            var jqEl = $(this.$refs.dialogBody.$el);
            var defFocus = jqEl.find(".default-focus");
            if (defFocus.length) { defFocus.focus(); } else { jqEl.find("form :input:visible:not([type='submit']):first").focus(); }
        }
    }
});
Vue.component('m-container-dialog', {
    name: "mContainerDialog",
    props: { id:String, color:String, buttonText:String, buttonClass:String, title:String, width:{type:String},
        openDialog:{type:Boolean,'default':false}, buttonIcon:{type:String,'default':'open_in_new'} },
    data: function() { return { isShown:false }},
    template:
    '<span>' +
        '<span @click="show()"><slot name="button"><q-btn dense outline no-caps :icon="buttonIcon" :label="buttonText" :color="color" :class="buttonClass"></q-btn></slot></span>' +
        '<m-dialog v-model="isShown" :id="id" :title="title" :color="color" :width="width"><slot></slot></m-dialog>' +
    '</span>',
    methods: { show: function() { this.isShown = true; }, hide: function() { this.isShown = false; } },
    mounted: function() { if (this.openDialog) { this.isShown = true; } }
});
Vue.component('m-dynamic-container', {
    name: "mDynamicContainer",
    props: { id:{type:String,required:true}, url:{type:String} },
    data: function() { return { curComponent:moqui.EmptyComponent, curUrl:"" } },
    template: '<component :is="curComponent"></component>',
    methods: { reload: function() { var saveUrl = this.curUrl; this.curUrl = ""; var vm = this; setTimeout(function() { vm.curUrl = saveUrl; }, 20); },
        load: function(url) { if (this.curUrl === url) { this.reload(); } else { this.curUrl = url; } }},
    watch: { curUrl: function(newUrl) {
        if (!newUrl || newUrl.length === 0) { this.curComponent = moqui.EmptyComponent; return; }
        var vm = this; moqui.loadComponent(newUrl, function(comp) { vm.curComponent = comp; }, this.id);
    }},
    mounted: function() { this.$root.addContainer(this.id, this); this.curUrl = this.url; }
});
Vue.component('m-dynamic-dialog', {
    name: "mDynamicDialog",
    props: { id:{type:String}, url:{type:String,required:true}, color:String, buttonText:String, buttonClass:String, title:String, width:{type:String},
        openDialog:{type:Boolean,'default':false}, dynamicParams:{type:Object,'default':null} },
    data: function() { return { curComponent:moqui.EmptyComponent, curUrl:"", isShown:false} },
    template:
    '<span>' +
        '<q-btn dense outline no-caps icon="open_in_new" :label="buttonText" :color="color" :class="buttonClass" @click="isShown = true"></q-btn>' +
        '<m-dialog ref="dialog" v-model="isShown" :id="id" :title="title" :color="color" :width="width"><component :is="curComponent"></component></m-dialog>' +
    '</span>',
    methods: {
        reload: function() { if (this.isShown) { this.isShown = false; this.isShown = true; }}, // TODO: needs delay? needed at all?
        load: function(url) { this.curUrl = url; },
        hide: function() { this.isShown = false; }
    },
    watch: {
        curUrl: function(newUrl) {
            if (!newUrl || newUrl.length === 0) { this.curComponent = moqui.EmptyComponent; return; }
            var vm = this;
            if (moqui.isPlainObject(this.dynamicParams)) {
                var dpStr = '';
                $.each(this.dynamicParams, function (key, value) {
                    var dynVal = $("#" + value).val();
                    if (dynVal && dynVal.length) dpStr = dpStr + (dpStr.length > 0 ? '&' : '') + key + '=' + dynVal;
                });
                if (dpStr.length) newUrl = newUrl + (newUrl.indexOf("?") > 0 ? '&' : '?') + dpStr;
            }
            moqui.loadComponent(newUrl, function(comp) {
                comp.mounted = function() { this.$nextTick(function () { vm.$refs.dialog.focusFirst(); }); };
                vm.curComponent = comp;
            }, this.id);
        },
        isShown: function(newShown) {
            if (newShown) {
                this.curUrl = this.url;
            } else {
                this.curUrl = "";
            }
        }
    },
    mounted: function() {
        this.$root.addContainer(this.id, this);
        if (this.openDialog) { this.isShown = true; }
    }
});
Vue.component('m-tree-top', {
    name: "mTreeTop",
    template: '<ul :id="id" class="tree-list"><m-tree-item v-for="model in itemList" :key="model.id" :model="model" :top="top"/></ul>',
    props: { id:{type:String,required:true}, items:{type:[String,Array],required:true}, openPath:String, parameters:Object },
    data: function() { return { urlItems:null, currentPath:null, top:this }},
    computed: {
        itemList: function() { if (this.urlItems) { return this.urlItems; } return moqui.isArray(this.items) ? this.items : []; }
    },
    methods: { },
    mounted: function() { if (moqui.isString(this.items)) {
        this.currentPath = this.openPath;
        var allParms = $.extend({ moquiSessionToken:this.$root.moquiSessionToken, treeNodeId:'#', treeOpenPath:this.openPath }, this.parameters);
        var vm = this; $.ajax({ type:'POST', dataType:'json', url:this.items, headers:{Accept:'application/json'}, data:allParms,
            error:moqui.handleAjaxError, success:function(resp) { vm.urlItems = resp; /*console.info('m-tree-top response ' + JSON.stringify(resp));*/ } });
    }}
});
Vue.component('m-tree-item', {
    name: "mTreeItem",
    template:
    '<li :id="model.id">' +
        '<i v-if="isFolder" @click="toggle" class="fa" :class="{\'fa-chevron-right\':!open, \'fa-chevron-down\':open}"></i>' +
        '<i v-else class="fa fa-square-o"></i>' +
        ' <span @click="setSelected">' +
            '<m-link v-if="model.a_attr" :href="model.a_attr.urlText" :load-id="model.a_attr.loadId" :class="{\'text-success\':selected}">{{model.text}}</m-link>' +
            '<span v-if="!model.a_attr" :class="{\'text-success\':selected}">{{model.text}}</span>' +
        '</span>' +
        '<ul v-show="open" v-if="hasChildren"><m-tree-item v-for="model in model.children" :key="model.id" :model="model" :top="top"/></ul></li>',
    props: { model:Object, top:Object },
    data: function() { return { open:false }},
    computed: {
        isFolder: function() { var children = this.model.children; if (!children) { return false; }
            if (moqui.isArray(children)) { return children.length > 0 } return true; },
        hasChildren: function() { var children = this.model.children; return moqui.isArray(children) && children.length > 0; },
        selected: function() { return this.top.currentPath === this.model.id; }
    },
    watch: { open: function(newVal) { if (newVal) {
        var children = this.model.children;
        var url = this.top.items;
        if (this.open && children && moqui.isBoolean(children) && moqui.isString(url)) {
            var li_attr = this.model.li_attr;
            var allParms = $.extend({ moquiSessionToken:this.$root.moquiSessionToken, treeNodeId:this.model.id,
                treeNodeName:(li_attr && li_attr.treeNodeName ? li_attr.treeNodeName : ''), treeOpenPath:this.top.currentPath }, this.top.parameters);
            var vm = this; $.ajax({ type:'POST', dataType:'json', url:url, headers:{Accept:'application/json'}, data:allParms,
                error:moqui.handleAjaxError, success:function(resp) { vm.model.children = resp; } });
        }
    }}},
    methods: {
        toggle: function() { if (this.isFolder) { this.open = !this.open; } },
        setSelected: function() { this.top.currentPath = this.model.id; this.open = true; }
    },
    mounted: function() { if (this.model.state && this.model.state.opened) { this.open = true; } }
});
/* ========== general field components ========== */
Vue.component('m-editable', {
    name: "mEditable",
    props: { id:{type:String,required:true}, labelType:{type:String,'default':'span'}, labelValue:{type:String,required:true},
        url:{type:String,required:true}, urlParameters:{type:Object,'default':{}},
        parameterName:{type:String,'default':'value'}, widgetType:{type:String,'default':'textarea'},
        loadUrl:String, loadParameters:Object, indicator:{type:String,'default':'Saving'}, tooltip:{type:String,'default':'Click to edit'},
        cancel:{type:String,'default':'Cancel'}, submit:{type:String,'default':'Save'} },
    mounted: function() {
        var reqData = $.extend({ moquiSessionToken:this.$root.moquiSessionToken, parameterName:this.parameterName }, this.urlParameters);
        var edConfig = { indicator:this.indicator, tooltip:this.tooltip, cancel:this.cancel, submit:this.submit,
                name:this.parameterName, type:this.widgetType, cssclass:'editable-form', submitdata:reqData };
        if (this.loadUrl && this.loadUrl.length > 0) {
            var vm = this; edConfig.loadurl = this.loadUrl; edConfig.loadtype = "POST";
            edConfig.loaddata = function(value) { return $.extend({ currentValue:value, moquiSessionToken:vm.$root.moquiSessionToken }, vm.loadParameters); };
        }
        // TODO, replace with something in quasar: $(this.$el).editable(this.url, edConfig);
    },
    render: function(createEl) { return createEl(this.labelType, { attrs:{ id:this.id, 'class':'editable-label' }, domProps: { innerHTML:this.labelValue } }); }
});

/* ========== form components ========== */

moqui.checkboxSetMixin = {
    // NOTE: checkboxCount is used to init the checkbox state array, defaults to 100 and must be greater than or equal to the actual number of checkboxes (not including the All checkbox)
    props: { checkboxCount:{type:Number,'default':100}, checkboxParameter:String, checkboxListMode:Boolean, checkboxValues:Array },
    data: function() {
        var checkboxStates = [];
        for (var i = 0; i < this.checkboxCount; i++) checkboxStates[i] = false;
        return { checkboxAllState:false, checkboxStates:checkboxStates }
    },
    methods: {
        setCheckboxAllState: function(newState) {
            this.checkboxAllState = newState;
            var csSize = this.checkboxStates.length;
            for (var i = 0; i < csSize; i++) this.checkboxStates[i] = newState;
        },
        getCheckboxValueArray: function() {
            if (!this.checkboxValues) return [];
            var valueArray = [];
            var csSize = this.checkboxStates.length;
            for (var i = 0; i < csSize; i++) if (this.checkboxStates[i] && this.checkboxValues[i]) valueArray.push(this.checkboxValues[i]);
            return valueArray;
        },
        addCheckboxParameters: function(formData, parameter, listMode) {
            var parmName = parameter || this.checkboxParameter;
            var useList = (listMode !== null && listMode !== undefined && listMode) ? listMode : this.checkboxListMode;
            // NOTE: formData must be a FormData object, or at least have a set(name, value) method
            var valueArray = this.getCheckboxValueArray();
            if (!valueArray.length) return false;
            if (useList) {
                formData.set(parmName, valueArray.join(','));
            } else {
                for (var i = 0; i < valueArray.length; i++)
                    formData.set(parmName + '_' + i, valueArray[i]);
                formData.set('_isMulti', 'true');
            }
            return true;
        }
    },
    watch: {
        checkboxStates: { deep:true, handler:function(newArray) {
            var allTrue = true;
            for (var i = 0; i < newArray.length; i++) {
                var curState = newArray[i];
                if (!curState) allTrue = false;
                if (!allTrue) break;
            }
            this.checkboxAllState = allTrue;
        } }
    }
}
Vue.component('m-checkbox-set', {
    name: "mCheckboxSet",
    mixins:[moqui.checkboxSetMixin],
    template: '<span class="checkbox-set"><slot :checkboxAllState="checkboxAllState" :setCheckboxAllState="setCheckboxAllState"' +
        ' :checkboxStates="checkboxStates" :addCheckboxParameters="addCheckboxParameters"></slot></span>'
});

Vue.component('m-form', {
    name: "mForm",
    mixins:[moqui.checkboxSetMixin],
    props: { fieldsInitial:Object, action:{type:String,required:true}, method:{type:String,'default':'POST'},
        submitMessage:String, submitReloadId:String, submitHideId:String, focusField:String, noValidate:Boolean,
        excludeEmptyFields:Boolean, parentCheckboxSet:Object },
    data: function() { return { fields:Object.assign({}, this.fieldsInitial), fieldsChanged:{}, buttonClicked:null }},
    // NOTE: <slot v-bind:fields="fields"> also requires prefix from caller, using <m-form v-slot:default="formProps"> in qvt.ftl macro
    // see https://vuejs.org/v2/guide/components-slots.html
    template:
        '<q-form ref="qForm" @submit.prevent="submitForm" @reset.prevent="resetForm" autocapitalize="off" autocomplete="off">' +
            '<slot :fields="fields" :checkboxAllState="checkboxAllState" :setCheckboxAllState="setCheckboxAllState"' +
                ' :checkboxStates="checkboxStates" :addCheckboxParameters="addCheckboxParameters"></slot>' +
        '</q-form>',
    methods: {
        submitForm: function() {
            if (this.noValidate) {
                this.submitGo();
            } else {
                var jqEl = $(this.$el);
                var vm = this;
                this.$refs.qForm.validate().then(function(success) {
                    if (success) {
                        vm.submitGo();
                    } else {
                        /*
                        // For convenience, attempt to focus the first invalid element.
                        // Begin by finding the first invalid input
                        var invEle = jqEl.find('div.has-error input, div.has-error select, div.has-error textarea').first();
                        if (invEle.length) {
                            // TODO remove this or change to handle Quasar flavor of accordian/panel
                            // If the element is inside a collapsed panel, attempt to open it.
                            // Find parent (if it exists) with class .panel-collapse.collapse (works for accordion and regular panels)
                            var nearestPanel = invEle.parents('div.panel-collapse.collapse').last();
                            if (nearestPanel.length) {
                                // Only bother if the panel is not currently open
                                if (!nearestPanel.hasClass('in')) {
                                    // From there find sibling with class panel-heading
                                    var panelHeader = nearestPanel.prevAll('div.panel-heading').last();
                                    if (panelHeader.length) {
                                        // Here is where accordion and regular panels diverge.
                                        var panelLink = panelHeader.find('a[data-toggle="collapse"]').first();
                                        if (panelLink.length) panelLink.click();
                                        else panelHeader.click();
                                        setTimeout(function() { invEle.focus(); }, 250);
                                    } else invEle.focus();
                                } else invEle.focus();
                            } else invEle.focus();
                        }
                        */
                    }
                })
            }
        },
        resetForm: function() {
            this.fields = Object.assign({}, this.fieldsInitial);
            this.fieldsChanged = {};
        },
        submitGo: function() {
            var vm = this;
            var jqEl = $(this.$el);
            // get button pressed value and disable ASAP to avoid double submit
            var btnName = null, btnValue = null;
            var $btn = $(this.buttonClicked || document.activeElement);
            if ($btn.length && jqEl.has($btn) && $btn.is('button[type="submit"], input[type="submit"], input[type="image"]')) {
                if ($btn.is('[name]')) { btnName = $btn.attr('name'); btnValue = $btn.val(); }
                $btn.prop('disabled', true);
                setTimeout(function() { $btn.prop('disabled', false); }, 3000);
            }
            var formData = Object.keys(this.fields).length ? new FormData() : new FormData(this.$refs.qForm.$el);
            $.each(this.fields, function(key, value) { formData.set(key, value || ""); });

            var fieldsToRemove = [];
            // NOTE: using iterator directly to avoid using 'for of' which requires more recent ES version (for minify, browser compatibility)
            var formDataIterator = formData.entries()[Symbol.iterator]();
            while (true) {
                var iterEntry = formDataIterator.next();
                if (iterEntry.done) break;
                var pair = iterEntry.value;
                var fieldName = pair[0];
                var fieldValue = pair[1];
                // NOTE: this shouldn't happen as when not getting from FormData q-input with mask should have null value when empty, but just in case skip String values that are unfilled masks
                // NOTE: with q-input mask place holder is underscore, look for 2; this will cause issues if a valid user input starts with 2 underscores, may need better approach here and in m-form-link
                if (moqui.isString(fieldValue) && fieldValue.startsWith("__")) {
                    // instead of delete set to empty string, otherwise can't clear masked fields: formData["delete"](fieldName);
                    formData.set(fieldName, "");
                }
                if (this.excludeEmptyFields && (!fieldValue || !fieldValue.length)) fieldsToRemove.push(fieldName);
            }
            for (var ftrIdx = 0; ftrIdx < fieldsToRemove.length; ftrIdx++) formData['delete'](fieldsToRemove[ftrIdx]);

            formData.set('moquiSessionToken', this.$root.moquiSessionToken);
            if (btnName) { formData.set(btnName, btnValue); }

            // add ID parameters for selected rows, add _isMulti=true
            if (this.parentCheckboxSet && this.parentCheckboxSet.addCheckboxParameters) {
                var addedParms = this.parentCheckboxSet.addCheckboxParameters(formData);
                // TODO: if no addedParms should this blow up or just wait for the server for a missing parameter?
                // maybe best to leave it to the server, some forms might make sense without any rows selected...
            }

            // console.info('m-form parameters ' + JSON.stringify(formData));
            // for (var key of formData.keys()) { console.log('m-form key ' + key + ' val ' + JSON.stringify(formData.get(key))); }
            this.$root.loading++;

            /* this didn't work, JS console error: Failed to execute 'createObjectURL' on 'URL': Overload resolution failed
            $.ajax({ type:this.method, url:(this.$root.appRootPath + this.action), data:formData, contentType:false, processData:false, dataType:'text',
                xhrFields:{responseType:'blob'}, headers:{Accept:'application/json'}, error:moqui.handleLoadError, success:this.handleResponse });
             */

            var xhr = new XMLHttpRequest();
            xhr.open(this.method, (this.$root.appRootPath + this.action), true);
            xhr.responseType = 'blob';
            xhr.withCredentials = true;
            xhr.onload = function () {
                if (this.status === 200) {
                    // decrement loading counter
                    vm.$root.loading--;

                    var disposition = xhr.getResponseHeader('Content-Disposition');
                    if (disposition && (disposition.indexOf('attachment') !== -1 || disposition.indexOf('inline') !== -1)) {
                        // download code here thanks to Jonathan Amend, see: https://stackoverflow.com/questions/16086162/handle-file-download-from-ajax-post/23797348#23797348
                        var blob = this.response;
                        var filename = "";
                        if (disposition && disposition.indexOf('attachment') !== -1) {
                            var filenameRegex = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/;
                            var matches = filenameRegex.exec(disposition);
                            if (matches != null && matches[1]) filename = matches[1].replace(/['"]/g, '');
                        }

                        if (typeof window.navigator.msSaveBlob !== 'undefined') {
                            window.navigator.msSaveBlob(blob, filename);
                        } else {
                            var URL = window.URL || window.webkitURL;
                            var downloadUrl = URL.createObjectURL(blob);

                            if (filename) {
                                var a = document.createElement("a");
                                if (typeof a.download === 'undefined') {
                                    window.location.href = downloadUrl;
                                } else {
                                    a.href = downloadUrl;
                                    a.download = filename;
                                    document.body.appendChild(a);
                                    a.click();
                                }
                            } else {
                                window.location.href = downloadUrl;
                            }

                            setTimeout(function () { URL.revokeObjectURL(downloadUrl); }, 100); // cleanup
                        }
                    } else {
                        var reader = new FileReader();
                        reader.onload = function(evt) {
                            var bodyText = evt.target.result;
                            try {
                                vm.handleResponse(JSON.parse(bodyText));
                            } catch(e) {
                                vm.handleResponse(bodyText);
                            }

                        };
                        reader.readAsText(this.response);
                    }
                } else {
                    moqui.handleLoadError(this, this.statusText, "");
                }
            };
            xhr.setRequestHeader('Accept', 'application/json');
            xhr.send(formData);
        },
        handleResponse: function(resp) {
            var notified = false;
            // console.info('m-form response ' + JSON.stringify(resp));
            if (resp && moqui.isPlainObject(resp)) {
                notified = moqui.notifyMessages(resp.messageInfos, resp.errors);
                if (resp.screenUrl && resp.screenUrl.length > 0) { this.$root.setUrl(resp.screenUrl); }
                else if (resp.redirectUrl && resp.redirectUrl.length > 0) { window.location.href = resp.redirectUrl; }
            } else { console.warn('m-form no response or non-JSON response: ' + JSON.stringify(resp)) }
            var hideId = this.submitHideId; if (hideId && hideId.length > 0) { $('#' + hideId).modal('hide'); }
            var reloadId = this.submitReloadId; if (reloadId && reloadId.length > 0) { this.$root.reloadContainer(reloadId); }
            var subMsg = this.submitMessage;
            if (subMsg && subMsg.length) {
                var responseText = resp; // this is set for backward compatibility in case message relies on responseText as in old JS
                var message = eval('"' + subMsg + '"');
                moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOpts, { message:message }));
                moqui.webrootVue.addNotify(message, 'success');
            } else if (!notified) {
                moqui.webrootVue.$q.notify($.extend({}, moqui.notifyOpts, { message:"Submit successful" }));
            }
        }
        /* TODO
        fieldChange: function (evt) {
            var targetDom = evt.delegateTarget; var targetEl = $(targetDom);
            if (targetEl.hasClass("input-group") && targetEl.children("input").length) {
                // special case for date-time using bootstrap-datetimepicker
                targetEl = targetEl.children("input").first();
                targetDom = targetEl.get(0);
            }
            var changed = false;
            if (targetDom.nodeName === "INPUT" || targetDom.nodeName === "TEXTAREA") {
                if (targetEl.attr("type") === "radio" || targetEl.attr("type") === "checkbox") {
                    changed = targetDom.checked !== targetDom.defaultChecked; }
                else { changed = targetDom.value !== targetDom.defaultValue; }
            } else if (targetDom.nodeName === "SELECT") {
                if (targetDom.multiple) {
                    var optLen = targetDom.options.length;
                    for (var i = 0; i < optLen; i++) {
                        var opt = targetDom.options[i];
                        if (opt.selected !== opt.defaultSelected) { changed = true; break; }
                    }
                } else {
                    changed = !targetDom.options[targetDom.selectedIndex].defaultSelected;
                }
            }
            // console.log("changed? " + changed + " node " + targetDom.nodeName + " type " + targetEl.attr("type") + " " + targetEl.attr("name") + " to " + targetDom.value + " default " + targetDom.defaultValue);
            // console.log(targetDom.defaultValue);
            if (changed) {
                this.fieldsChanged[targetEl.attr("name")] = true;
                targetEl.parents(".form-group").children("label").addClass("is-changed");
                targetEl.parents(".form-group").find(".select2-selection").addClass("is-changed");
                targetEl.addClass("is-changed");
            } else {
                this.fieldsChanged[targetEl.attr("name")] = false;
                targetEl.parents(".form-group").children("label").removeClass("is-changed");
                targetEl.parents(".form-group").find(".select2-selection").removeClass("is-changed");
                targetEl.removeClass("is-changed");
            }
        }
         */
    },
    mounted: function() {
        var vm = this;
        var jqEl = $(this.$el);
        if (this.focusField && this.focusField.length) jqEl.find('[name^="' + this.focusField + '"]').addClass('default-focus').focus();

        /* TODO: should not need to watch input fields any more
        // watch changed fields
        jqEl.find(':input').on('change', this.fieldChange);
        // special case for date-time using bootstrap-datetimepicker
        jqEl.find('div.input-group.date').on('change', this.fieldChange);
        */
        // TODO: find other way to get button clicked (Vue event?)
        // watch button clicked
        jqEl.find('button[type="submit"], input[type="submit"], input[type="image"]').on('click', function() { vm.buttonClicked = this; });
    }
});
Vue.component('m-form-link', {
    name: "mFormLink",
    props: { fieldsInitial:Object, action:{type:String,required:true}, focusField:String, noValidate:Boolean, bodyParameterNames:Array },
    data: function() { return { fields:Object.assign({}, this.fieldsInitial) }},
    template:
        '<q-form ref="qForm" @submit.prevent="submitForm" @reset.prevent="resetForm" autocapitalize="off" autocomplete="off">' +
            '<slot :clearForm="clearForm" :fields="fields"></slot></q-form>',
    methods: {
        submitForm: function() {
            if (this.noValidate) {
                this.submitGo();
            } else {
                var vm = this;
                this.$refs.qForm.validate().then(function(success) {
                    if (success) {
                        vm.submitGo();
                    } else {
                        // oh no, user has filled in at least one invalid value
                    }
                })
            }
        },
        submitGo: function() {
            // get button pressed value and disable ASAP to avoid double submit
            var btnName = null, btnValue = null;
            var $btn = $(document.activeElement);
            if ($btn.length && $btn.is('button[type="submit"], input[type="submit"], input[type="image"]')) {
                if ($btn.is('[name]')) { btnName = $btn.attr('name'); btnValue = $btn.val(); }
                $btn.prop('disabled', true);
                setTimeout(function() { $btn.prop('disabled', false); }, 3000);
            }

            var formData = Object.keys(this.fields).length ? new FormData() : new FormData(this.$refs.qForm.$el);
            /*
            formData.forEach(function(value, key, parent) {
                console.warn("m-form-link submit FormData key " + key + " value " + value + " is mask placeholder " + (moqui.isString(value) && value.startsWith("__")));
            });
             */
            $.each(this.fields, function (key, value) { if (value) {
                // NOTE: this shouldn't happen as when not getting from FormData q-input with mask should have null value when empty, but just in case skip String values that are unfilled masks
                // NOTE: with q-input mask place holder is underscore, look for 2; this will cause issues if a valid user input starts with 2 underscores, may need better approach here and in m-form
                // console.warn("m-form-link submit fields key " + key + " value " + value + " is mask placeholder " + (moqui.isString(value) && value.startsWith("__")));
                if (moqui.isString(value) && value.startsWith("__")) return;
                formData.set(key, value);
            } });

            var extraList = [];
            var plainKeyList = [];
            var parmStr = "";
            var bodyParameters = null;
            // NOTE: using iterator directly to avoid using 'for of' which requires more recent ES version (for minify, browser compatibility)
            var formDataIterator = formData.entries()[Symbol.iterator]();
            while (true) {
                var iterEntry = formDataIterator.next();
                if (iterEntry.done) break;
                var pair = iterEntry.value;
                var key = pair[0];
                var value = pair[1];

                if (value.trim().length === 0 || key === "moquiSessionToken" || key === "moquiFormName" || key.indexOf('[]') > 0) continue;
                if (key.indexOf("_op") > 0 || key.indexOf("_not") > 0 || key.indexOf("_ic") > 0) {
                    extraList.push({name:key, value:value});
                } else {
                    plainKeyList.push(key);
                    if (this.bodyParameterNames && this.bodyParameterNames.indexOf(key) >= 0) {
                        if (!bodyParameters) bodyParameters = {};
                        bodyParameters[key] = value;
                    } else {
                        if (parmStr.length > 0) { parmStr += '&'; }
                        parmStr += (encodeURIComponent(key) + '=' + encodeURIComponent(value));
                    }
                }
            }
            for (var ei=0; ei<extraList.length; ei++) {
                var eparm = extraList[ei];
                var keyName = eparm.name.substring(0, eparm.name.indexOf('_'));
                if (plainKeyList.indexOf(keyName) >= 0) {
                    if (parmStr.length > 0) { parmStr += '&'; }
                    parmStr += (encodeURIComponent(eparm.name) + '=' + encodeURIComponent(eparm.value));
                }
            }
            if (btnName && btnValue && btnValue.trim().length) {
                if (parmStr.length > 0) { parmStr += '&'; }
                parmStr += (encodeURIComponent(btnName) + '=' + encodeURIComponent(btnValue));
            }
            var url = this.action;
            if (url.indexOf('?') > 0) { url = url + '&' + parmStr; } else { url = url + '?' + parmStr; }
            // console.log("form-link url " + url + " bodyParameters " + JSON.stringify(bodyParameters));
            this.$root.setUrl(url, bodyParameters);

        },
        resetForm: function() {
            this.fields = Object.assign({}, this.fieldsInitial);
        },
        clearForm: function() {
            // TODO: probably need to iterate over object and clear each value
            this.fields = {};
        }
    },
    mounted: function() {
        var jqEl = $(this.$el);
        /* TODO if (!this.noValidate) jqEl.validate({ errorClass: 'help-block', errorElement: 'span',
            highlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-success').addClass('has-error'); },
            unhighlight: function(element, errorClass, validClass) { $(element).parents('.form-group').removeClass('has-error').addClass('has-success'); }
        });*/
        // TODO jqEl.find('[data-toggle="tooltip"]').tooltip({placement:'auto top'});
        if (this.focusField && this.focusField.length > 0) jqEl.find('[name=' + this.focusField + ']').addClass('default-focus').focus();
    }
});

Vue.component('m-form-paginate', {
    name: "mFormPaginate",
    props: { paginate:Object, formList:Object },
    template:
    '<div v-if="paginate" class="q-pagination row no-wrap items-center">' +
        '<template v-if="paginate.pageIndex > 0">' +
            '<q-btn dense flat no-caps @click.prevent="setIndex(0)" icon="skip_previous"></q-btn>' +
            '<q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageIndex-1)" icon="fast_rewind"></q-btn></template>' +
        '<template v-else><q-btn dense flat no-caps disabled icon="skip_previous"></q-btn><q-btn dense flat no-caps disabled icon="fast_rewind"></q-btn></template>' +
        '<q-btn v-for="prevIndex in prevArray" :key="prevIndex" dense flat no-caps @click.prevent="setIndex(prevIndex)" :label="prevIndex+1" color="primary"></q-btn>' +
        '<q-btn dense flat no-caps disabled>{{paginate.pageIndex+1}} / {{paginate.pageMaxIndex+1}} ({{paginate.pageRangeLow}}-{{paginate.pageRangeHigh}} / {{paginate.count}})</q-btn>' +
        '<q-btn v-for="nextIndex in nextArray" :key="nextIndex" dense flat no-caps @click.prevent="setIndex(nextIndex)" :label="nextIndex+1" color="primary"></q-btn>' +
        '<template v-if="paginate.pageIndex < paginate.pageMaxIndex">' +
            '<q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageIndex+1)" icon="fast_forward"></q-btn>' +
            '<q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageMaxIndex)" icon="skip_next"></q-btn></template>' +
        '<template v-else><q-btn dense flat no-caps disabled icon="fast_forward"></q-btn><q-btn dense flat no-caps disabled icon="skip_next"></q-btn></template>' +
    '</div>',
    computed: {
        prevArray: function() {
            var pag = this.paginate; var arr = []; if (!pag || pag.pageIndex < 1) return arr;
            var pageIndex = pag.pageIndex; var indexMin = pageIndex - 3; if (indexMin < 0) { indexMin = 0; } var indexMax = pageIndex - 1;
            while (indexMin <= indexMax) { arr.push(indexMin++); } return arr;
        },
        nextArray: function() {
            var pag = this.paginate; var arr = []; if (!pag || pag.pageIndex >= pag.pageMaxIndex) return arr;
            var pageIndex = pag.pageIndex; var pageMaxIndex = pag.pageMaxIndex;
            var indexMin = pageIndex + 1; var indexMax = pageIndex + 3; if (indexMax > pageMaxIndex) { indexMax = pageMaxIndex; }
            while (indexMin <= indexMax) { arr.push(indexMin++); } return arr;
        }
    },
    methods: { setIndex: function(newIndex) {
        if (this.formList) { this.formList.setPageIndex(newIndex); } else { this.$root.setParameters({pageIndex:newIndex}); }
    }}
});
Vue.component('m-form-go-page', {
    name: "mFormGoPage",
    props: { idVal:{type:String,required:true}, maxIndex:Number, formList:Object },
    data: function() { return { pageIndex:"" } },
    template:
    '<q-form v-if="!formList || (formList.paginate && formList.paginate.pageMaxIndex > 4)" @submit.prevent="goPage">' +
        '<q-input dense v-model="pageIndex" type="text" size="4" name="pageIndex" placeholder="Page #"' +
            '   :rules="[val => /^\\d*$/.test(val) || \'digits only\', val => ((formList && +val <= formList.paginate.pageMaxIndex) || (maxIndex && +val < maxIndex)) || \'higher than max\']">' +
            '<template v-slot:append><q-btn dense flat no-caps type="submit" icon="redo" @click="goPage"></q-btn></template>' +
        '</q-input>' +
    '</q-form>',
    methods: { goPage: function() {
        var formList = this.formList;
        var newIndex = +this.pageIndex - 1;
        if (formList) { formList.setPageIndex(newIndex); } else { this.$root.setParameters({pageIndex:newIndex}); }
        var vm = this;
        this.$nextTick(function() { vm.pageIndex = ""; });
    }}
});
Vue.component('m-form-column-config', {
    name: "mFormColumnConfig",
    // column entry Object fields: id, label, children[]
    props: { id:String, action:String, columnsInitial:{type:Array,required:true}, formLocation:{type:String,required:true}, findParameters:Object },
    data: function() { return { columns:moqui.deepCopy(this.columnsInitial) } },
    template:
        '<m-form ref="mForm" :id="id" :action="action">' +
            '<q-list v-for="(column, columnIdx) in columns" :key="column.id" bordered dense>' +
                '<q-item-label header>{{column.label}}</q-item-label>' +
                '<q-item v-for="(field, fieldIdx) in column.children" :key="field.id">' +
                    '<q-item-section side v-if="columnIdx !== 0">' +
                        '<q-btn dense flat icon="cancel" @click="hideField(columnIdx, fieldIdx)"><q-tooltip>Hide</q-tooltip></q-btn>' +
                    '</q-item-section>' +
                    '<q-item-section><q-item-label>{{field.label}}</q-item-label></q-item-section>' +
                    '<q-item-section v-if="columnIdx === 0" side>' +
                        '<q-btn-dropdown dense outline no-caps label="Display"><q-list dense>' +
                            '<q-item v-for="(toColumn, toColumnIdx) in columns.slice(1)" :key="toColumn.id" clickable>' +
                                '<q-item-section @click="moveToCol(columnIdx, fieldIdx, toColumnIdx+1)">{{toColumn.label}}</q-item-section></q-item>' +
                            '<q-item clickable>' +
                                '<q-item-section @click="moveToCol(columnIdx, fieldIdx, columns.length+1)">New Column</q-item-section></q-item>' +
                        '</q-list></q-btn-dropdown>' +
                    '</q-item-section>' +
                    '<q-item-section v-else side><q-btn-group flat>' +
                        '<q-btn :disabled="columnIdx <= 1" dense flat icon="north" @click="moveToCol(columnIdx, fieldIdx, columnIdx-1)"></q-btn>' +
                        '<q-btn :disabled="fieldIdx === 0" dense flat icon="expand_less" @click="moveInCol(columnIdx, fieldIdx, fieldIdx-1)"></q-btn>' +
                        '<q-btn :disabled="(fieldIdx + 1) === column.children.length" dense flat icon="expand_more" @click="moveInCol(columnIdx, fieldIdx, fieldIdx+1)"></q-btn>' +
                        '<q-btn dense flat icon="south" @click="moveToCol(columnIdx, fieldIdx, columnIdx+1)"></q-btn>' +
                    '</q-btn-group></q-item-section>' +
                '</q-item>' +
            '</q-list>' +
            '<div class="q-my-md">' +
                '<q-btn dense outline no-caps @click.prevent="saveColumns()" label="Save Changes"></q-btn>' +
                '<q-btn dense outline no-caps @click.prevent="resetColumns()" label="Undo Changes"></q-btn>' +
                '<q-btn dense outline no-caps @click.prevent="resetToDefault()" label="Reset to Default"></q-btn>' +
            '</div>' +
        '</m-form>',
    methods: {
        moveInCol: function(columnIdx, fieldIdx, newFieldIdx) {
            var children = this.columns[columnIdx].children;
            var fieldObj = children.splice(fieldIdx, 1)[0];
            children.splice(newFieldIdx, 0, fieldObj);
        },
        moveToCol: function(columnIdx, fieldIdx, newColumnIdx) {
            var columnObj = this.columns[columnIdx];
            var newColumnObj = newColumnIdx >= this.columns.length ? this.addColumn() : this.columns[newColumnIdx];
            var fieldObj = columnObj.children.splice(fieldIdx, 1)[0];
            newColumnObj.children.push(fieldObj);
        },
        addColumn: function() {
            var oldLength = this.columns.length;
            var lastCol = this.columns[oldLength-1];
            var newId = lastCol.id.split("_")[0] + "_" + oldLength;
            var newLabel = lastCol.label.split(" ")[0] + " " + oldLength;
            // NOTE: push and get so reactive
            this.columns.push({id:newId,label:newLabel,children:[]});
            return this.columns[oldLength];
        },
        hideField: function(columnIdx, fieldIdx) {
            if (columnIdx === 0) return;
            var hiddenObj = this.columns[0];
            var columnObj = this.columns[columnIdx];
            var fieldObj = columnObj.children.splice(fieldIdx, 1)[0];
            hiddenObj.children.push(fieldObj);
        },
        resetColumns: function() { this.columns = moqui.deepCopy(this.columnsInitial); },
        saveColumns: function() {
            this.generalFormFields();
            var fields = this.$refs.mForm.fields;
            fields.SaveColumns = "SaveColumns";
            fields.columnsTree = JSON.stringify(this.columns);
            this.$refs.mForm.submitGo();
        },
        resetToDefault: function() {
            this.generalFormFields();
            this.$refs.mForm.fields.ResetColumns = "ResetColumns";
            this.$refs.mForm.submitGo();
        },
        generalFormFields: function() {
            var fields = this.$refs.mForm.fields;
            fields.formLocation = this.formLocation;
            if (this.findParameters) for (var curKey in Object.keys(this.findParameters))
                fields[curKey] = this.findParameters[curKey];
            console.log("Save column config " + this.formLocation + " Window Width " + window.innerWidth + " Quasar Platform: " + JSON.stringify(Quasar.Platform.is));
            if (window.innerWidth <= 600 || Quasar.Platform.is.mobile) fields._uiType = 'mobile';
        }
    }
});

// TODO: m-form-list still needs a LOT of work, full re-implementation of form-list FTL macros for full client rendering so that component is fully static and data driven
Vue.component('m-form-list', {
    name: "mFormList",
    // rows can be a full path to a REST service or transition, a plain form name on the current screen, or a JS Array with the actual rows
    props: { name:{type:String,required:true}, id:String, rows:{type:[String,Array],required:true}, search:{type:Object},
        action:String, multi:Boolean, skipForm:Boolean, skipHeader:Boolean, headerForm:Boolean, headerDialog:Boolean,
        savedFinds:Boolean, selectColumns:Boolean, allButton:Boolean, csvButton:Boolean, textButton:Boolean, pdfButton:Boolean,
        columns:[String,Number] },
    data: function() { return { rowList:[], paginate:null, searchObj:null, moqui:moqui } },
    // slots (props): headerForm (search), header (search), nav (), rowForm (fields), row (fields)
    // TODO: QuickSavedFind drop-down
    // TODO: change find options form to update searchObj and run fetchRows instead of changing main page and reloading
    // TODO: update window url on paginate and other searchObj update?
    // TODO: review for actual static (no server side rendering, cachable)
    template:
    '<div>' +
        '<template v-if="!multi && !skipForm">' +
            '<m-form v-for="(fields, rowIndex) in rowList" :name="idVal+\'_\'+rowIndex" :id="idVal+\'_\'+rowIndex" :action="action">' +
                '<slot name="rowForm" :fields="fields"></slot></m-form></template>' +
        '<m-form v-if="multi && !skipForm" :name="idVal" :id="idVal" :action="action">' +
            '<input type="hidden" name="moquiFormName" :value="name"><input type="hidden" name="_isMulti" value="true">' +
            '<template v-for="(fields, rowIndex) in rowList"><slot name="rowForm" :fields="fields"></slot></template></m-form>' +
        '<m-form-link v-if="!skipHeader && headerForm && !headerDialog" :name="idVal+\'_header\'" :id="idVal+\'_header\'" :action="$root.currentLinkPath">' +
            '<input v-if="searchObj && searchObj.orderByField" type="hidden" name="orderByField" :value="searchObj.orderByField">' +
            '<slot name="headerForm" :search="searchObj"></slot></m-form-link>' +
        '<div class="q-table__container q-table__card q-table--horizontal-separator q-table--dense q-table--flat"><table class="q-table" :id="idVal+\'_table\'"><thead>' +
            '<tr class="form-list-nav-row"><th :colspan="columns?columns:\'100\'"><q-bar>' +
                '<button v-if="savedFinds || headerDialog" :id="idVal+\'_hdialog_button\'" type="button" data-toggle="modal" :data-target="\'#\'+idVal+\'_hdialog\'" data-original-title="Find Options" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> Find Options</button>' +
                '<button v-if="selectColumns" :id="idVal+\'_SelColsDialog_button\'" type="button" data-toggle="modal" :data-target="\'#\'+idVal+\'_SelColsDialog\'" data-original-title="Columns" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> Columns</button>' +
                '<m-form-paginate :paginate="paginate" :form-list="this"></m-form-paginate>' +
                '<m-form-go-page :id-val="idVal" :form-list="this"></m-form-go-page>' +
                '<a v-if="csvButton" :href="csvUrl" class="btn btn-default">CSV</a>' +
                '<button v-if="textButton" :id="idVal+\'_TextDialog_button\'" type="button" data-toggle="modal" :data-target="\'#\'+idVal+\'_TextDialog\'" data-original-title="Text" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> Text</button>' +
                '<button v-if="pdfButton" :id="idVal+\'_PdfDialog_button\'" type="button" data-toggle="modal" :data-target="\'#\'+idVal+\'_PdfDialog\'" data-original-title="PDF" data-placement="bottom" class="btn btn-default"><i class="fa fa-share"></i> PDF</button>' +
                '<slot name="nav"></slot>' +
            '</q-bar></th></tr>' +
            '<slot name="header" :search="searchObj"></slot>' +
        '</thead><tbody><tr v-for="(fields, rowIndex) in rowList"><slot name="row" :fields="fields" :row-index="rowIndex" :moqui="moqui"></slot></tr>' +
        '</tbody></table></div>' +
    '</div>',
    computed: {
        idVal: function() { if (this.id && this.id.length > 0) { return this.id; } else { return this.name; } },
        csvUrl: function() { return this.$root.currentPath + '?' + moqui.objToSearch($.extend({}, this.searchObj,
            { renderMode:'csv', pageNoLimit:'true', lastStandalone:'true', saveFilename:(this.name + '.csv') })); }
    },
    methods: {
        fetchRows: function() {
            if (moqui.isArray(this.rows)) { console.warn('Tried to fetch form-list-body rows but rows prop is an array'); return; }
            var vm = this;
            var searchObj = this.search; if (!searchObj) { searchObj = this.$root.currentParameters; }
            var url = this.rows; if (url.indexOf('/') === -1) { url = this.$root.currentPath + '/actions/' + url; }
            console.info("Fetching rows with url " + url + " searchObj " + JSON.stringify(searchObj));
            $.ajax({ type:"GET", url:url, data:searchObj, dataType:"json", headers:{Accept:'application/json'},
                error:moqui.handleAjaxError, success: function(list, status, jqXHR) {
                    if (list && moqui.isArray(list)) {
                        var getHeader = jqXHR.getResponseHeader;
                        var count = Number(getHeader("X-Total-Count"));
                        if (count && !isNaN(count)) {
                            vm.paginate = { count:Number(count), pageIndex:Number(getHeader("X-Page-Index")),
                                pageSize:Number(getHeader("X-Page-Size")), pageMaxIndex:Number(getHeader("X-Page-Max-Index")),
                                pageRangeLow:Number(getHeader("X-Page-Range-Low")), pageRangeHigh:Number(getHeader("X-Page-Range-High")) };
                        }
                        vm.rowList = list;
                        console.info("Fetched " + list.length + " rows, paginate: " + JSON.stringify(vm.paginate));
                    }
                }});
        },
        setPageIndex: function(newIndex) {
            if (!this.searchObj) { this.searchObj = { pageIndex:newIndex }} else { this.searchObj.pageIndex = newIndex; }
            this.fetchRows();
        }
    },
    watch: {
        rows: function(newRows) { if (moqui.isArray(newRows)) { this.rowList = newRows; } else { this.fetchRows(); } },
        search: function () { this.fetchRows(); }
    },
    mounted: function() {
        if (this.search) { this.searchObj = this.search; } else { this.searchObj = this.$root.currentParameters; }
        if (moqui.isArray(this.rows)) { this.rowList = this.rows; } else { this.fetchRows(); }
    }
});

/* ========== form field widget components ========== */
Vue.component('m-date-time', {
    name: "mDateTime",
    props: { id:String, name:{type:String,required:true}, value:String, type:{type:String,'default':'date-time'}, label:String,
        size:String, format:String, tooltip:String, form:String, required:String, rules:Array, disable:Boolean, autoYear:String,
        minuteStep:{type:Number,'default':5} },
    template:
    // NOTE: tried :fill-mask="formatVal" but results in all Y, only supports single character for mask placeholder... how to show more helpful date mask?
    // TODO: add back @focus="focusDate" @blur="blurDate" IFF needed given different mask/etc behavior
    '<q-input dense outlined stack-label :label="label" v-bind:value="value" v-on:input="$emit(\'input\', $event)" :rules="rules"' +
            ' :mask="inputMask" fill-mask :id="id" :name="name" :form="form" :disable="disable" :size="sizeVal" style="max-width:max-content;">' +
        '<template v-slot:prepend v-if="type==\'date\' || type==\'date-time\' || !type">' +
            '<q-icon name="event" class="cursor-pointer">' +
                '<q-popup-proxy ref="qDateProxy" transition-show="scale" transition-hide="scale">' +
                    '<q-date v-bind:value="value" v-on:input="$emit(\'input\', $event)" :mask="formatVal" @input="function(){$refs.qDateProxy.hide()}"></q-date>' +
                '</q-popup-proxy>' +
            '</q-icon>' +
        '</template>' +
        '<template v-slot:append v-if="type==\'time\' || type==\'date-time\' || !type">' +
            '<q-icon name="access_time" class="cursor-pointer">' +
                '<q-popup-proxy ref="qTimeProxy" transition-show="scale" transition-hide="scale">' +
                    '<q-time v-bind:value="value" v-on:input="$emit(\'input\', $event)" :mask="formatVal" format24h @input="function(){$refs.qTimeProxy.hide()}"></q-time>' +
                '</q-popup-proxy>' +
            '</q-icon>' +
        '</template>' +
        '<template v-slot:after><slot name="after"></slot></template>' +
    '</q-input>',
    // TODO: how to add before slot pass through without the small left margin when nothing in the slot? <template v-slot:before><slot name="before"></slot></template>
    // TODO handle required (:required="required == 'required' ? true : false")
    methods: {
        focusDate: function(event) {
            if (this.type === 'time' || this.autoYear === 'false') return;
            var curVal = this.value;
            if (!curVal || !curVal.length) {
                var startYear = (this.autoYear && this.autoYear.match(/^[12]\d\d\d$/)) ? this.autoYear : new Date().getFullYear()
                this.$emit('input', startYear);
            }
        },
        blurDate: function(event) {
            if (this.type === 'time') return;
            var curVal = this.value;
            // console.log("date/time unfocus val " + curVal);
            // if contains 'd ' (month/day missing, or month specified but date missing or partial) clear input
            // Sufficient to check for just 'd', since the mask handles any scenario where there would only be a single 'd'
            if (curVal.indexOf('d') > 0) { this.$emit('input', ''); return; }
            // default time to noon, or minutes to 00
            if (curVal.indexOf('hh:mm') > 0) { this.$emit('input', curVal.replace('hh:mm', '12:00')); return; }
            if (curVal.indexOf(':mm') > 0) { this.$emit('input', curVal.replace(':mm', ':00')); return; }
        }
    },
    computed: {
        formatVal: function() { var format = this.format; if (format && format.length) { return format; }
            return this.type === 'time' ? 'HH:mm' : (this.type === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm'); },
        inputMask: function() { var formatMask = this.formatVal; return formatMask.replace(/\w/g, '#') },
        extraFormatsVal: function() { return this.type === 'time' ? ['LT', 'LTS', 'HH:mm'] :
            (this.type === 'date' ? ['l', 'L', 'YYYY-MM-DD'] : ['YYYY-MM-DD HH:mm', 'YYYY-MM-DD HH:mm:ss', 'MM/DD/YYYY HH:mm']); },
        sizeVal: function() { var size = this.size; if (size && size.length) { return size; }
            return this.type === 'time' ? '9' : (this.type === 'date' ? '10' : '16'); },
        timePattern: function() { return '^(?:(?:([01]?\\d|2[0-3]):)?([0-5]?\\d):)?([0-5]?\\d)$'; }
    },
    mounted: function() {
        var vm = this;
        var value = this.value;
        var format = this.formatVal;
        var jqEl = $(this.$el);
        /* TODO
        if (this.type === "time") {
            jqEl.datetimepicker({toolbarPlacement:'top', debug:false, showClose:true, showClear:true, showTodayButton:true, useStrict:true,
                defaultDate:(value && value.length ? moment(value,this.formatVal) : null), format:format,
                extraFormats:this.extraFormatsVal, stepping:this.minuteStep, locale:this.$root.locale,
                keyBinds: {up: function () { if(this.date()) this.date(this.date().clone().add(1, 'H')); },
                           down: function () { if(this.date()) this.date(this.date().clone().subtract(1, 'H')); },
                           'control up': null, 'control down': null,
                           'shift up': function () { if(this.date()) this.date(this.date().clone().add(this.stepping(), 'm')); },
                           'shift down': function () { if(this.date()) this.date(this.date().clone().subtract(this.stepping(), 'm')); }}});
            jqEl.on("dp.change", function() { jqEl.val(jqEl.find("input").first().val()); jqEl.trigger("change"); vm.$emit('input', this.value); })

            jqEl.val(jqEl.find("input").first().val());

            // TODO if (this.tooltip && this.tooltip.length) jqEl.tooltip({ title: this.tooltip, placement: "auto" });
        } else {
            jqEl.datetimepicker({toolbarPlacement:'top', debug:false, showClose:true, showClear:true, showTodayButton:true, useStrict:true,
                defaultDate:(value && value.length ? moment(value,this.formatVal) : null), format:format,
                extraFormats:this.extraFormatsVal, stepping:this.minuteStep, locale:this.$root.locale,
                keyBinds: {up: function () { if(this.date()) this.date(this.date().clone().add(1, 'd')); },
                           down: function () { if(this.date()) this.date(this.date().clone().subtract(1, 'd')); },
                           'alt up': function () { if(this.date()) this.date(this.date().clone().add(1, 'M')); },
                           'alt down': function () { if(this.date()) this.date(this.date().clone().subtract(1, 'M')); },
                           'control up': null, 'control down': null,
                           'shift up': function () { if(this.date()) this.date(this.date().clone().add(1, 'y')); },
                           'shift down': function () { if(this.date()) this.date(this.date().clone().subtract(1, 'y')); } }});
            jqEl.on("dp.change", function() { jqEl.val(jqEl.find("input").first().val()); jqEl.trigger("change"); vm.$emit('input', this.value); })

            jqEl.val(jqEl.find("input").first().val());

            // TODO if (this.tooltip && this.tooltip.length) jqEl.tooltip({ title: this.tooltip, placement: "auto" });
        }
        */
        // TODO if (format === "YYYY-MM-DD") { jqEl.find('input').inputmask("yyyy-mm-dd", { clearIncomplete:false, clearMaskOnLostFocus:true, showMaskOnFocus:true, showMaskOnHover:false, removeMaskOnSubmit:false }); }
        // TODO if (format === "YYYY-MM-DD HH:mm") { jqEl.find('input').inputmask("yyyy-mm-dd hh:mm", { clearIncomplete:false, clearMaskOnLostFocus:true, showMaskOnFocus:true, showMaskOnHover:false, removeMaskOnSubmit:false }); }
    }
});
moqui.dateOffsets = [{value:'0',label:'This'},{value:'-1',label:'Last'},{value:'1',label:'Next'},
    {value:'-2',label:'-2'},{value:'2',label:'+2'},{value:'-3',label:'-3'},{value:'-4',label:'-4'},{value:'-6',label:'-6'},{value:'-12',label:'-12'}];
moqui.datePeriods = [{value:'day',label:'Day'},{value:'7d',label:'7 Days'},{value:'30d',label:'30 Days'},{value:'week',label:'Week'},{value:'weeks',label:'Weeks'},
    {value:'month',label:'Month'},{value:'months',label:'Months'},{value:'quarter',label:'Quarter'},{value:'year',label:'Year'},{value:'7r',label:'+/-7d'},{value:'30r',label:'+/-30d'}];
moqui.emptyOpt = {value:'',label:''};
Vue.component('m-date-period', {
    name: "mDatePeriod",
    props: { fields:{type:Object,required:true}, name:{type:String,required:true}, id:String, allowEmpty:Boolean,
        fromThruType:{type:String,'default':'date'}, form:String, tooltip:String, label:String },
    data: function() { return { fromThruMode:false, dateOffsets:moqui.dateOffsets.slice(), datePeriods:moqui.datePeriods.slice() } },
    template:
    '<div v-if="fromThruMode" class="row">' +
        '<m-date-time :name="name+\'_from\'" :id="id+\'_from\'" :label="label+\' From\'" :form="form" :type="fromThruType" v-model="fields[name+\'_from\']"></m-date-time>' +
        '<q-icon class="q-my-auto" name="remove"></q-icon>' +
        '<m-date-time :name="name+\'_thru\'" :id="id+\'_thru\'" :label="label+\' Thru\'" :form="form" :type="fromThruType" v-model="fields[name+\'_thru\']">' +
            '<template v-slot:after>' +
                '<q-btn dense flat icon="calendar_view_day" @click="toggleMode"><q-tooltip>Period Select Mode</q-tooltip></q-btn>' +
                '<q-btn dense flat icon="clear" @click="clearAll"><q-tooltip>Clear</q-tooltip></q-btn>' +
            '</template>' +
        '</m-date-time>' +
    '</div>' +
    '<div v-else class="row"><q-input dense outlined stack-label :label="label" v-model="fields[name+\'_pdate\']"' +
            ' mask="####-##-##" fill-mask :id="id" :name="name+\'_pdate\'" :form="form" style="max-width:max-content;">' +
        '<q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip>' +
        '<template v-slot:before>' +
            '<q-select class="q-pr-xs" dense outlined options-dense emit-value map-options v-model="fields[name+\'_poffset\']" :name="name+\'_poffset\'"' +
                ' stack-label label="Offset" :options="dateOffsets" :form="form" behavior="menu"></q-select>' +
            '<q-select dense outlined options-dense emit-value map-options v-model="fields[name+\'_period\']" :name="name+\'_period\'"' +
                ' stack-label label="Period" :options="datePeriods" :form="form" behavior="menu"></q-select>' +
        '</template>' +
        '<template v-slot:prepend>' +
            '<q-icon name="event" class="cursor-pointer">' +
                '<q-popup-proxy ref="qDateProxy" transition-show="scale" transition-hide="scale">' +
                    '<q-date v-model="fields[name+\'_pdate\']" mask="YYYY-MM-DD" @input="function(){$refs.qDateProxy.hide()}"></q-date>' +
                '</q-popup-proxy>' +
            '</q-icon>' +
        '</template>' +
        '<template v-slot:after>' +
            '<q-btn dense flat icon="date_range" @click="toggleMode"><q-tooltip>Date Range Mode</q-tooltip></q-btn>' +
            '<q-btn dense flat icon="clear" @click="clearAll"><q-tooltip>Clear</q-tooltip></q-btn>' +
        '</template>' +
    '</q-input></div>',
    methods: {
        toggleMode: function() { this.fromThruMode = !this.fromThruMode; },
        clearAll: function() {
            this.fields[this.name+'_pdate'] = null; this.fields[this.name+'_poffset'] = null; this.fields[this.name+'_period'] = null;
            this.fields[this.name+'_from'] = null; this.fields[this.name+'_thru'] = null;
        }
    },
    mounted: function() {
        var fromDate = this.fields[this.name+'_from'];
        var thruDate = this.fields[this.name+'_thru'];
        if (((fromDate && fromDate.length) || (thruDate && thruDate.length))) this.fromThruMode = true;
    }
});

Vue.component('m-display', {
    name: "mDisplay",
    props: { value:String, display:String, valueUrl:String, valueParameters:Object, dependsOn:Object, dependsOptional:Boolean, valueLoadInit:Boolean,
        fields:{type:Object}, tooltip:String, label:String, labelWrapper:Boolean, name:String, id:String },
    data: function() { return { curDisplay:this.display, loading:false } },
    template:
        '<q-input v-if="labelWrapper" dense outlined readonly stack-label :value="displayValue" :label="label" :id="id" :name="name" :loading="loading">' +
            '<q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip>' +
        '</q-input>' +
        '<span v-else :id="id">' +
            '<q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip><slot></slot>' +
            '{{displayValue}}' +
        '</span>',
    methods: {
        serverData: function() {
            var hasAllParms = true;
            var dependsOnMap = this.dependsOn;
            var parmMap = this.valueParameters;
            var reqData = { moquiSessionToken: this.$root.moquiSessionToken };
            for (var parmName in parmMap) { if (parmMap.hasOwnProperty(parmName)) reqData[parmName] = parmMap[parmName]; }
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                var doValue;
                if (this.fields) {
                    doValue = this.fields[dependsOnMap[doParm]];
                } else {
                    var doParmJqEl = $('#' + dependsOnMap[doParm]);
                    doValue = doParmJqEl.val();
                    if (!doValue) doValue = doParmJqEl.find('select').val();
                }
                if (!doValue) { hasAllParms = false; } else { reqData[doParm] = doValue; }
            }}
            reqData.hasAllParms = hasAllParms;
            return reqData;
        },
        populateFromUrl: function(params) {
            var reqData = this.serverData(params);
            // console.log("m-display populateFromUrl 1 " + this.valueUrl + " reqData.hasAllParms " + reqData.hasAllParms + " dependsOptional " + this.dependsOptional);
            // console.log(reqData);
            if (!this.valueUrl || !this.valueUrl.length) {
                console.warn("In m-display for " + this.name + " tried to populateFromUrl but no valueUrl");
                return;
            }
            if (!reqData.hasAllParms && !this.dependsOptional) {
                console.warn("In m-display for " + this.name + "  tried to populateFromUrl but not hasAllParms and not dependsOptional");
                this.$emit('input', null);
                this.curDisplay = null;
                return;
            }
            var vm = this;
            this.loading = true;
            $.ajax({ type:"POST", url:this.valueUrl, data:reqData, dataType:"text", headers:{Accept:'text/plain'},
                error:function(jqXHR, textStatus, errorThrown) {
                    vm.loading = false;
                    moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
                },
                success: function(defaultText) {
                    vm.loading = false;

                    var newLabel = '', newValue = '';
                    try {
                        var response = JSON.parse(defaultText);
                        if ($.isArray(response) && response.length) { response = response[0]; }
                        else if ($.isPlainObject(response) && response.hasOwnProperty('options') && response.options.length) { response = response.options[0]; }
                        if (response.hasOwnProperty('label')) { newLabel = response.label; }
                        if (response.hasOwnProperty('value')) { newValue = response.value; }
                    } catch(e) { }
                    if (!newLabel || !newLabel.length) newLabel = defaultText;
                    if (!newValue || !newValue.length) newValue = defaultText;

                    if (moqui.isNumber(newValue)) { newValue = newValue.toString(); }

                    vm.$emit('input', newValue);
                    if (vm.fields && vm.fields.length && vm.name && vm.name.length) { vm.fields[vm.name + "_display"] = newLabel; }
                    vm.curDisplay = newLabel;
                }
            });
        }
    },
    computed: {
        displayValue: function() { return this.curDisplay && this.curDisplay.length ? this.curDisplay : this.value; }
    },
    mounted: function() {
        if (this.valueUrl && this.valueUrl.length) {
            var dependsOnMap = this.dependsOn;
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                if (this.fields) {
                    this.$watch('fields.' + doParm, function() { this.populateFromUrl({term:this.value}); });
                } else {
                    // TODO: if no fields passed, use some sort of DOM-based value like jQuery val()?
                }
            } }
            // do initial populate if not a serverSearch or for serverSearch if we have an initial value do the search so we don't display the ID
            if (this.valueLoadInit) { this.populateFromUrl(); }
        }
    }
});

Vue.component('m-drop-down', {
    name: "mDropDown",
    props: { value:[Array,String], options:{type:Array,'default':function(){return [];}}, combo:Boolean,
        allowEmpty:Boolean, multiple:Boolean, requiredManualSelect:Boolean,
        optionsUrl:String, optionsParameters:Object, optionsLoadInit:Boolean,
        serverSearch:Boolean, serverDelay:{type:Number,'default':300}, serverMinLength:{type:Number,'default':1},
        labelField:{type:String,'default':'label'}, valueField:{type:String,'default':'value'},
        dependsOn:Object, dependsOptional:Boolean, form:String, fields:{type:Object},
        tooltip:String, label:String, name:String, id:String, disable:Boolean, onSelectGoTo:String },
    data: function() { return { curOptions:this.options, allOptions:this.options, lastVal:null, lastSearch:null, loading:false } },
    template:
        // was: ':fill-input="!multiple" hide-selected' changed to ':hide-selected="multiple"' to show selected to the left of input,
        //     fixes issues with fill-input where set values would sometimes not be displayed
        '<q-select ref="qSelect" v-bind:value="value" v-on:input="handleInput($event)"' +
                ' dense outlined options-dense use-input :hide-selected="multiple" :name="name" :id="id" :form="form"' +
                ' input-debounce="500" @filter="filterFn" :clearable="allowEmpty||multiple" :disable="disable"' +
                ' :multiple="multiple" :emit-value="!onSelectGoTo" map-options behavior="menu"' +
                ' :rules="[val => allowEmpty||multiple||val===\'\'||(val&&val.length)||\'Please select an option\']"' +
                ' stack-label :label="label" :loading="loading" :options="curOptions">' +
            '<q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip>' +
            '<template v-slot:no-option><q-item><q-item-section class="text-grey">No results</q-item-section></q-item></template>' +
            '<template v-if="multiple" v-slot:prepend><div>' +
                '<q-chip v-for="valueEntry in value" :key="valueEntry" dense size="md" class="q-my-xs" removable @remove="removeValue(valueEntry)">{{optionLabel(valueEntry)}}</q-chip>' +
            '</div></template>' +
            '<template v-slot:append><slot name="append"></slot></template>' +
            '<template v-slot:after>' +
                '<slot name="after"></slot>' +
            '</template>' +
        '</q-select>',
        // TODO: how to add before slot pass through without the small left margin when nothing in the slot? <template v-slot:before><slot name="before"></slot></template>
    methods: {
        handleInput: function($event) {
            // console.warn(this.onSelectGoTo + ": " + JSON.stringify($event));
            if (this.onSelectGoTo && this.onSelectGoTo.length) {
                if ($event[this.onSelectGoTo]) this.$root.setUrl($event[this.onSelectGoTo]);
            } else {
                this.$emit('input', $event);
            }
        },
        filterFn: function(search, doneFn, abortFn) {
            if (this.serverSearch) {
                if ((this.lastSearch === search) || (this.serverMinLength && ((search ? search.length : 0) < this.serverMinLength))) {
                    doneFn();
                } else {
                    this.lastSearch = search;
                    this.populateFromUrl({term:search}, doneFn, abortFn);
                }
            } else if (this.allOptions && this.allOptions.length) {
                var vm = this;
                if (search && search.length) {
                    doneFn(function() {
                        var needle = search.toLowerCase();
                        vm.curOptions = vm.allOptions.filter(function (v) {
                            return v.label && v.label.toLowerCase().indexOf(needle) > -1;
                        });
                    });
                } else {
                    if ((vm.curOptions ? vm.curOptions.length : 0) === (vm.allOptions ? vm.allOptions.length : 0)) {
                        doneFn();
                    } else {
                        doneFn(function() { vm.curOptions = vm.allOptions; });
                    }
                }
            } else if (this.optionsUrl && this.optionsUrl.length) {
                // no current options, get from server
                this.populateFromUrl({}, doneFn, abortFn);
            } else {
                console.error("m-drop-down " + this.name + " has no options and no options-url");
                abortFn();
            }
        },
        processOptionList: function(list, page, term) {
            var newData = [];
            var labelField = this.labelField;
            var valueField = this.valueField;
            $.each(list, function(idx, curObj) {
                var valueVal = curObj[valueField];
                var labelVal = curObj[labelField];
                newData.push(Object.assign(curObj, { value:valueVal||labelVal, label:labelVal||valueVal }));
            });
            return newData;
        },
        serverData: function(params) {
            var hasAllParms = true;
            var dependsOnMap = this.dependsOn;
            var parmMap = this.optionsParameters;
            var reqData = { moquiSessionToken: this.$root.moquiSessionToken };
            for (var parmName in parmMap) { if (parmMap.hasOwnProperty(parmName)) reqData[parmName] = parmMap[parmName]; }
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                var doValue;
                if (this.fields) {
                    doValue = this.fields[dependsOnMap[doParm]];
                } else {
                    var doParmJqEl = $('#' + dependsOnMap[doParm]);
                    doValue = doParmJqEl.val();
                    if (!doValue) doValue = doParmJqEl.find('select').val();
                }
                // console.warn("do " + doParm + ":" + dependsOnMap[doParm] + " val " + doValue);
                if (!doValue) { hasAllParms = false; } else { reqData[doParm] = doValue; }
            }}
            if (params) { reqData.term = params.term || ''; reqData.pageIndex = (params.page || 1) - 1; }
            else if (this.serverSearch) { reqData.term = ''; reqData.pageIndex = 0; }
            reqData.hasAllParms = hasAllParms;
            return reqData;
        },
        processResponse: function(data, params) {
            if (moqui.isArray(data)) {
                return { results:this.processOptionList(data, null, params.term) };
            } else {
                params.page = params.page || 1; // NOTE: 1 based index, is 0 based on server side
                var pageSize = data.pageSize || 20;
                return { results: this.processOptionList(data.options, params.page, params.term),
                    pagination: { more: (data.count ? (params.page * pageSize) < data.count : false) } };
            }
        },
        populateFromUrl: function(params, doneFn, abortFn) {
            var reqData = this.serverData(params);
            // console.log("populateFromUrl 1 " + this.optionsUrl + " reqData.hasAllParms " + reqData.hasAllParms + " dependsOptional " + this.dependsOptional);
            // console.log(reqData);
            if (!this.optionsUrl || !this.optionsUrl.length) {
                console.warn("In m-drop-down tried to populateFromUrl but no optionsUrl");
                if (abortFn) abortFn();
                return;
            }
            if (!reqData.hasAllParms && !this.dependsOptional) {
                console.warn("In m-drop-down tried to populateFromUrl but not hasAllParms and not dependsOptional");
                this.curOptions = [];
                this.allOptions = [];
                if (abortFn) abortFn();
                return;
            }
            var vm = this;
            this.loading = true;
            $.ajax({ type:"POST", url:this.optionsUrl, data:reqData, dataType:"json", headers:{Accept:'application/json'},
                error:function(jqXHR, textStatus, errorThrown) {
                    vm.loading = false;
                    moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
                    if (abortFn) abortFn();
                },
                success: function(data) {
                    vm.loading = false;
                    var list = moqui.isArray(data) ? data : data.options;
                    var procList = vm.processOptionList(list, null, (params ? params.term : null));
                    if (list) {
                        if (doneFn) {
                            doneFn(function() {
                                vm.setNewOptions(procList);
                            });
                        } else {
                            vm.setNewOptions(procList);
                            if (vm.$refs.qSelect) vm.$refs.qSelect.refresh();
                            // tried this for some drop-downs getting value set and have options but not showing current value's label, didn't work: if (vm.$refs.qSelect) vm.$nextTick(function() { vm.$refs.qSelect.refresh(); });
                            // NOTE: don't want to do this, was mistakenly used before, use only if setting the input value string to an explicit value otherwise clears it and calls filter again: vm.$refs.qSelect.updateInputValue();
                        }
                    }
                }
            });
        },
        setNewOptions: function(options) {
            this.curOptions = options;
            if (this.multiple && this.allOptions && this.allOptions.length && this.value && this.value.length && moqui.isArray(this.value)) {
                // for multiple retain current value(s) in allOptions, at end of Array, so that in most cases already selected values are retained
                var newAllOptions = options.slice();
                for (var vi = 0; vi < this.value.length; vi++) {
                    var curValue = this.value[vi];
                    for (var oi = 0; oi < this.allOptions.length; oi++) {
                        var curOption = this.allOptions[oi];
                        if (curValue === curOption.value) newAllOptions.push(curOption);
                    }
                }
                this.allOptions = newAllOptions;
            } else {
                this.allOptions = options;
                this.checkCurrentValue(this.allOptions);
            }
        },
        checkCurrentValue: function(options) {
            // if cur value not in new options either clear it or set it to the new first option in list if !allowEmpty
            var isInNewOptions = false;
            var valIsArray = moqui.isArray(this.value);
            if (this.value && this.value.length && options) for (var i=0; i<options.length; i++) {
                var curObj = options[i];
                // console.warn("option val " + curObj.value + " cur value " + JSON.stringify(this.value) + " valIsArray " + valIsArray + " is in value " + (valIsArray ? this.value.includes(curObj.value) : curObj.value === this.value));
                if (valIsArray ? this.value.includes(curObj.value) : curObj.value === this.value) {
                    isInNewOptions = true;
                    break;
                }
            }

            // console.warn("curOptions updated " + this.name + " allowEmpty " + this.allowEmpty + " value '" + this.value + "' " + " isInNewOptions " + isInNewOptions + ": " + JSON.stringify(options));
            if (!isInNewOptions) {
                if (!this.allowEmpty && !this.multiple && options && options.length && options[0].value && (!this.requiredManualSelect || options.length === 1)) {
                    // simulate normal select behavior with no empty option (not allowEmpty) where first value is selected by default
                    // console.warn("checkCurrentValue setting " + this.name + " to " + options[0].value + " options " + options.length);
                    this.$emit('input', options[0].value);
                } else {
                    // console.warn("setting " + this.name + " to null");
                    this.$emit('input', null);
                }
            }
        },
        optionLabel: function(value) {
            var options = this.allOptions;
            if (!options || !options.length) return "";
            for (var i=0; i < options.length; i++) {
                var curOption = options[i];
                if (value === curOption.value) return curOption.label;
            }
            return "";
        },
        removeValue: function(value) {
            var curValueArr = this.value;
            if (!moqui.isArray(curValueArr)) { console.warn("Tried to remove value from m-drop-down multiple " + this.name + " but value is not an Array"); return; }
            var newValueArr = [];
            for (var i = 0; i < curValueArr.length; i++) {
                var valueEntry = curValueArr[i];
                if (valueEntry !== value) newValueArr.push(valueEntry);
            }
            if (curValueArr.length !== newValueArr.length) this.$emit('input', newValueArr);
        },
        clearAll: function() { this.$emit('input', null); }
    },
    mounted: function() {
        // TODO: handle combo somehow: if (this.combo) { opts.tags = true; opts.tokenSeparators = [',',' ']; }

        if (this.serverSearch) {
            if (!this.optionsUrl) console.error("m-drop-down in form " + this.form + " has no options-url but has server-search=true");
        }
        if (this.optionsUrl && this.optionsUrl.length) {
            var dependsOnMap = this.dependsOn;
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                if (this.fields) {
                    var vm = this;
                    this.$watch('fields.' + dependsOnMap[doParm], function() {
                        // in the case of dependency change clear current value
                        vm.$emit('input', null);
                        vm.populateFromUrl({term:vm.lastSearch});
                    });
                } else {
                    // TODO: if no fields passed, use some sort of DOM-based value like jQuery val()?
                }
            } }
            // do initial populate if not a serverSearch or for serverSearch if we have an initial value do the search so we don't display the ID
            if (this.optionsLoadInit) {
                if (!this.serverSearch) { this.populateFromUrl(); }
                else if (this.value && this.value.length && moqui.isString(this.value)) { this.populateFromUrl({term:this.value}); }
            }
        }
        // simulate normal select behavior with no empty option (not allowEmpty) where first value is selected by default - but only do for 1 option to force user to think and choose from multiple
        if (!this.multiple && !this.allowEmpty && (!this.value || !this.value.length) && this.options && this.options.length && (!this.requiredManualSelect || this.options.length === 1)) {
            this.$emit('input', this.options[0].value);
        }
    }
    /* probably don't need, remove sometime:
    watch: {
        // need to watch for change to options prop? options: function(options) { this.curOptions = options; },
        curOptionsFoo: function(options) {
            // save the lastVal if there is one to remember what was selected even if new options don't have it, just in case options change again
            if (this.value && this.value.length) this.lastVal = this.value;

        }
    }
     */
});

Vue.component('m-text-line', {
    name: "mTextLine",
    props: { value:String, type:{type:String,'default':'text'}, id:String, name:String, size:String, fields:{type:Object},
        label:String, tooltip:String, prefix:String, disable:Boolean, mask:String, fillMask:String, rules:Array,
        defaultUrl:String, defaultParameters:Object, dependsOn:Object, dependsOptional:Boolean, defaultLoadInit:Boolean },
    data: function() { return { loading:false } },
    template:
        '<q-input dense outlined stack-label :label="label" :prefix="prefix" v-bind:value="value" v-on:input="$emit(\'input\', $event)" :type="type"' +
                ' :id="id" :name="name" :size="size" :loading="loading" :rules="rules" :disable="disable" :mask="mask" :fill-mask="fillMask"' +
                ' autocapitalize="off" autocomplete="off">' +
            '<q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip>' +
        '</q-input>',
    methods: {
        serverData: function() {
            var hasAllParms = true;
            var dependsOnMap = this.dependsOn;
            var parmMap = this.defaultParameters;
            var reqData = { moquiSessionToken: this.$root.moquiSessionToken };
            for (var parmName in parmMap) { if (parmMap.hasOwnProperty(parmName)) reqData[parmName] = parmMap[parmName]; }
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                var doValue;
                if (this.fields) {
                    doValue = this.fields[dependsOnMap[doParm]];
                } else {
                    var doParmJqEl = $('#' + dependsOnMap[doParm]);
                    doValue = doParmJqEl.val();
                    if (!doValue) doValue = doParmJqEl.find('select').val();
                }
                if (!doValue) { hasAllParms = false; } else { reqData[doParm] = doValue; }
            }}
            reqData.hasAllParms = hasAllParms;
            return reqData;
        },
        populateFromUrl: function(params) {
            var reqData = this.serverData(params);
            // console.log("m-text-line populateFromUrl 1 " + this.defaultUrl + " reqData.hasAllParms " + reqData.hasAllParms + " dependsOptional " + this.dependsOptional);
            // console.log(reqData);
            if (!this.defaultUrl || !this.defaultUrl.length) {
                console.warn("In m-text-line tried to populateFromUrl but no defaultUrl");
                return;
            }
            if (!reqData.hasAllParms && !this.dependsOptional) {
                console.warn("In m-text-line tried to populateFromUrl but not hasAllParms and not dependsOptional");
                return;
            }
            var vm = this;
            this.loading = true;
            $.ajax({ type:"POST", url:this.defaultUrl, data:reqData, dataType:"text",
                error:function(jqXHR, textStatus, errorThrown) {
                    vm.loading = false;
                    moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
                },
                success: function(defaultText) {
                    vm.loading = false;
                    if (defaultText && defaultText.length) vm.$emit('input', defaultText);
                }
            });
        }
    },
    mounted: function() {
        if (this.defaultUrl && this.defaultUrl.length) {
            var dependsOnMap = this.dependsOn;
            for (var doParm in dependsOnMap) { if (dependsOnMap.hasOwnProperty(doParm)) {
                if (this.fields) {
                    this.$watch('fields.' + doParm, function() { this.populateFromUrl({term:this.value}); });
                } else {
                    // TODO: if no fields passed, use some sort of DOM-based value like jQuery val()?
                }
            } }
            // do initial populate if not a serverSearch or for serverSearch if we have an initial value do the search so we don't display the ID
            if (this.defaultLoadInit) { this.populateFromUrl(); }
        }
    }
});

/* Lazy loading Chart JS wrapper component */
Vue.component('m-chart', {
    name: 'mChart',
    props: { config:{type:Object,required:true}, height:{type:String,'default':'400px'}, width:{type:String,'default':'100%'} },
    template: '<div class="chart-container" style="position:relative;" :style="{height:height,width:width}"><canvas ref="canvas"></canvas></div>',
    data: function() { return { instance:null } },
    mounted: function() {
        var vm = this;
        moqui.loadScript('https://cdnjs.cloudflare.com/ajax/libs/Chart.js/2.9.3/Chart.min.js', function(err) {
            if (err) return;
            vm.instance = new Chart(vm.$refs.canvas, vm.config);
        }, function() { return !!window.Chart; });
    }
});
/* Lazy loading CK Editor wrapper component, based on https://github.com/ckeditor/ckeditor4-vue */
/* see https://ckeditor.com/docs/ckeditor4/latest/api/CKEDITOR_config.html */
Vue.component('m-ck-editor', {
    name: 'mCkEditor',
    template:'<div><textarea ref="area"></textarea></div>',
    props: { value:{type:String,'default':''}, useInline:Boolean, config:Object, readOnly:{type:Boolean,'default':null} },
    data: function() { return { destroyed:false, ckeditor:null } },
    mounted: function() {
        var vm = this;
        moqui.loadScript('https://cdn.ckeditor.com/4.14.1/standard-all/ckeditor.js', function(err) {
            if (err) return;
            if (vm.destroyed) return;
            var config = vm.config || {};
            if (vm.readOnly !== null) config.readOnly = vm.readOnly;

            CKEDITOR.dtd.$removeEmpty['i'] = false;
            var method = vm.useInline ? 'inline' : 'replace';
            var editor = vm.ckeditor = CKEDITOR[method](vm.$refs.area, config);
            editor.on('instanceReady', function() {
                var data = vm.value;
                editor.fire('lockSnapshot');
                editor.setData(data, { callback: function() {
                    editor.on('change', function(evt) {
                        var curData = editor.getData();
                        if (vm.value !== curData) vm.$emit('input', curData, evt, editor);
                    });
                    editor.on('focus', function(evt) { vm.$emit('focus', evt, editor); });
                    editor.on('blur', function(evt) { vm.$emit('blur', evt, editor); });

                    var newData = editor.getData();
                    // Locking the snapshot prevents the 'change' event. Trigger it manually to update the bound data.
                    if (data !== newData) {
                        vm.$once('input', function() { vm.$emit('ready', editor); });
                        vm.$emit('input', newData);
                    } else {
                        vm.$emit('ready', editor);
                    }
                    editor.fire('unlockSnapshot');
                }});
            });
        }, function() { return !!window.CKEDITOR; });
    },
    beforeDestroy: function() {
        if (this.ckeditor) { this.ckeditor.destroy(); }
        this.destroyed = true;
    },
    watch: {
        value: function(val) { if (this.ckeditor && this.ckeditor.getData() !== val) this.ckeditor.setData(val); },
        readOnly: function(val) { if (this.ckeditor) this.ckeditor.setReadOnly( val ); }
    }
});
/* Lazy loading Simple MDE wrapper component */
Vue.component('m-simple-mde', {
    name: 'mSimpleMde',
    template:'<div><textarea ref="area"></textarea></div>',
    props: { value:{type:String,'default':''}, config:Object },
    data: function() { return { simplemde:null } },
    mounted: function() {
        var vm = this;
        moqui.loadStylesheet('https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.css');
        moqui.loadScript('https://cdnjs.cloudflare.com/ajax/libs/simplemde/1.11.2/simplemde.min.js', function(err) {
            if (err) return;
            // needed? forceSync:true
            var fullConfig = Object.assign({
                element: vm.$refs.area,
                initialValue: vm.value
            }, vm.config);
            var editor = vm.simplemde = new SimpleMDE(fullConfig);

            editor.codemirror.on('change', function(instance, changeObj) {
                if (changeObj.origin === 'setValue') return;
                var val = editor.value();
                vm.$emit('input', val);
            });
            editor.codemirror.on('blur', function() {
                var val = editor.value();
                vm.$emit('blur', val);
            });

            vm.$nextTick(function() { vm.$emit('initialized', editor); });
        }, function() { return !!window.SimpleMDE; });
    },
    watch: { value: function(val) { if (this.simplemde && this.simplemde.value() !== val) this.simplemde.value(val); } }
});


/* ========== webrootVue - root Vue component with router ========== */
Vue.component('m-subscreens-tabs', {
    name: "mSubscreensTabs",
    data: function() { return { pathIndex:-1 }},
    /* NOTE DEJ 20200729 In theory could use q-route-tab and show active automatically, attempted to mimic Vue Router sufficiently for this to work but no luck yet:
    '<div v-if="subscreens.length > 0"><q-tabs dense no-caps align="left" active-color="primary" indicator-color="primary">' +
        '<q-route-tab v-for="tab in subscreens" :key="tab.name" :name="tab.name" :label="tab.title" :disable="tab.disableLink" :to="tab.pathWithParams"></q-route-tab>' +
    '</q-tabs><q-separator class="q-mb-md"></q-separator></div>',
     */
    template:
    '<div v-if="subscreens.length > 1"><q-tabs dense no-caps align="left" active-color="primary" indicator-color="primary" :value="activeTab">' +
        '<q-tab v-for="tab in subscreens" :key="tab.name" :name="tab.name" :label="tab.title" :disable="tab.disableLink" @click.prevent="goTo(tab.pathWithParams)"></q-tab>' +
    '</q-tabs><q-separator class="q-mb-md"></q-separator></div>',
    methods: {
        goTo: function(pathWithParams) { this.$root.setUrl(this.$root.getLinkPath(pathWithParams)); }
    },
    computed: {
        subscreens: function() {
            if (!this.pathIndex || this.pathIndex < 0) return [];
            var navMenu = this.$root.navMenuList[this.pathIndex];
            if (!navMenu || !navMenu.subscreens) return [];
            return navMenu.subscreens;
        },
        activeTab: function () {
            if (!this.pathIndex || this.pathIndex < 0) return null;
            var navMenu = this.$root.navMenuList[this.pathIndex];
            if (!navMenu || !navMenu.subscreens) return null;
            var activeName = null;
            $.each(navMenu.subscreens, function(idx, tab) { if (tab.active) activeName = tab.name; });
            return activeName;
        }
    },
    // this approach to get pathIndex won't work if the m-subscreens-active tag comes before m-subscreens-tabs
    mounted: function() { this.pathIndex = this.$root.activeSubscreens.length; }
});
Vue.component('m-subscreens-active', {
    name: "mSubscreensActive",
    data: function() { return { activeComponent:moqui.EmptyComponent, pathIndex:-1, pathName:null } },
    template: '<component :is="activeComponent"></component>',
    // method instead of a watch on pathName so that it runs even when newPath is the same for non-static reloading
    methods: { loadActive: function() {
        var vm = this;
        var root = vm.$root;
        var pathIndex = vm.pathIndex;
        var curPathList = root.currentPathList;
        var newPath = curPathList[pathIndex];
        var pathChanged = (this.pathName !== newPath);
        this.pathName = newPath;
        if (!newPath || newPath.length === 0) {
            console.info("in m-subscreens-active newPath is empty, loading EmptyComponent and returning true");
            this.activeComponent = moqui.EmptyComponent;
            return true;
        }
        var fullPath = root.basePath + '/' + curPathList.slice(0, pathIndex + 1).join('/');
        if (!pathChanged && moqui.componentCache.containsKey(fullPath)) {
            // no need to reload component
            // console.info("in m-subscreens-active returning false because pathChanged is false and componentCache contains " + fullPath);
            return false;
        }
        var urlInfo = { path:fullPath };
        if (pathIndex === (curPathList.length - 1)) {
            var extra = root.extraPathList;
            if (extra && extra.length > 0) { urlInfo.extraPath = extra.join('/'); }
        }
        var search = root.currentSearch;
        if (search && search.length > 0) { urlInfo.search = search; }
        urlInfo.bodyParameters = root.bodyParameters;
        var navMenuItem = root.navMenuList[pathIndex + root.basePathSize];
        if (navMenuItem && navMenuItem.renderModes) urlInfo.renderModes = navMenuItem.renderModes;
        console.info('m-subscreens-active loadActive pathIndex ' + pathIndex + ' pathName ' + vm.pathName + ' urlInfo ' + JSON.stringify(urlInfo));
        root.loading++;
        root.currentLoadRequest = moqui.loadComponent(urlInfo, function(comp) {
            root.currentLoadRequest = null;
            vm.activeComponent = comp;
            root.loading--;
        });
        return true;
    }},
    mounted: function() { this.$root.addSubscreen(this); }
});

Vue.component('m-menu-nav-item', {
    name: "mMenuNavItem",
    props: { menuIndex:Number },
    template:
    '<q-expansion-item v-if="navMenuItem && navMenuItem.subscreens && navMenuItem.subscreens.length" :value="true" :content-inset-level="0.3"' +
            ' switch-toggle-side dense dense-toggle expanded-icon="arrow_drop_down" :to="navMenuItem.pathWithParams" @input="go">' +
        '<template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>' +
        '<template v-slot:default><m-menu-subscreen-item v-for="(subscreen, ssIndex) in navMenuItem.subscreens" :key="subscreen.name" :menu-index="menuIndex" :subscreen-index="ssIndex"></m-menu-subscreen-item></template>' +
    '</q-expansion-item>' +
    '<q-expansion-item v-else-if="navMenuItem && navMenuItem.savedFinds && navMenuItem.savedFinds.length" :value="true" :content-inset-level="0.3"' +
            ' switch-toggle-side dense dense-toggle expanded-icon="arrow_drop_down" :to="navMenuItem.pathWithParams" @input="go">' +
        '<template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>' +
        '<template v-slot:default><q-expansion-item v-for="(savedFind, ssIndex) in navMenuItem.savedFinds" :key="savedFind.name"' +
                ' :value="false" switch-toggle-side dense dense-toggle expand-icon="chevron_right" :to="savedFind.pathWithParams" @input="goPath(savedFind.pathWithParams)">' +
            '<template v-slot:header><m-menu-item-content :menu-item="savedFind" :active="savedFind.active"/></template>' +
        '</q-expansion-item></template>' +
    '</q-expansion-item>' +
    '<q-expansion-item v-else-if="menuIndex < (navMenuLength - 1)" :value="true" :content-inset-level="0.3"' +
            ' switch-toggle-side dense dense-toggle expanded-icon="arrow_drop_down" :to="navMenuItem.pathWithParams" @input="go">' +
        '<template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>' +
        '<template v-slot:default><m-menu-nav-item :menu-index="menuIndex + 1"></m-menu-nav-item></template>' +
    '</q-expansion-item>' +
    '<q-expansion-item v-else-if="navMenuItem" :value="false" switch-toggle-side dense dense-toggle expand-icon="arrow_right" :to="navMenuItem.pathWithParams" @input="go">' +
        '<template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>' +
    '</q-expansion-item>',
    methods: {
        go: function go() { this.$root.setUrl(this.navMenuItem.pathWithParams); },
        goPath: function goPath(path) { this.$root.setUrl(path); }
    },
    computed: {
        navMenuItem: function() { return this.$root.navMenuList[this.menuIndex]; },
        navMenuLength: function() { return this.$root.navMenuList.length; }
    }
});
Vue.component('m-menu-subscreen-item', {
    name: "mMenuSubscreenItem",
    props: { menuIndex:Number, subscreenIndex:Number },
    template:
    '<m-menu-nav-item v-if="subscreen.active" :menu-index="menuIndex + 1"></m-menu-nav-item>' +
    '<q-expansion-item v-else :value="false" switch-toggle-side dense dense-toggle expand-icon="arrow_right" :to="subscreen.pathWithParams" @input="go">' +
        '<template v-slot:header><m-menu-item-content :menu-item="subscreen"></m-menu-item-content></template>' +
    '</q-expansion-item>',
    methods: { go: function go() { this.$root.setUrl(this.subscreen.pathWithParams); } },
    computed: { subscreen: function() { return this.$root.navMenuList[this.menuIndex].subscreens[this.subscreenIndex]; } }
});
Vue.component('m-menu-item-content', {
    name: "mMenuItemContent",
    props: { menuItem:Object, active:Boolean },
    template:
    '<div class="q-item__section column q-item__section--main justify-center"><div class="q-item__label">' +
        '<i v-if="menuItem.image && menuItem.imageType === \'icon\'" :class="menuItem.image" style="padding-right: 8px;"></i>' +
        /* TODO: images don't line up vertically, padding-top and margin-top do nothing, very annoying layout stuff, for another time... */
        '<span v-else-if="menuItem.image" style="padding-right:8px;"><img :src="menuItem.image" :alt="menuItem.title" height="14" class="invertible"></span>' +
        '<span :class="{\'text-primary\':active}">{{menuItem.title}}</span>' +
    '</div></div>'
});


moqui.webrootVue = new Vue({
    el: '#apps-root',
    data: { basePath:"", linkBasePath:"", currentPathList:[], extraPathList:[], currentParameters:{}, bodyParameters:null,
        activeSubscreens:[], navMenuList:[], navHistoryList:[], navPlugins:[], accountPlugins:[], notifyHistoryList:[],
        lastNavTime:Date.now(), loading:0, currentLoadRequest:null, activeContainers:{},
        moquiSessionToken:"", appHost:"", appRootPath:"", userId:"", locale:"en",
        notificationClient:null, qzVue:null, leftOpen:false, moqui:moqui },
    methods: {
        setUrl: function(url, bodyParameters, onComplete) {
            // cancel current load if needed
            if (this.currentLoadRequest) {
                console.log("Aborting current page load currentLinkUrl " + this.currentLinkUrl + " url " + url);
                this.currentLoadRequest.abort();
                this.currentLoadRequest = null;
                this.loading = 0;
            }
            // always set bodyParameters, setting to null when not specified to clear out previous
            this.bodyParameters = bodyParameters;
            url = this.getLinkPath(url);
            // console.info('setting url ' + url + ', cur ' + this.currentLinkUrl);
            if (this.currentLinkUrl === url && url !== this.linkBasePath) {
                this.reloadSubscreens(); /* console.info('reloading, same url ' + url); */
                if (onComplete) this.callOnComplete(onComplete, this.currentPath);
            } else {
                var redirectedFrom = this.currentPath;
                var urlInfo = moqui.parseHref(url);
                // clear out extra path, to be set from nav menu data if needed
                this.extraPathList = [];
                // set currentSearch before currentPath so that it is available when path updates
                this.currentSearch = urlInfo.search;
                this.currentPath = urlInfo.path;
                // with url cleaned up through setters now get current screen url for menu
                var srch = this.currentSearch;
                var screenUrl = this.currentPath + (srch.length > 0 ? '?' + srch : '');
                if (!screenUrl || screenUrl.length === 0) return;
                console.info("Current URL changing to " + screenUrl);
                this.lastNavTime = Date.now();
                // TODO: somehow only clear out activeContainers that are in subscreens actually reloaded? may cause issues if any but last screen have m-dynamic-container
                this.activeContainers = {};

                // update menu, which triggers update of screen/subscreen components
                var vm = this;
                var menuDataUrl = this.appRootPath && this.appRootPath.length && screenUrl.indexOf(this.appRootPath) === 0 ?
                    this.appRootPath + "/menuData" + screenUrl.slice(this.appRootPath.length) : "/menuData" + screenUrl;
                $.ajax({ type:"GET", url:menuDataUrl, dataType:"text", error:moqui.handleAjaxError, success: function(outerListText) {
                    var outerList = null;
                    // console.log("menu response " + outerListText);
                    try { outerList = JSON.parse(outerListText); } catch (e) { console.info("Error parson menu list JSON: " + e); }
                    if (outerList && moqui.isArray(outerList)) {
                        vm.navMenuList = outerList;
                        if (onComplete) vm.callOnComplete(onComplete, redirectedFrom);
                        /* console.info('navMenuList ' + JSON.stringify(outerList)); */
                    }
                }});

                // set the window URL
                window.history.pushState(null, this.ScreenTitle, url);
                // scroll to top
                document.documentElement.scrollTop = 0;
                document.body.scrollTop = 0;
            }
        },
        callOnComplete: function(onComplete, redirectedFrom) {
            if (!onComplete) return;
            var route = this.getRoute();
            if (redirectedFrom) route.redirectedFrom = redirectedFrom;
            onComplete(route);
        },
        getRoute: function() {
            return { name:this.currentPathList[this.currentPathList.length-1], meta:{}, path:this.currentPath,
                hash:'', query:this.currentParameters, params:this.bodyParameters||{}, fullPath:this.currentLinkUrl, matched:[] };
        },
        setParameters: function(parmObj) {
            if (parmObj) { this.$root.currentParameters = $.extend({}, this.$root.currentParameters, parmObj); }
            this.$root.reloadSubscreens();
        },
        addSubscreen: function(saComp) {
            var pathIdx = this.activeSubscreens.length;
            // console.info('addSubscreen idx ' + pathIdx + ' pathName ' + this.currentPathList[pathIdx]);
            saComp.pathIndex = pathIdx;
            // setting pathName here handles initial load of m-subscreens-active; this may be undefined if we have more activeSubscreens than currentPathList items
            saComp.loadActive();
            this.activeSubscreens.push(saComp);
        },
        reloadSubscreens: function() {
            // console.info('reloadSubscreens path ' + JSON.stringify(this.currentPathList) + ' currentParameters ' + JSON.stringify(this.currentParameters) + ' currentSearch ' + this.currentSearch);
            var fullPathList = this.currentPathList;
            var activeSubscreens = this.activeSubscreens;
            console.info("reloadSubscreens currentPathList " + JSON.stringify(this.currentPathList));
            if (fullPathList.length === 0 && activeSubscreens.length > 0) {
                activeSubscreens.splice(1);
                activeSubscreens[0].loadActive();
                return;
            }
            for (var i=0; i<activeSubscreens.length; i++) {
                if (i >= fullPathList.length) break;
                // always try loading the active subscreen and see if actually loaded
                var loaded = activeSubscreens[i].loadActive();
                // clear out remaining activeSubscreens, after first changed loads its placeholders will register and load
                if (loaded) activeSubscreens.splice(i+1);
            }
        },
        goPreviousScreen: function() {
            var currentPath = this.currentPath;
            var navHistoryList = this.navHistoryList;
            var prevHist;
            for (var hi = 0; hi < navHistoryList.length; hi++) {
                if (navHistoryList[hi].pathWithParams.indexOf(currentPath) < 0) { prevHist = navHistoryList[hi]; break; } }
            if (prevHist && prevHist.pathWithParams && prevHist.pathWithParams.length) this.setUrl(prevHist.pathWithParams)
        },
        // all container components added with this must have reload() and load(url) methods
        addContainer: function(contId, comp) { this.activeContainers[contId] = comp; },
        reloadContainer: function(contId) { var contComp = this.activeContainers[contId];
            if (contComp) { contComp.reload(); } else { console.error("Container with ID " + contId + " not found, not reloading"); }},
        loadContainer: function(contId, url) { var contComp = this.activeContainers[contId];
            if (contComp) { contComp.load(url); } else { console.error("Container with ID " + contId + " not found, not loading url " + url); }},

        addNavPlugin: function(url) { var vm = this; moqui.loadComponent(this.appRootPath + url, function(comp) { vm.navPlugins.push(comp); }) },
        addNavPluginsWait: function(urlList, urlIndex) { if (urlList && urlList.length > urlIndex) {
            this.addNavPlugin(urlList[urlIndex]);
            var vm = this;
            if (urlList.length > (urlIndex + 1)) { setTimeout(function(){ vm.addNavPluginsWait(urlList, urlIndex + 1); }, 500); }
        } },
        addAccountPlugin: function(url) { var vm = this; moqui.loadComponent(this.appRootPath + url, function(comp) { vm.accountPlugins.push(comp); }) },
        addAccountPluginsWait: function(urlList, urlIndex) { if (urlList && urlList.length > urlIndex) {
            this.addAccountPlugin(urlList[urlIndex]);
            var vm = this;
            if (urlList.length > (urlIndex + 1)) { setTimeout(function(){ vm.addAccountPluginsWait(urlList, urlIndex + 1); }, 500); }
        } },

        addNotify: function(message, type) {
            var histList = this.notifyHistoryList.slice(0);
            var nowDate = new Date();
            var nh = nowDate.getHours(); if (nh < 10) nh = '0' + nh;
            var nm = nowDate.getMinutes(); if (nm < 10) nm = '0' + nm;
            // var ns = nowDate.getSeconds(); if (ns < 10) ns = '0' + ns;
            histList.unshift({message:message, type:type, time:(nh + ':' + nm)}); //  + ':' + ns
            while (histList.length > 25) { histList.pop(); }
            this.notifyHistoryList = histList;
        },
        switchDarkLight: function() {
            this.$q.dark.toggle();
            $.ajax({ type:'POST', url:(this.appRootPath + '/apps/setPreference'), error:moqui.handleAjaxError,
                data:{ moquiSessionToken:this.moquiSessionToken, preferenceKey:'QUASAR_DARK', preferenceValue:(this.$q.dark.isActive ? 'true' : 'false') } });
        },
        toggleLeftOpen: function() {
            this.leftOpen = !this.leftOpen;
            $.ajax({ type:'POST', url:(this.appRootPath + '/apps/setPreference'), error:moqui.handleAjaxError,
                data:{ moquiSessionToken:this.moquiSessionToken, preferenceKey:'QUASAR_LEFT_OPEN', preferenceValue:(this.leftOpen ? 'true' : 'false') } });
        },
        stopProp: function (e) { e.stopPropagation(); },
        getNavHref: function(navIndex) {
            if (!navIndex) navIndex = this.navMenuList.length - 1;
            var navMenu = this.navMenuList[navIndex];
            if (navMenu.extraPathList && navMenu.extraPathList.length) {
                var href = navMenu.path + '/' + navMenu.extraPathList.join('/');
                var questionIdx = navMenu.pathWithParams.indexOf("?");
                if (questionIdx > 0) { href += navMenu.pathWithParams.slice(questionIdx); }
                return href;
            } else {
                return navMenu.pathWithParams || navMenu.path;
            }
        },
        getLinkPath: function(path) {
            if (moqui.isPlainObject(path)) path = moqui.makeHref(path);
            if (this.appRootPath && this.appRootPath.length && path.indexOf(this.appRootPath) !== 0) path = this.appRootPath + path;
            var pathList = path.split('/');
            // element 0 in array after split is empty string from leading '/'
            var wrapperIdx = this.appRootPath ? 2 : 1;
            if (pathList.length > wrapperIdx) {
                pathList[wrapperIdx] = this.linkBasePath.slice(1);
                path = pathList.join("/");
            }
            return path;
        },
        getQuasarColor: function(bootstrapColor) { return moqui.getQuasarColor(bootstrapColor); }
    },
    watch: {
        navMenuList: function(newList) { if (newList.length > 0) {
            var cur = newList[newList.length - 1];
            var par = newList.length > 1 ? newList[newList.length - 2] : null;
            // if there is an extraPathList set it now
            if (cur.extraPathList) this.extraPathList = cur.extraPathList;
            // make sure full currentPathList and activeSubscreens is populated (necessary for minimal path urls)
            // fullPathList is the path after the base path, menu and link paths are in the screen tree context only so need to subtract off the appRootPath (Servlet Context Path)
            var basePathSize = this.basePathSize;
            var fullPathList = cur.path.split('/').slice(basePathSize + 1);
            console.info('nav updated fullPath ' + JSON.stringify(fullPathList) + ' currentPathList ' + JSON.stringify(this.currentPathList) + ' cur.path ' + cur.path + ' basePathSize ' + basePathSize);
            this.currentPathList = fullPathList;
            this.reloadSubscreens();

            // update history and document.title
            var newTitle = (par ? par.title + ' - ' : '') + cur.title;
            var curUrl = cur.pathWithParams; var questIdx = curUrl.indexOf("?");
            if (questIdx > 0) {
                var excludeKeys = ["pageIndex", "orderBySelect", "orderByField", "moquiSessionToken"];
                var parmList = curUrl.substring(questIdx+1).split("&");
                curUrl = curUrl.substring(0, questIdx);
                var dpCount = 0;
                var titleParms = "";
                for (var pi=0; pi<parmList.length; pi++) {
                    var parm = parmList[pi];
                    if (curUrl.indexOf("?") === -1) { curUrl += "?"; } else { curUrl += "&"; }
                    curUrl += parm;
                    // from here down only add to title parms
                    if (dpCount > 3) continue; // add up to 4 parms to the title
                    var eqIdx = parm.indexOf("=");
                    if (eqIdx > 0) {
                        var key = parm.substring(0, eqIdx);
                        var value = parm.substring(eqIdx + 1);
                        if (key.indexOf("_op") > 0 || key.indexOf("_not") > 0 || key.indexOf("_ic") > 0 || excludeKeys.indexOf(key) > 0 || key === value) continue;
                        if (titleParms.length > 0) titleParms += ", ";
                        titleParms += decodeURIComponent(value);
                        dpCount++;
                    }
                }
                if (titleParms.length > 0) {
                    if (titleParms.length > 70) titleParms = titleParms.substring(0, 70) + "...";
                    newTitle = newTitle + " (" + titleParms + ")";
                }
            }
            var navHistoryList = this.navHistoryList;
            for (var hi=0; hi<navHistoryList.length;) {
                if (navHistoryList[hi].pathWithParams === curUrl) { navHistoryList.splice(hi,1); } else { hi++; } }
            navHistoryList.unshift({ title:newTitle, pathWithParams:curUrl, image:cur.image, imageType:cur.imageType });
            while (navHistoryList.length > 25) { navHistoryList.pop(); }
            document.title = newTitle;
        }},
        currentPathList: function(newList) {
            // console.info('set currentPathList to ' + JSON.stringify(newList) + ' activeSubscreens.length ' + this.activeSubscreens.length);
            var lastPath = newList[newList.length - 1];
            if (lastPath) { $(this.$el).removeClass().addClass(lastPath); }
        }
    },
    computed: {
        currentPath: {
            get: function() { var curPath = this.currentPathList; var extraPath = this.extraPathList;
                return this.basePath + (curPath && curPath.length > 0 ? '/' + curPath.join('/') : '') +
                    (extraPath && extraPath.length > 0 ? '/' + extraPath.join('/') : ''); },
            set: function(newPath) {
                if (!newPath || newPath.length === 0) { this.currentPathList = []; return; }
                if (newPath.slice(newPath.length - 1) === '/') newPath = newPath.slice(0, newPath.length - 1);
                if (newPath.indexOf(this.linkBasePath) === 0) { newPath = newPath.slice(this.linkBasePath.length + 1); }
                else if (newPath.indexOf(this.basePath) === 0) { newPath = newPath.slice(this.basePath.length + 1); }
                this.currentPathList = newPath.split('/');
            }
        },
        currentLinkPath: function() {
            var curPath = this.currentPathList; var extraPath = this.extraPathList;
            return this.linkBasePath + (curPath && curPath.length > 0 ? '/' + curPath.join('/') : '') +
                (extraPath && extraPath.length > 0 ? '/' + extraPath.join('/') : '');
        },
        currentSearch: {
            get: function() { return moqui.objToSearch(this.currentParameters); },
            set: function(newSearch) { this.currentParameters = moqui.searchToObj(newSearch); }
        },
        currentLinkUrl: function() { var srch = this.currentSearch; return this.currentLinkPath + (srch.length > 0 ? '?' + srch : ''); },
        basePathSize: function() { return this.basePath.split('/').length - this.appRootPath.split('/').length; },
        ScreenTitle: function() { return this.navMenuList.length > 0 ? this.navMenuList[this.navMenuList.length - 1].title : ""; },
        documentMenuList: function() {
            var docList = [];
            for (var i = 0; i < this.navMenuList.length; i++) {
                var screenDocList = this.navMenuList[i].screenDocList;
                if (screenDocList && screenDocList.length) { screenDocList.forEach(function(el) { docList.push(el);}); }
            }
            return docList;
        }
    },
    created: function() {
        this.moquiSessionToken = $("#confMoquiSessionToken").val();
        this.appHost = $("#confAppHost").val(); this.appRootPath = $("#confAppRootPath").val();
        this.basePath = $("#confBasePath").val(); this.linkBasePath = $("#confLinkBasePath").val();
        this.userId = $("#confUserId").val();
        this.locale = $("#confLocale").val(); if (moqui.localeMap[this.locale]) this.locale = moqui.localeMap[this.locale];
        this.leftOpen = $("#confLeftOpen").val() === 'true';

        var confDarkMode = $("#confDarkMode").val();
        this.$q.dark.set(confDarkMode === "true");

        this.notificationClient = new moqui.NotificationClient((location.protocol === 'https:' ? 'wss://' : 'ws://') + this.appHost + this.appRootPath + "/notws");

        var navPluginUrlList = [];
        $('.confNavPluginUrl').each(function(idx, el) { navPluginUrlList.push($(el).val()); });
        this.addNavPluginsWait(navPluginUrlList, 0);

        var accountPluginUrlList = [];
        $('.confAccountPluginUrl').each(function(idx, el) { accountPluginUrlList.push($(el).val()); });
        this.addAccountPluginsWait(accountPluginUrlList, 0);
    },
    mounted: function() {
        var jqEl = $(this.$el);
        jqEl.css("display", "initial");
        // load the current screen
        this.setUrl(window.location.pathname + window.location.search);
        // init the NotificationClient and register 'displayNotify' as the default listener
        this.notificationClient.registerListener("ALL");

        // request Notification permission on load if not already granted or denied
        if (window.Notification && Notification.permission !== "granted" && Notification.permission !== "denied") {
            Notification.requestPermission(function (status) {
                if (status === "granted") {
                    moqui.notifyMessages("Browser notifications enabled, if you don't want them use browser notification settings to block");
                } else if (status === "denied") {
                    moqui.notifyMessages("Browser notifications disabled, if you want them use browser notification settings to allow");
                }
            });
        }
    }

});
window.addEventListener('popstate', function() { moqui.webrootVue.setUrl(window.location.pathname + window.location.search); });

// NOTE: simulate vue-router so this.$router.resolve() works in a basic form; required for use of q-btn 'to' attribute along with router-link component defined above
moqui.webrootRouter = {
    resolve: function resolve(to, current, append) {
        var location = moqui.isString(to) ? moqui.parseHref(to) : to;

        var path = location.path;
        if (moqui.webrootVue) location.path = path = moqui.webrootVue.getLinkPath(path);

        var lslIdx = path.lastIndexOf("/");
        var name = lslIdx === -1 ? path : path.slice(lslIdx+1);

        var route = { name:name, meta:{}, path:path,
            hash:location.hash||"", query:location.query||"", params: {}, fullPath:path, matched:[] };
        return { location:location, route:route, href:moqui.makeHref(location), normalizedTo:location, resolved:route }
    },
    replace: function(location, onComplete, onAbort) { moqui.webrootVue.setUrl(location, null, onComplete); },
    push: function(location, onComplete, onAbort) { moqui.webrootVue.setUrl(location, null, onComplete); }
}
Object.defineProperty(Vue.prototype, '$router', {
    get: function get() { return moqui.webrootRouter; }
});
Object.defineProperty(Vue.prototype, '$route', {
    get: function get() { return moqui.webrootVue.getRoute(); }
});
