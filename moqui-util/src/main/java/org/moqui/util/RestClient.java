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
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SuppressWarnings("unused")
public class RestClient {
    public enum Method { GET, PATCH, PUT, POST, DELETE, OPTIONS, HEAD }
    public static final Method GET = Method.GET, PATCH = Method.PATCH, PUT = Method.PUT, POST = Method.POST,
            DELETE = Method.DELETE, OPTIONS = Method.OPTIONS, HEAD = Method.HEAD;
    private static final EnumSet<Method> BODY_METHODS = EnumSet.of(Method.PATCH, Method.POST, Method.PUT);
    private static final Logger logger = LoggerFactory.getLogger(RestClient.class);

    private URI uri = null;
    private Method method = Method.GET;
    private String contentType = "application/json";
    private Charset charset = StandardCharsets.UTF_8;
    private String bodyText = null;
    private List<KeyValueString> headerList = new LinkedList<>();
    private List<KeyValueString> bodyParameterList = new LinkedList<>();
    private String username = null;
    private String password = null;

    public RestClient() { }

    /** Full URL String including protocol, host, path, parameters, etc */
    public RestClient uri(String location) throws URISyntaxException {
        uri = new URI(location);
        return this;
    }

    /** URL object including protocol, host, path, parameters, etc */
    public RestClient uri(URI uri) { this.uri = uri; return this; }
    public UriBuilder uri() { return new UriBuilder(this); }
    public URI getUri() { return uri; }

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

    /** Defaults to 'application/json', could also be 'text/xml', etc */
    public RestClient contentType(String contentType) {
        this.contentType = contentType;
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

    /** Do the HTTP request and get the response */
    public RestResponse call() {
        if (uri == null) throw new IllegalStateException("No URI set in RestClient");

        ContentResponse response = null;

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setTrustAll(true);
        HttpClient httpClient = new HttpClient(sslContextFactory);

        try {
            httpClient.start();

            final Request request = httpClient.newRequest(uri);
            request.method(method.name());
            // set charset on request?

            // add headers and parameters
            for (KeyValueString nvp : headerList) request.header(nvp.key, nvp.value);
            for (KeyValueString nvp : bodyParameterList) request.param(nvp.key, nvp.value);
            // authc
            if (username != null && !username.isEmpty())
                httpClient.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, null, username, password));

            if (bodyText != null && !bodyText.isEmpty()) {
                request.content(new StringContentProvider(contentType, bodyText, charset), contentType);
                request.header(HttpHeader.CONTENT_TYPE, contentType);
            }

            request.accept(contentType);

            if (logger.isTraceEnabled())
                logger.trace("RestClient request " + request.getMethod() + " " + String.valueOf(request.getURI()) + " Headers: " + String.valueOf(request.getHeaders()));

            response = request.send();
        } catch (Exception e) {
            throw new BaseException("Error calling REST request", e);
        } finally {
            try {
                httpClient.stop();
            } catch (Exception e) {
                logger.error("Error stopping REST HttpClient", e);
            }
        }

        return new RestResponse(this, response);
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
                logger.info("Error " + String.valueOf(statusCode) + " (" + reasonPhrase + ") in response to " + rci.method + " to " + rci.uri.toASCIIString() + ", response text:\n" + text());
                throw new HttpResponseException("Error " + String.valueOf(statusCode) + " (" + reasonPhrase + ") in response to " + rci.method + " to " + rci.uri.toASCIIString(), response);
            }

            return this;
        }

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
                throw new BaseException("Error parsing JSON response from request to " + rci.uri.toASCIIString(), t);
            }
        }
        /** Parse the response as XML and return a MNode */
        public MNode xmlNode() { return MNode.parseText(rci.uri.toASCIIString(), text()); }

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
                return new String(bytes, 3, bytes.length - 3, "UTF-8");
            } else {
                return new String(bytes, "UTF-8");
            }
        }
    }

    public static class UriBuilder {
        private RestClient rci;
        private String protocol = "http";
        private String host = (String) null;
        private int port = 80;
        private StringBuilder path = new StringBuilder();
        private Map<String, String> parameters = (Map<String, String>) null;
        private String fragment = (String) null;

        UriBuilder(RestClient rci) { this.rci = rci; }

        public UriBuilder protocol(String protocol) { this.protocol = protocol; return this; }

        public UriBuilder host(String host) {
            this.host = host;
            return this;
        }

        public UriBuilder port(int port) {
            this.port = port;
            return this;
        }

        public UriBuilder path(String pathEl) {
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
            StringBuilder query = null;
            if (parameters != null && parameters.size() > 0) {
                query = new StringBuilder();
                for (Map.Entry<String, String> parm : parameters.entrySet()) {
                    if (query.length() > 0) query.append("&");
                    query.append(URLEncoder.encode(parm.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(parm.getValue(), "UTF-8"));
                }

            }

            if (path.length() == 0) path.append("/");
            URI newUri = new URI(protocol, null, host, port, path.toString(), query != null ? query.toString() : null, null);
            return rci.uri(newUri);
        }
    }

    private static class KeyValueString {
        KeyValueString(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String key;
        public String value;
    }
}
