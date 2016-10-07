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
package org.moqui.impl.service

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.xml.XmlUtil

import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.HttpResponseException
import org.eclipse.jetty.client.api.ContentResponse
import org.eclipse.jetty.client.api.Request
import org.eclipse.jetty.client.util.BasicAuthentication
import org.eclipse.jetty.client.util.StringContentProvider
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.HttpHeader
import org.moqui.BaseException
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.StupidJavaUtilities.KeyValueString
import org.moqui.service.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

@CompileStatic
class RestClientImpl implements RestClient {
    protected final static Logger logger = LoggerFactory.getLogger(RestClientImpl.class)

    // NOTE: ecfi not currently used, but in place as will likely be useful for future features such as auth, etc
    protected ExecutionContextFactoryImpl ecfi

    protected URI uri = null
    protected String method = 'GET', contentType = 'application/json'
    protected Charset charset = StandardCharsets.UTF_8

    protected String bodyText = null
    protected List<KeyValueString> headerList = [], bodyParameterList = []

    protected String username = null, password = null

    RestClientImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    @Override
    RestClient uri(String location) { uri = new URI(location); return this }
    @Override
    RestClient uri(URI uri) { this.uri = uri; return this }
    @Override
    RestClient.UriBuilder uri() { return new UriBuilderImpl(this) }

    @Override
    RestClient method(String method) {
        method = method.toUpperCase()
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Method ${method} not valid")
        this.method = method
        return this
    }
    @Override
    RestClient contentType(String contentType) { this.contentType = contentType; return this }
    @Override
    RestClient encoding(String characterEncoding) { this.charset = Charset.forName(characterEncoding); return this }

    @Override
    RestClient addHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> entry in headers.entrySet())
            headerList.add(new KeyValueString(entry.key, entry.value))
        return this
    }
    @Override
    RestClient addHeader(String name, String value) { headerList.add(new KeyValueString(name, value)); return this }
    @Override
    RestClient basicAuth(String username, String password) { this.username = username; this.password = password; return this }

    @Override
    RestClient text(String bodyText) {
        if (!BODY_METHODS.contains(method)) throw new IllegalStateException("Cannot use body text with method ${method}")
        this.bodyText = bodyText
        return this}
    @Override
    RestClient jsonObject(Object bodyJsonObject) {
        if (bodyJsonObject == null) { bodyText = null; return this }
        JsonBuilder jb = new JsonBuilder()
        if (bodyJsonObject instanceof Map) {
            jb.call((Map) bodyJsonObject)
        } else if (bodyJsonObject instanceof List) {
            jb.call((List) bodyJsonObject)
        } else {
            jb.call((Object) bodyJsonObject)
        }
        return text(jb.toString())
    }
    @Override
    RestClient xmlNode(Node bodyXmlNode) {
        if (bodyXmlNode == null) { bodyText = null; return this }
        return text(XmlUtil.serialize(bodyXmlNode))
    }

    @Override
    RestClient addBodyParameters(Map<String, String> formFields) {
        for (Map.Entry<String, String> entry in formFields.entrySet())
            bodyParameterList.add(new KeyValueString(entry.key, entry.value))
        return this
    }
    @Override
    RestClient addBodyParameter(String name, String value) {
        bodyParameterList.add(new KeyValueString(name, value))
        return this
    }

    @Override
    RestClient.RestResponse call() {
        if (uri == null) throw new IllegalStateException("No URI set in RestClient")

        ContentResponse response = null

        HttpClient httpClient = new HttpClient()
        httpClient.start()

        try {
            Request request = httpClient.newRequest(uri)
            request.method(method)
            // set charset on request?

            // add headers and parameters
            for (KeyValueString nvp in headerList) request.header(nvp.key, nvp.value)
            for (KeyValueString nvp in bodyParameterList) request.param(nvp.key, nvp.value)
            // authc
            if (username != null && !username.isEmpty()) httpClient.getAuthenticationStore().addAuthentication(
                    new BasicAuthentication(uri, null, username, password))

            if (bodyText != null && !bodyText.isEmpty()) {
                request.content(new StringContentProvider(contentType, bodyText, charset), contentType)
                request.header(HttpHeader.CONTENT_TYPE, contentType)
            }
            request.accept(contentType)

            if (logger.isTraceEnabled()) logger.trace("RestClient request ${request.getMethod()} ${request.getURI()} Headers: ${request.getHeaders()}")

            response = request.send()
        } finally {
            httpClient.stop()
        }

        return new RestResponseImpl(this, response)

        /*

        try {

            logger.info("RestClient request '${httpUriRequest.getRequestLine()}' Headers: ${httpUriRequest.getAllHeaders()}")

            CloseableHttpResponse response = httpClient.execute(target, httpUriRequest, localContext)
            try {
                return new RestResponseImpl(this, response)
            } finally {
                response.close()
            }
        } finally {
            httpClient.close()
        }
        */
    }

    static class RestResponseImpl implements RestClient.RestResponse {
        protected RestClientImpl rci
        protected ContentResponse response
        protected byte[] bytes = null
        protected Map<String, ArrayList<String>> headers = [:]
        protected int statusCode
        protected String reasonPhrase

        RestResponseImpl(RestClientImpl rci, ContentResponse response) {
            this.rci = rci

            statusCode = response.getStatus()
            reasonPhrase = response.getReason()

            // get headers
            for (HttpField hdr in response.getHeaders()) {
                String name = hdr.getName()
                ArrayList<String> curList = headers.get(name)
                if (curList == null) { curList = new ArrayList<>(); headers.put(name, curList) }
                curList.addAll(Arrays.asList(hdr.getValues()))
            }

            // get the response body
            bytes = response.getContent()
        }

        RestClient.RestResponse checkError() {
            if (statusCode < 200 || statusCode >= 300) {
                logger.info("Error ${statusCode} (${reasonPhrase}) in response to ${rci.method} to ${rci.uri.toASCIIString()}, response text:\n${text()}")
                throw new HttpResponseException("Error ${statusCode} (${reasonPhrase}) in response to ${rci.method} to ${rci.uri.toASCIIString()}", response)
            }
            return this
        }

        @Override
        int getStatusCode() { return statusCode }
        @Override
        String getReasonPhrase() { return reasonPhrase }

        @Override
        String text() { return StupidUtilities.toStringCleanBom(bytes) }
        @Override
        Object jsonObject() {
            try {
                return new JsonSlurper().parseText(text())
            } catch (Throwable t) {
                throw new BaseException("Error parsing JSON response from request to ${rci.uri.toASCIIString()}", t)
            }
        }
        @Override
        Node xmlNode() {
            try {
                return new XmlParser().parseText(text())
            } catch (Throwable t) {
                throw new BaseException("Error parsing XML response from request to ${rci.uri.toASCIIString()}", t)
            }
        }
        @Override
        byte[] bytes() { return bytes }

        @Override
        Map<String, ArrayList<String>> headers() { return headers }

        @Override
        String headerFirst(String name) {
            List<String> valueList = headers.get(name)
            return valueList ? valueList[0] : null
        }
    }

    static class UriBuilderImpl implements RestClient.UriBuilder {
        private static final char slashChar = '/' as char
        private RestClientImpl rci
        private String protocol = "http"
        private String host = (String) null
        private int port = 80
        private StringBuilder path = new StringBuilder()
        private Map<String, String> parameters = (Map<String, String>) null
        private String fragment = (String) null

        UriBuilderImpl(RestClientImpl rci) { this.rci = rci }

        @Override
        RestClient.UriBuilder protocol(String protocol) { this.protocol = protocol; return this }
        @Override
        RestClient.UriBuilder host(String host) { this.host = host; return this }
        @Override
        RestClient.UriBuilder port(int port) { this.port = port; return this }
        @Override
        RestClient.UriBuilder path(String pathEl) {
            if (!pathEl.startsWith("/")) path.append('/')
            path.append(pathEl)
            int lastIndex = path.length() - 1
            if (slashChar == path.charAt(lastIndex)) path.deleteCharAt(lastIndex)
            return this
        }
        @Override
        RestClient.UriBuilder parameter(String name, String value) {
            if (parameters == null) parameters = new LinkedHashMap<>()
            parameters.put(name, value)
            return this
        }
        @Override
        RestClient.UriBuilder parameters(Map<String, String> parms) {
            if (parameters == null) {
                parameters = new LinkedHashMap<>(parms)
            } else {
                parameters.putAll(parms)
            }
            return this
        }
        @Override
        RestClient.UriBuilder fragment(String fragment) { this.fragment = fragment; return this }

        @Override
        RestClient build() {
            if (host == null || host.isEmpty()) throw new IllegalArgumentException("No host specified, call the host() method before build()")
            StringBuilder query = null
            if (parameters != null && parameters.size() > 0) {
                query = new StringBuilder()
                for (Map.Entry<String, String> parm in parameters) {
                    if (query.length() > 0) query.append('&')
                    query.append(URLEncoder.encode(parm.key, "UTF-8")).append('=').append(URLEncoder.encode(parm.value, "UTF-8"))
                }
            }
            if (path.length() == 0) path.append('/')
            URI newUri = new URI(protocol, null, host, port, path.toString(), query != null ? query.toString() : null, null)
            return rci.uri(newUri)
        }
    }
}
