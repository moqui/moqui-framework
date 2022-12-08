/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.util;

import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.ValidatingConnectionPool;
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.*;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("unused")
public class RestClient {
    public enum Method { GET, PATCH, PUT, POST, DELETE, OPTIONS, HEAD }
    public static final Method GET = Method.GET, PATCH = Method.PATCH, PUT = Method.PUT, POST = Method.POST,
            DELETE = Method.DELETE, OPTIONS = Method.OPTIONS, HEAD = Method.HEAD;
    public static final String[] METHOD_ARRAY = { "GET", "PATCH", "PUT", "POST", "DELETE", "OPTIONS", "HEAD" };
    public static final Set<String> METHOD_SET = new HashSet<>(Arrays.asList(METHOD_ARRAY));

    // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
    public static final int TOO_MANY = 429;

    // NOTE: DELETE doesn't normally support a body, but some APIs use it
    private static final EnumSet<Method> BODY_METHODS = EnumSet.of(Method.GET, Method.PATCH, Method.POST, Method.PUT, Method.DELETE);
    private static final Logger logger = LoggerFactory.getLogger(RestClient.class);

    // Default RequestFactory (avoid new per request)
    private static final ReentrantLock defaultReqFacLock = new ReentrantLock();
    private static RequestFactory defaultRequestFactoryInternal = null;
    public static RequestFactory getDefaultRequestFactory() {
        if (defaultRequestFactoryInternal != null) return defaultRequestFactoryInternal;
        defaultReqFacLock.lock();
        try { defaultRequestFactoryInternal = new SimpleRequestFactory(); return defaultRequestFactoryInternal; }
        finally { defaultReqFacLock.unlock(); }
    }
    // TODO: consider creating a RequestFactory in ECFI, init and destroy along with ECFI
    public static void setDefaultRequestFactory(RequestFactory newRequestFactory) {
        defaultReqFacLock.lock();
        try {
            RequestFactory tempRf = defaultRequestFactoryInternal;
            defaultRequestFactoryInternal = newRequestFactory;
            if (tempRf != null) tempRf.destroy();
        } finally { defaultReqFacLock.unlock(); }
    }
    public static void destroyDefaultRequestFactory() {
        defaultReqFacLock.lock();
        try { if (defaultRequestFactoryInternal != null) { defaultRequestFactoryInternal.destroy(); defaultRequestFactoryInternal = null; } }
        finally { defaultReqFacLock.unlock(); }
    }

    // ========== Instance Fields ==========
    private String uriString = null;
    private Method method = Method.GET;
    private String contentType = "application/json";
    private String acceptContentType = null;
    private Charset charset = StandardCharsets.UTF_8;
    private String bodyText = null;
    private MultiPartRequestContent multiPart = null;
    private List<KeyValueString> headerList = new LinkedList<>();
    private List<KeyValueString> bodyParameterList = new LinkedList<>();
    private String username = null;
    private String password = null;
    private float initialWaitSeconds = 2.0F;
    private int maxRetries = 0;
    private int maxResponseSize = 4 * 1024 * 1024;
    private int timeoutSeconds = 30;
    private boolean timeoutRetry = false;
    private RequestFactory overrideRequestFactory = null;
    private boolean isolate = false;

    public RestClient() { }

    /** Full URL String including protocol, host, path, parameters, etc */
    public RestClient uri(String location) { uriString = location; return this; }
    /** URL object including protocol, host, path, parameters, etc */
    public RestClient uri(URI uri) { this.uriString = uri.toASCIIString(); return this; }
    public UriBuilder uri() { return new UriBuilder(this); }
    public URI getUri() { return URI.create(uriString); }
    public String getUriString() { return uriString; }

    /** Sets the HTTP request method, defaults to 'GET'; must be in the METHODS array */
    public RestClient method(String method) {
        if (method == null || method.isEmpty()) {
            this.method = Method.GET;
            return this;
        }
        method = method.toUpperCase();
        try {
            this.method = Method.valueOf(method);
        } catch (Exception e) {
            throw new IllegalArgumentException("Method " + method + " not valid");
        }
        return this;
    }
    public RestClient method(Method method) { this.method = method; return this; }
    public Method getMethod() { return method; }

    /** Defaults to 'application/json', could also be 'text/xml', etc */
    public RestClient contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    public RestClient acceptContentType(String acceptContentType) {
        this.acceptContentType = acceptContentType;
        return this;
    }

    /** The MIME character encoding for the body sent and response. Defaults to <code>UTF-8</code>. Must be a valid
     * charset in the java.nio.charset.Charset class. */
    public RestClient encoding(String characterEncoding) { this.charset = Charset.forName(characterEncoding); return this; }
    public RestClient addHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet())
            headerList.add(new KeyValueString(entry.getKey(), entry.getValue()));
        return this;
    }

    public RestClient addHeader(String name, String value) {
        headerList.add(new KeyValueString(name, value));
        return this;
    }

    public RestClient basicAuth(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /** Set the body text to use */
    public RestClient text(String bodyText) {
        if (!BODY_METHODS.contains(method)) throw new IllegalStateException("Cannot use body text with method " + method);
        this.bodyText = bodyText;
        return this;
    }

    /** Set the body text as JSON from an Object */
    public RestClient jsonObject(Object bodyJsonObject) {
        if (bodyJsonObject == null) {
            bodyText = null;
            return this;
        }
        if (bodyJsonObject instanceof CharSequence) {
            return text(bodyJsonObject.toString());
        }

        JsonBuilder jb = new JsonBuilder();
        if (bodyJsonObject instanceof Map) {
            jb.call((Map) bodyJsonObject);
        } else if (bodyJsonObject instanceof List) {
            jb.call((List) bodyJsonObject);
        } else {
            jb.call((Object) bodyJsonObject);
        }

        return text(jb.toString());
    }

    /** Set the body text as XML from a MNode */
    public RestClient xmlNode(MNode bodyXmlNode) {
        if (bodyXmlNode == null) {
            bodyText = null;
            return this;
        }

        return text(bodyXmlNode.toString());
    }

    public String getBodyText() { return bodyText; }

    /** Add fields to put in body form parameters */
    public RestClient addBodyParameters(Map<String, String> formFields) {
        for (Map.Entry<String, String> entry : formFields.entrySet())
            bodyParameterList.add(new KeyValueString(entry.getKey(), entry.getValue()));
        return this;
    }
    /** Add a field to put in body form parameters */
    public RestClient addBodyParameter(String name, String value) {
        bodyParameterList.add(new KeyValueString(name, value));
        return this;
    }
    /** Add a field part to a multi part request **/
    public RestClient addFieldPart(String field, String value) {
        if (method != Method.POST) throw new IllegalStateException("Can only use multipart body with POST method, not supported for method " + method + "; if you need a different effective request method try using the X-HTTP-Method-Override header");

        if (multiPart == null) multiPart = new MultiPartRequestContent();
        multiPart.addFieldPart(field, new StringRequestContent(value), null);
        return this;
    }
    /** Add a String file part to a multi part request **/
    public RestClient addFilePart(String name, String fileName, String stringContent) {
        return addFilePart(name, fileName, new StringRequestContent(stringContent), null);
    }
    /** Add a InputStream file part to a multi part request **/
    public RestClient addFilePart(String name, String fileName, InputStream streamContent) {
        return addFilePart(name, fileName, new InputStreamRequestContent(streamContent), null);
    }
    /** Add file part using Jetty ContentProvider.
     * WARNING: This uses Jetty HTTP Client API objects and may change over time, do not use if alternative will work.
     */
    public RestClient addFilePart(String name, String fileName, Request.Content content, HttpFields fields) {
        if (method != Method.POST) throw new IllegalStateException("Can only use multipart body with POST method, not supported for method " + method + "; if you need a different effective request method try using the X-HTTP-Method-Override header");
        if (multiPart == null) multiPart = new MultiPartRequestContent();
        multiPart.addFilePart(name, fileName, content, fields);
        return this;
    }

    /** If a velocity limit (429) response is received then retry up to maxRetries with
     * exponential back off (initialWaitSeconds^i) sleep time in between requests. */
    public RestClient retry(float initialWaitSeconds, int maxRetries) {
        this.initialWaitSeconds = initialWaitSeconds;
        this.maxRetries = maxRetries;
        return this;
    }
    /** Same as retry(int, int) with defaults of 2 for initialWaitSeconds and 5 for maxRetries
     * (2, 4, 8, 16, 32 seconds; up to total 62 seconds wait time and 6 HTTP requests) */
    public RestClient retry() { return retry(2.0F, 5); }

    /** Set a maximum response size, defaults to 4MB (4 * 1024 * 1024) */
    public RestClient maxResponseSize(int maxSize) { this.maxResponseSize = maxSize; return this; }
    /** Set a full response timeout in seconds, defaults to 30 */
    public RestClient timeout(int seconds) { this.timeoutSeconds = seconds; return this; }
    /** Set to true if retry should also be done on timeout; must call retry() to set retry parameters otherwise defaults to 1 retry with 2.0 initial wait time. */
    public RestClient timeoutRetry(boolean tr) { this.timeoutRetry = tr; if (maxRetries == 0) maxRetries = 1; return this; }

    /** Use a specific RequestFactory for pooling, keep alive, etc */
    public RestClient withRequestFactory(RequestFactory requestFactory) { overrideRequestFactory = requestFactory; return this; }
    /** If true isolate the request from all other requests by using a new HttpClient instance per request (no cookies, keep alive, etc; each request isolated from others) */
    public RestClient isolate(boolean isolate) { this.isolate = isolate; return this; }

    /** Do the HTTP request and get the response */
    public RestResponse call() {
        float curWaitSeconds = initialWaitSeconds;
        if (curWaitSeconds == 0) curWaitSeconds = 1;

        RestResponse curResponse = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                // do the request
                curResponse = callInternal();
            } catch (TimeoutException e) {
                // if set to do so retry on timeout
                if (timeoutRetry && i < maxRetries) {
                    try {
                        Thread.sleep(Math.round(curWaitSeconds * 1000));
                    } catch (InterruptedException ie) {
                        logger.warn("RestClient timeout retry sleep interrupted, returning most recent response", ie);
                        return curResponse;
                    }
                    curWaitSeconds = curWaitSeconds * initialWaitSeconds;
                    continue;
                } else {
                    throw new BaseException("Timeout error calling REST request", e);
                }
            }
            if (curResponse.statusCode == TOO_MANY && i < maxRetries) {
                try {
                    Thread.sleep(Math.round(curWaitSeconds * 1000));
                } catch (InterruptedException e) {
                    logger.warn("RestClient velocity retry sleep interrupted, returning most recent response", e);
                    return curResponse;
                }
                curWaitSeconds = curWaitSeconds * initialWaitSeconds;
            } else {
                break;
            }
        }

        return curResponse;
    }
    protected RestResponse callInternal() throws TimeoutException {
        if (uriString == null || uriString.isEmpty()) throw new IllegalStateException("No URI set in RestClient");
        RequestFactory tempFactory = this.isolate ? new SimpleRequestFactory() : null;
        try {
            Request request = makeRequest(tempFactory != null ? tempFactory : (overrideRequestFactory != null ? overrideRequestFactory : getDefaultRequestFactory()));
            if (timeoutSeconds < 2) timeoutSeconds = 2;
            request.idleTimeout(timeoutSeconds > 30 ? 30 : timeoutSeconds-1, TimeUnit.SECONDS);
            // use a FutureResponseListener so we can set the timeout and max response size (old: response = request.send(); )
            FutureResponseListener listener = new FutureResponseListener(request, maxResponseSize);
            try {
                request.send(listener);
                ContentResponse response = listener.get(timeoutSeconds, TimeUnit.SECONDS);
                return new RestResponse(this, response);
            } catch (TimeoutException e) {
                logger.warn("RestClient request timed out after " + timeoutSeconds + "s to " + request.getURI());
                // cancel listener, just in case
                listener.cancel(true);
                // abort request to make sure it gets closed and cleaned up
                request.abort(e);
                throw e;
            }
        } catch (Exception e) {
            throw new BaseException("Error calling HTTP request to " + uriString, e);
        } finally {
            if (tempFactory != null) tempFactory.destroy();
        }
    }

    protected Request makeRequest(RequestFactory requestFactory) {
        final Request request = requestFactory.makeRequest(uriString);
        request.method(method.name());
        // set charset on request?

        // add headers and parameters
        for (KeyValueString nvp : headerList) request.header(nvp.key, nvp.value);
        for (KeyValueString nvp : bodyParameterList) request.param(nvp.key, nvp.value);
        // authc
        if (username != null && !username.isEmpty()) {
            String unPwString = username + ':' + password;
            String basicAuthStr  = "Basic " + Base64.getEncoder().encodeToString(unPwString.getBytes());
            request.header(HttpHeader.AUTHORIZATION, basicAuthStr);
            // using basic Authorization header instead, too many issues with this: httpClient.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, BasicAuthentication.ANY_REALM, username, password));
        }

        if (multiPart != null) {
            multiPart.close();
            if (method == Method.POST) {
                // HttpClient will send the correct headers when it's a multi-part content type (ie set content type to multipart/form-data, etc)
                request.body(multiPart);
            } else {
                throw new IllegalStateException("Can only use multipart body with POST method, not supported for method " + method + "; if you need a different effective request method try using the X-HTTP-Method-Override header");
            }
        } else if (bodyText != null && !bodyText.isEmpty()) {
            request.body(new StringRequestContent(contentType, bodyText, charset));
            // not needed, set by call to request.content() with passed contentType: request.header(HttpHeader.CONTENT_TYPE, contentType);
        }

        request.accept(acceptContentType != null && !acceptContentType.isEmpty() ? acceptContentType : contentType);

        if (logger.isTraceEnabled())
            logger.trace("RestClient request " + request.getMethod() + " " + request.getURI() + " Headers: " + request.getHeaders());

        return request;
    }

    /** Call in background  */
    public Future<RestResponse> callFuture() {
        if (uriString == null || uriString.isEmpty()) throw new IllegalStateException("No URI set in RestClient");
        return new RestClientFuture(this);
    }

    public static class RestResponse {
        private RestClient rci;
        protected ContentResponse response;
        protected byte[] bytes = null;
        private Map<String, ArrayList<String>> headers = new LinkedHashMap<>();
        private int statusCode;
        private String reasonPhrase, contentType, encoding;

        RestResponse(RestClient rci, ContentResponse response) {
            this.rci = rci;
            this.response = response;
            statusCode = response.getStatus();
            reasonPhrase = response.getReason();
            contentType = response.getMediaType();
            encoding = response.getEncoding();
            if (encoding == null || encoding.isEmpty()) encoding = "UTF-8";

            // get headers
            for (HttpField hdr : response.getHeaders()) {
                String name = hdr.getName();
                ArrayList<String> curList = headers.get(name);
                if (curList == null) {
                    curList = new ArrayList<>();
                    headers.put(name, curList);
                }
                curList.addAll(Arrays.asList(hdr.getValues()));
            }

            // get the response body
            bytes = response.getContent();
        }

        /** If status code is not in the 200 range throw an exception with details; call this first for easy error
         * handling or skip it to handle manually or allow errors */
        public RestResponse checkError() {
            if (statusCode < 200 || statusCode >= 300) {
                logger.info("Error " + statusCode + " (" + reasonPhrase + ") in response to " + rci.method + " to " + rci.uriString + ", response text:\n" + text());
                throw new HttpResponseException("Error " + statusCode + " (" + reasonPhrase + ") in response to " + rci.method + " to " + rci.uriString, response);
            }

            return this;
        }

        public RestClient getClient() { return rci; }

        public int getStatusCode() { return statusCode; }
        public String getReasonPhrase() { return reasonPhrase; }
        public String getContentType() { return contentType; }
        public String getEncoding() { return encoding; }

        /** Get the plain text of the response */
        public String text() {
            try {
                if ("UTF-8".equals(encoding)) {
                    return toStringCleanBom(bytes);
                } else {
                    return new String(bytes, encoding);
                }
            } catch (UnsupportedEncodingException e) {
                throw new BaseException("Error decoding REST response", e);
            }
        }

        /** Parse the response as JSON and return an Object */
        public Object jsonObject() {
            try {
                return new JsonSlurper().parseText(text());
            } catch (Throwable t) {
                throw new BaseException("Error parsing JSON response from request to " + rci.uriString, t);
            }
        }
        /** Parse the response as XML and return a MNode */
        public MNode xmlNode() { return MNode.parseText(rci.uriString, text()); }

        /** Get bytes from a binary response */
        public byte[] bytes() { return bytes; }
        // FUTURE: handle stream response, but in a way that avoids requiring an explicit close for other methods

        public Map<String, ArrayList<String>> headers() { return headers; }
        public String headerFirst(String name) {
            List<String> valueList = headers.get(name);
            return valueList != null && valueList.size() > 0 ? valueList.get(0) : null;
        }

        static String toStringCleanBom(byte[] bytes) throws UnsupportedEncodingException {
            // NOTE: this only supports UTF-8 for now!
            if (bytes == null || bytes.length == 0) return "";
            // UTF-8 BOM = 239, 187, 191
            if (bytes[0] == (byte) 239) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            } else {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    public static class UriBuilder {
        private RestClient rci;
        private String protocol = "http";
        private String host = null;
        private int port = 80;
        private StringBuilder path = new StringBuilder();
        private Map<String, String> parameters = null;
        private String fragment = null;

        UriBuilder(RestClient rci) { this.rci = rci; }

        public UriBuilder protocol(String protocol) {
            if (protocol == null || protocol.isEmpty()) throw new IllegalArgumentException("Empty protocol not allowed");
            this.protocol = protocol;
            return this;
        }

        public UriBuilder host(String host) {
            if (host == null || host.isEmpty()) throw new IllegalArgumentException("Empty host not allowed");
            this.host = host;
            return this;
        }

        public UriBuilder port(int port) {
            if (port <= 0) throw new IllegalArgumentException("Invalid port " + port);
            this.port = port;
            return this;
        }

        public UriBuilder path(String pathEl) {
            if (pathEl == null || pathEl.isEmpty()) return this;
            if (!pathEl.startsWith("/")) path.append("/");
            path.append(pathEl);
            int lastIndex = path.length() - 1;
            if ('/' == path.charAt(lastIndex)) path.deleteCharAt(lastIndex);
            return this;
        }

        public UriBuilder parameter(String name, String value) {
            if (parameters == null) parameters = new LinkedHashMap<>();
            parameters.put(name, value);
            return this;
        }

        public UriBuilder parameters(Map<String, String> parms) {
            if (parms == null) return this;
            if (parameters == null) {
                parameters = new LinkedHashMap<>(parms);
            } else {
                parameters.putAll(parms);
            }

            return this;
        }

        public UriBuilder fragment(String fragment) {
            this.fragment = fragment;
            return this;
        }

        public RestClient build() throws URISyntaxException, UnsupportedEncodingException {
            if (host == null || host.isEmpty())
                throw new IllegalArgumentException("No host specified, call the host() method before build()");

            StringBuilder uriSb = new StringBuilder();
            uriSb.append(protocol).append("://").append(host).append(':').append(port);

            if (path.length() == 0) path.append("/");
            uriSb.append(path);

            String query = parametersMapToString(parameters);
            if (query != null && query.length() > 0) uriSb.append('?').append(query);

            return rci.uri(uriSb.toString());
        }
    }

    public static String parametersMapToString(Map<String, ?> parameters) throws UnsupportedEncodingException {
        if (parameters == null || parameters.size() == 0) return null;
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, ?> parm : parameters.entrySet()) {
            if (query.length() > 0) query.append("&");
            Object valueObj = parm.getValue();
            if (valueObj == null) continue;
            String valueStr = ObjectUtilities.toPlainString(valueObj);
            query.append(URLEncoder.encode(parm.getKey(), "UTF-8"))
                    .append("=").append(URLEncoder.encode(valueStr, "UTF-8"));
        }
        return query.toString();
    }

    private static class KeyValueString {
        KeyValueString(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String key;
        public String value;
    }

    public static class RetryListener implements Response.CompleteListener {
        RestClientFuture rcf;
        RetryListener(RestClientFuture rcf) { this.rcf = rcf; }
        @Override public void onComplete(Result result) {
            if (result.getResponse().getStatus() == TOO_MANY && rcf.retryCount < rcf.rci.maxRetries && !rcf.cancelled) {
                // lock before new request to make sure not in the middle of get()
                rcf.retryLock.lock();
                try {
                    try {
                        Thread.sleep(Math.round(rcf.curWaitSeconds * 1000));
                    } catch (InterruptedException e) {
                        logger.warn("RestClientFuture retry sleep interrupted, returning most recent response", e);
                        return;
                    }
                    // update wait time and count
                    rcf.curWaitSeconds = rcf.curWaitSeconds * rcf.rci.initialWaitSeconds;
                    rcf.retryCount++;

                    // do a new request, still in the background
                    rcf.newRequest();
                } finally { rcf.retryLock.unlock(); }
            }
        }
    }
    public static class RestClientFuture implements Future<RestResponse> {
        RestClient rci;
        RequestFactory tempRequestFactory = null;
        FutureResponseListener listener;
        volatile float curWaitSeconds;
        volatile int retryCount = 0;
        volatile boolean cancelled = false;
        ReentrantLock retryLock = new ReentrantLock();
        ContentResponse lastResponse = null;

        RestClientFuture(RestClient rci) {
            this.rci = rci;
            curWaitSeconds = rci.initialWaitSeconds;
            if (curWaitSeconds == 0) curWaitSeconds = 1;
            // start the initial request
            newRequest();
        }

        void newRequest() {
            if (tempRequestFactory != null) tempRequestFactory.destroy();
            tempRequestFactory = rci.isolate ? new SimpleRequestFactory() : null;

            // NOTE: RestClientFuture methods call httpClient.stop() so not handled here
            try {
                Request request = rci.makeRequest(tempRequestFactory != null ? tempRequestFactory :
                        (rci.overrideRequestFactory != null ? rci.overrideRequestFactory : getDefaultRequestFactory()));
                // use a CompleteListener to retry in background
                request.onComplete(new RetryListener(this));
                // use a FutureResponseListener so we can set the timeout and max response size (old: response = request.send(); )
                listener = new FutureResponseListener(request, rci.maxResponseSize);
                request.send(listener);
            } catch (Exception e) {
                throw new BaseException("Error calling REST request to " + rci.uriString, e);
            }
        }

        @Override public boolean isCancelled() { return cancelled || listener.isCancelled(); }
        @Override public boolean isDone() { return retryCount >= rci.maxRetries && listener.isDone(); }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            retryLock.lock();
            try {
                try {
                    cancelled = true;
                    return listener.cancel(mayInterruptIfRunning);
                } finally {
                    if (tempRequestFactory != null) {
                        tempRequestFactory.destroy();
                        tempRequestFactory = null;
                    }
                }
            } finally { retryLock.unlock(); }
        }

        @Override
        public RestResponse get() throws InterruptedException, ExecutionException {
            try {
                return get(rci.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new BaseException("Timeout error calling REST request", e);
            }
        }

        @Override
        public RestResponse get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            do {
                // lock before new request to make sure not in the middle of retry
                retryLock.lock();
                try {
                    try {
                        lastResponse = listener.get(timeout, unit);
                        if (lastResponse.getStatus() != TOO_MANY) break;
                    } finally {
                        if (tempRequestFactory != null) {
                            tempRequestFactory.destroy();
                            tempRequestFactory = null;
                        }
                    }
                } finally { retryLock.unlock(); }
            } while (!cancelled && retryCount < rci.maxRetries);

            return new RestResponse(rci, lastResponse);
        }
    }

    public interface RequestFactory {
        Request makeRequest(String uriString);
        void destroy();
    }
    /** The default RequestFactory, uses mostly Jetty HttpClient defaults and retains HttpClient instance between requests. */
    public static class SimpleRequestFactory implements RequestFactory {
        private final HttpClient httpClient;

        public SimpleRequestFactory() {
            this(true, false);
        }

        public SimpleRequestFactory(boolean trustAll, boolean disableCookieManagement) {
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
            ClientConnector clientConnector = new ClientConnector();
            clientConnector.setSslContextFactory(sslContextFactory);
            httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));

            if (disableCookieManagement) httpClient.setCookieStore(new HttpCookieStore.Empty());
            // use a default idle timeout of 15 seconds, should be lower than server idle timeouts which will vary by server but 30 seconds seems to be common
            httpClient.setIdleTimeout(15000);
            try { httpClient.start(); } catch (Exception e) { throw new BaseException("Error starting HTTP client", e); }
        }

        @Override public Request makeRequest(String uriString) {
            return httpClient.newRequest(uriString);
        }

        HttpClient getHttpClient() { return httpClient; }

        @Override public void destroy() {
            if (httpClient != null && httpClient.isRunning()) {
                try { httpClient.stop(); }
                catch (Exception e) { logger.error("Error stopping SimpleRequestFactory HttpClient", e); }
            }
        }
        @Override protected void finalize() throws Throwable {
            if (httpClient != null && httpClient.isRunning()) {
                logger.warn("SimpleRequestFactory finalize and httpClient still running, stopping");
                try { httpClient.stop(); } catch (Exception e) { logger.error("Error stopping SimpleRequestFactory HttpClient", e); }
            }
            super.finalize();
        }
    }
    /** RequestFactory with explicit pooling parameters and options specific to the Jetty HttpClient */
    public static class PooledRequestFactory implements RequestFactory {
        private HttpClient httpClient;
        private final String shortName;
        private int poolSize = 64;
        private int queueSize = 1024;
        private long validationTimeoutMillis = 1000;

        private SslContextFactory.Client sslContextFactory = null;
        private HttpClientTransport transport = null;
        private QueuedThreadPool executor = null;
        private Scheduler scheduler = null;

        /** The required shortName is used as a prefix for thread names and should be distinct. */
        public PooledRequestFactory(String shortName) { this.shortName = shortName; }

        /** Note that if a transport is specified it must include the SslContextFactory.Client so this is ignored. */
        public PooledRequestFactory with(SslContextFactory.Client sslcf) { sslContextFactory = sslcf; return this; }
        public PooledRequestFactory with(HttpClientTransport transport) { this.transport = transport; return this; }
        public PooledRequestFactory with(QueuedThreadPool executor) { this.executor = executor; return this; }
        public PooledRequestFactory with(Scheduler scheduler) { this.scheduler = scheduler; return this; }

        /** Size of the HTTP connection pool per destination (scheme + host + port) */
        public PooledRequestFactory poolSize(int size) { poolSize = size; return this; }
        /** Size of the HTTP request queue per destination (scheme + host + port) */
        public PooledRequestFactory queueSize(int size) { queueSize = size; return this; }
        /** Quarantine timeout for connection validation, see ValidatingConnectionPool javadoc for details */
        public PooledRequestFactory validationTimeout(long millis) { validationTimeoutMillis = millis; return this; }

        public PooledRequestFactory init() {
            if (transport == null) {
                if (sslContextFactory == null) {
                    sslContextFactory = new SslContextFactory.Client(true);
                    sslContextFactory.setEndpointIdentificationAlgorithm(null);
                }
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSslContextFactory(sslContextFactory);
                transport = new HttpClientTransportDynamic(clientConnector);
            }

            if (executor == null) { executor = new QueuedThreadPool(); executor.setName(shortName + "-queue"); }
            if (scheduler == null) scheduler = new ScheduledExecutorScheduler(shortName + "-scheduler", false);

            transport.setConnectionPoolFactory(destination -> new ValidatingConnectionPool(destination,
                    destination.getHttpClient().getMaxConnectionsPerDestination(), destination,
                    destination.getHttpClient().getScheduler(), validationTimeoutMillis));

            httpClient = new HttpClient(transport);
            httpClient.setExecutor(executor);
            httpClient.setScheduler(scheduler);
            httpClient.setMaxConnectionsPerDestination(poolSize);
            httpClient.setMaxRequestsQueuedPerDestination(queueSize);

            try { httpClient.start(); } catch (Exception e) { throw new BaseException("Error starting HTTP client for " + shortName, e); }

            return this;
        }

        @Override public Request makeRequest(String uriString) {
            return httpClient.newRequest(uriString);
        }

        public HttpClient getHttpClient() { return httpClient; }

        @Override public void destroy() {
            if (httpClient != null && httpClient.isRunning()) {
                try { httpClient.stop(); }
                catch (Exception e) { logger.error("Error stopping PooledRequestFactory HttpClient for " + shortName, e); }
            }
        }
        @Override protected void finalize() throws Throwable {
            if (httpClient != null && httpClient.isRunning()) {
                logger.warn("PooledRequestFactory finalize and httpClient still running for " + shortName + ", stopping");
                try { httpClient.stop(); } catch (Exception e) { logger.error("Error stopping PooledRequestFactory HttpClient for " + shortName, e); }
            }
            super.finalize();
        }
    }
}
