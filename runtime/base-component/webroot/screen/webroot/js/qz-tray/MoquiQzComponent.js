/* This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License. */
/*
You can get a key and certificate from QZ.io with a paid support agreement, or you can generate a single domain key for more restricted clients.

Generate Key and Cert:
$ openssl req -x509 -newkey rsa:2048 -keyout qz-private-key.pem -out digital-certificate.txt -days 11499 -nodes
- NOTE: make sure 'Common Name' is hostname like *.moqui.org ('localhost' works for local testing)

- add line to /opt/qz-tray/qz-tray.properties like: authcert.override=/opt/qz-tray/auth/digital-certificate.txt
- kill current process then restart or: java -Xms512m -jar /opt/qz-tray/qz-tray.jar

Add Files to Moqui:
- put qz-private-key.pem file on classpath (like runtime/classes or <component>/classes)
- add certificate hosted at path "/qz-tray/digital-certificate.txt" (in component use screen called qz-tray.xml and mount under webroot)
*/
if (window.qz && window.moqui && moqui.webrootVue) {
    console.info("Creating QZ component");
    moqui.webrootVue.qzVue = Vue.extend({
        template:
        '<span>' +
            '<button id="qz-print-modal-link" type="button" class="btn btn-sm navbar-btn navbar-right" :class="readyStyleBtn" data-toggle="modal" data-target="#qz-print-modal" title="Print Options"><i class="fa fa-print"></i></button>' +
            '<div class="modal fade" id="qz-print-modal" tabindex="-1" role="dialog" aria-labelledby="qz-print-modal-label">' +
                '<div class="modal-dialog" role="document"><div class="modal-content">' +
                    '<div class="modal-header">' +
                        '<button type="button" class="close" data-dismiss="modal" aria-label="Close"><span aria-hidden="true">&times;</span></button>' +
                        '<h4 class="modal-title" id="qz-print-modal-label">QZ Print Options</h4>' +
                    '</div>' +
                    '<div class="modal-body">' +
                        '<div id="qz-connection" class="row"><div class="col-xs-8">' +
                            '<h4 :class="connectionClass">Connection: {{connectionState}}<span v-if="qzVersion" class="text-muted"> ver {{qzVersion}}</span></h4>' +
                        '</div><div class="col-xs-4 text-right">' +
                            '<button v-if="connectionState !== \'Active\'" type="button" class="btn btn-success btn-sm" @click="startConnection()">Connect</button>' +
                            '<button v-if="connectionState === \'Active\'" type="button" class="btn btn-warning btn-sm" @click="endConnection()">Disconnect</button>' +
                            '<button v-if="connectionState === \'Inactive\' || connectionState === \'Error\'" type="button" class="btn btn-info btn-sm" @click="launchQZ()">Launch QZ</button>' +
                        '</div></div>' +
                        '<p v-if="connectionState !== \'Active\'">Don\'t have QZ Tray installed? <a href="https://qz.io/download/" target="_blank">Download from QZ.io</a></p>' +
                        '<div class="panel panel-default">' +
                            '<div class="panel-heading"><h5 class="panel-title">Printers: Main <span class="text-info">{{currentPrinter||"not set"}}</span> <button v-if="currentPrinter && currentPrinter.length" type="button" class="btn btn-default btn-sm" style="padding:0 4px 0 4px;margin:0;" @click="setPrinter(null)">&times;</button> ' +
                                    'Label <span class="text-info">{{currentLabelPrinter||"not set"}}</span> <button v-if="currentLabelPrinter && currentLabelPrinter.length" type="button" class="btn btn-default btn-sm" style="padding:0 4px 0 4px;margin:0;" @click="setLabelPrinter(null)">&times;</button></h5>' +
                                '<div class="panel-toolbar"><button type="button" class="btn btn-default btn-sm" @click="findPrinters()">Find</button></div></div>' +
                            '<div class="panel-body"><div class="row">' +
                                '<div v-for="printerName in printerList" class="col-xs-6">{{printerName}} ' +
                                    '<button v-if="currentPrinter !== printerName" type="button" class="btn btn-default btn-sm" @click="setPrinter(printerName)">Main</button>' +
                                    '<button v-if="currentPrinter === printerName" type="button" class="btn btn-primary btn-sm" @click="setPrinter(null)">Main</button>' +
                                    '<button v-if="currentLabelPrinter !== printerName" type="button" class="btn btn-default btn-sm" @click="setLabelPrinter(printerName)">Label</button>' +
                                    '<button v-if="currentLabelPrinter === printerName" type="button" class="btn btn-primary btn-sm" @click="setLabelPrinter(null)">Label</button>' +
                                '</div>' +
                            '</div>' +
                        '</div></div>' +
                        '<div class="row"><div class="col-xs-6">' +
                            '<h4>Main Options</h4>' +
                            '<div class="form-group form-inline"><label for="pxlOrientation">Orientation</label>' +
                                '<select id="pxlOrientation" class="form-control pull-right" v-model="mainOptions.orientation">' +
                                    '<option value="">Default</option><option value="portrait">Portrait</option>' +
                                    '<option value="landscape">Landscape</option><option value="reverse-landscape">Reverse LS</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="form-group form-inline"><label for="pxlColorType">Color Type</label>' +
                                '<select id="pxlColorType" class="form-control pull-right" v-model="mainOptions.colorType">' +
                                    '<option value="">Default</option><option value="color">Color</option>' +
                                    '<option value="grayscale">Grayscale</option><option value="blackwhite">Black & White</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="form-group form-inline"><label for="pxlScale">Scale Content</label>' +
                                '<input type="checkbox" id="pxlScale" class="pull-right" v-model="mainOptions.scaleContent"></div>' +
                            '<div class="form-group form-inline"><label for="pxlRasterize">Rasterize</label>' +
                                '<input type="checkbox" id="pxlRasterize" class="pull-right" v-model="mainOptions.rasterize"></div>' +
                        '</div><div class="col-xs-6">' +
                            '<h4>Label Options</h4>' +
                            '<div class="form-group form-inline"><label for="pxlOrientation">Orientation</label>' +
                                '<select id="pxlOrientation" class="form-control pull-right" v-model="labelOptions.orientation">' +
                                    '<option value="">Default</option><option value="portrait">Portrait</option>' +
                                    '<option value="landscape">Landscape</option><option value="reverse-landscape">Reverse LS</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="form-group form-inline"><label for="pxlColorType">Color Type</label>' +
                                '<select id="pxlColorType" class="form-control pull-right" v-model="labelOptions.colorType">' +
                                    '<option value="">Default</option><option value="color">Color</option>' +
                                    '<option value="grayscale">Grayscale</option><option value="blackwhite">Black & White</option>' +
                                '</select>' +
                            '</div>' +
                            '<div class="form-group form-inline"><label for="pxlScale">Scale Content</label>' +
                                '<input type="checkbox" id="pxlScale" class="pull-right" v-model="labelOptions.scaleContent"></div>' +
                            '<div class="form-group form-inline"><label for="pxlRasterize">Rasterize</label>' +
                                '<input type="checkbox" id="pxlRasterize" class="pull-right" v-model="labelOptions.rasterize"></div>' +
                        '</div></div>' +
                    '</div>' +
                '</div></div>' +
            '</div>' +
        '</span>',
        data: function() { return { connectionState:'Inactive', connectionClass:'text-muted', qzVersion:null,
                currentPrinter:null, currentLabelPrinter:null, printerList:[],
                mainOptions:{ orientation:"", colorType:"color", scaleContent:true, rasterize:false },
                labelOptions:{ orientation:"", colorType:"blackwhite", scaleContent:false, rasterize:false }} },
        methods: {
            findPrinters: function() {
                var vm = this;
                qz.printers.find().then(function(data) {
                    if (moqui.isArray(data)) {
                        var labelKeywords = ["label", "zebra", "sato", "dymo"];
                        vm.printerList = data;
                        for (var i = 0; i < data.length; i++) {
                            var printerName = data[i]; var pnLower = printerName.toLowerCase(); var hasLabelKw = false;
                            for (var j = 0; j < labelKeywords.length; j++) { if (pnLower.indexOf(labelKeywords[j]) !== -1) { hasLabelKw = true; break; } }
                            if (!hasLabelKw && !vm.currentPrinter) vm.setPrinter(printerName);
                            if (hasLabelKw && !vm.currentLabelPrinter) vm.setLabelPrinter(printerName);
                        }
                    }
                }).catch(moqui.showQzError);
            },
            setPrinter: function(printerName) {
                this.currentPrinter = printerName;
                $.ajax({ type:'POST', url:(this.$root.appRootPath + '/apps/setPreference'), error:moqui.handleAjaxError,
                    data:{ moquiSessionToken:this.$root.moquiSessionToken, preferenceKey:'qz.printer.main.active', preferenceValue:printerName } });
            },
            setLabelPrinter: function(printerName) {
                this.currentLabelPrinter = printerName;
                $.ajax({ type:'POST', url:(this.$root.appRootPath + '/apps/setPreference'), error:moqui.handleAjaxError,
                    data:{ moquiSessionToken:this.$root.moquiSessionToken, preferenceKey:'qz.printer.label.active', preferenceValue:printerName } });
            },
            handleConnectionError: function(err) {
                this.connectionState = "Error"; this.connectionClass = 'text-danger';
                if (err.target !== undefined) {
                    if (err.target.readyState >= 2) { //if CLOSING or CLOSED
                        moqui.notifyGrowl({type:"info", title:"Connection to QZ Tray was closed"});
                    } else {
                        moqui.notifyGrowl({type:"danger", title:"A QZ Tray connection error occurred, check log for details"});
                        console.error(err);
                    }
                } else {
                    moqui.notifyGrowl({type:"danger", title:err});
                }
            },
            launchQZ: function () {
                if (!qz.websocket.isActive()) {
                    window.location.assign("qz:launch");
                    this.startConnection();
                }
            },
            startConnection: function () {
                if (!qz.websocket.isActive()) {
                    var vm = this;
                    this.connectionState = "Connecting..."; this.connectionClass = 'text-info';
                    // Retry 5 times, pausing 1 second between each attempt
                    qz.websocket.connect({ retries: 5, delay: 1 }).then(function() {
                        vm.connectionState = "Active"; vm.connectionClass = 'text-success';
                        qz.api.getVersion().then(function(data) { vm.qzVersion = data; }).catch(moqui.showQzError);
                    }).catch(this.handleConnectionError);
                } else {
                    this.connectionState = "Active"; this.connectionClass = 'text-success';
                }
            },
            endConnection: function () {
                if (qz.websocket.isActive()) {
                    var vm = this;
                    qz.websocket.disconnect().then(function() {
                        vm.connectionState = "Inactive"; vm.connectionClass = 'text-muted';
                        vm.qzVersion = null;
                    }).catch(this.handleConnectionError);
                } else {
                    this.connectionState = "Inactive"; this.connectionClass = 'text-muted';
                }
            }
        },
        computed: {
            isMainReady: function () { return this.connectionState === 'Active' && this.currentPrinter; },
            isLabelReady: function () { return this.connectionState === 'Active' && this.currentLabelPrinter; },
            readyStyleBtn: function () { return this.connectionState === 'Active' ? (this.currentPrinter ? (this.currentLabelPrinter ? "btn-success" : "btn-primary") : (this.currentLabelPrinter ? "btn-info" : "btn-warning")) : "btn-danger"; }
        },
        mounted: function() {
            var vm = this;

            $('#qz-print-modal-link').tooltip({ placement:'bottom', trigger:'hover' });
            // move modal to just under body to avoid issues with header CSS/etc
            $('#qz-print-modal').appendTo("body");
            this.startConnection();

            qz.security.setCertificatePromise(function(resolve, reject) {
                $.ajax(vm.$root.appRootPath + "/qz-tray/digital-certificate.txt").then(resolve, reject);
                //Alternate method: resolve(); OR resolve("---...---");
            });
            qz.security.setSignaturePromise(function(toSign) { return function(resolve, reject) {
                // NOTE: using inline function that calls resolve() instead of using reject method, otherwise won't even show user warning and allow print/etc
                $.ajax(vm.$root.appRootPath + '/apps/qzSign?message=' + toSign).then(resolve, function() { resolve(); });
                // Alternate method - unsigned: resolve();
            }; });

            // AJAX call get preferences: qz.printer.main.active, qz.printer.label.active
            $.ajax({ type:'GET', url:(this.$root.appRootPath + '/apps/getPreferences'), error:moqui.handleAjaxError,
                data:{ keyRegexp:'qz\\.print.*' }, dataType:"json", success: function(prefMap) {
                    var mainPrinter = prefMap["qz.printer.main.active"];
                    if (mainPrinter && mainPrinter.length) vm.currentPrinter = mainPrinter;
                    var labelPrinter = prefMap["qz.printer.label.active"];
                    if (labelPrinter && labelPrinter.length) vm.currentLabelPrinter = labelPrinter;
                } });
        }
    });

    moqui.isQzActive = function() { return qz.websocket.isActive(); };
    // NOTE useful options: jobName, copies, scaleContent, rasterize, rotation, etc; see https://qz.io/wiki/2.0-Pixel-Printing#advanced-printing
    moqui.getQzConfig = function(printer, userOptions, jobOptions) {
        var cfgOptions = { copies:1, jobName:null };
        if (moqui.isPlainObject(userOptions)) cfgOptions = $.extend(cfgOptions, userOptions);
        if (moqui.isPlainObject(jobOptions)) cfgOptions = $.extend(cfgOptions, jobOptions);
        // console.log("Combined options " + JSON.stringify(cfgOptions));

        var cfg = qz.configs.create(printer, cfgOptions);
        cfg.reconfigure(cfgOptions);
        console.log("QZ Config: " + JSON.stringify(cfg));
        return cfg;
    };
    moqui.getQzMainConfig = function(options) { return moqui.getQzConfig(moqui.webrootVue.$refs.qzVue.currentPrinter, moqui.webrootVue.$refs.qzVue.mainOptions, options); };
    moqui.getQzLabelConfig = function(options) { return moqui.getQzConfig(moqui.webrootVue.$refs.qzVue.currentLabelPrinter, moqui.webrootVue.$refs.qzVue.labelOptions, options); };
    moqui.showQzError = function(error) { console.log(error); moqui.notifyGrowl({type:"danger", title:error}); };

    moqui.printUrlFile = function(config, url, type) {
        if (!url || !url.length) { console.warn("Called printUrlFile with no url array or string, printing nothing"); return; }
        if (!type || !type.length) type = "pdf";
        console.log("printing type " + type + " URL " + url);
        console.log(config);
        if (!moqui.isQzActive()) {
            moqui.notifyGrowl({type:"warning", title:"Cannot print, QZ is not active"});
            console.warn("Tried to print type " + type + " at URL " + url + " but QZ is not active");
            return;
        }

        var urlArray = moqui.isArray(url) ? url : [url];
        moqui.internalChainPrintFile(config, type, urlArray, 0);
    };
    moqui.internalChainPrintFile = function(config, type, urlArray, urlIndex) {
        if (!urlIndex) urlIndex = 0;
        if (urlIndex >= urlArray.length) return;
        var curUrl = urlArray[urlIndex];
        // pre-fetch the file, if we let QZ Tray do it the request won't be in the same session so there are authc/authz issues
        var oReq = new XMLHttpRequest();
        oReq.open("GET", curUrl, true);
        oReq.responseType = "blob";
        oReq.onload = function(oEvent) {
            var blob = oReq.response;
            var reader = new FileReader();
            reader.onloadend = function() {
                var base64data = reader.result;
                var base64Only = base64data.substr(base64data.indexOf(',')+1);
                var printDataObj = { type:type, format:"base64", data:base64Only };
                qz.print(config, [printDataObj]).catch(moqui.showQzError).then(function () {
                    var nextIndex = urlIndex + 1;
                    if (nextIndex < urlArray.length) { moqui.internalChainPrintFile(config, type, urlArray, nextIndex); }
                });
            };
            reader.readAsDataURL(blob);
        };
        oReq.send();
    };
    moqui.printUrlMain = function(url, type) { moqui.printUrlFile(moqui.getQzMainConfig(), url, type); };
    moqui.printUrlLabel = function(url, type) { moqui.printUrlFile(moqui.getQzLabelConfig(), url, type); };
} else {
    console.info("Not creating QZ component, qz: " + window.qz + ", moqui: " + window.moqui)
}
