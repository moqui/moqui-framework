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

import org.apache.http.Header
import org.apache.http.HttpEntity
import org.apache.http.HttpHost
import org.apache.http.NameValuePair
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.AuthCache
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.HttpResponseException
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.*
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils

import org.moqui.BaseException
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.service.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.Charset

@CompileStatic
class RestClientImpl implements RestClient {
    protected final static Logger logger = LoggerFactory.getLogger(RestClientImpl.class)

    // NOTE: ecfi not currently used, but in place as will likely be useful for future features such as auth, etc
    protected ExecutionContextFactoryImpl ecfi

    protected URI uri = null
    protected String method = 'GET', contentType = 'application/json'
    protected Charset charset = Charset.forName('UTF-8')

    protected String bodyText = null
    protected List<NameValuePair> headerList = [], bodyParameterList = []

    protected String username = null, password = null

    RestClientImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    @Override
    RestClient uri(String location) { uri = new URI(location); return this }
    @Override
    RestClient uri(URI uri) { this.uri = uri; return this }

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
            headerList.add(new BasicNameValuePair(entry.key, entry.value))
        return this
    }
    @Override
    RestClient addHeader(String name, String value) { headerList.add(new BasicNameValuePair(name, value)); return this }
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
            bodyParameterList.add(new BasicNameValuePair(entry.key, entry.value))
        return this
    }
    @Override
    RestClient addBodyParameter(String name, String value) {
        bodyParameterList.add(new BasicNameValuePair(name, value))
        return this
    }

    @Override
    RestClient.RestResponse call() {
        if (uri == null) throw new IllegalStateException("No URI set in RestClient")

        RequestBuilder rb = RequestBuilder.create(method)

        // set basic fields, common across all methods
        rb.setUri(uri)
        rb.setCharset(charset)

        // add headers and parameters
        for (NameValuePair nvp in headerList) rb.addHeader(nvp.name, nvp.value)
        for (NameValuePair nvp in bodyParameterList) rb.addParameter(nvp)

        HttpUriRequest httpUriRequest = rb.build()

        HttpClientBuilder httpClientBuilder = HttpClients.custom()
        HttpHost target = new HttpHost(uri.host, uri.port, uri.scheme)
        if (username) {
            CredentialsProvider credsProvider = new BasicCredentialsProvider()
            credsProvider.setCredentials(new AuthScope(target.getHostName(), target.getPort()),
                    new UsernamePasswordCredentials(username, password))
            httpClientBuilder.setDefaultCredentialsProvider(credsProvider)
        }
        CloseableHttpClient httpClient = httpClientBuilder.build()

        try {
            if (bodyText) {
                if (httpUriRequest instanceof HttpEntityEnclosingRequestBase) {
                    StringEntity requestEntity = new StringEntity(bodyText, ContentType.create(contentType, "UTF-8"))
                    httpUriRequest.setEntity(requestEntity)
                    httpUriRequest.setHeader("Content-Type", contentType)
                } else {
                    throw new IllegalStateException("Tried to set body text on unsupported method ${method}")
                }
            }

            HttpClientContext localContext = HttpClientContext.create()

            if (username) {
                // Generate BASIC scheme object and add it to the local auth cache
                AuthCache authCache = new BasicAuthCache()
                authCache.put(target, new BasicScheme())
                localContext.setAuthCache(authCache)
            }

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
    }

    static class RestResponseImpl implements RestClient.RestResponse {
        protected RestClientImpl rci
        protected byte[] bytes = null
        protected Map<String, List<String>> headers = [:]
        protected int statusCode
        protected String reasonPhrase

        RestResponseImpl(RestClientImpl rci, CloseableHttpResponse response) {
            this.rci = rci

            statusCode = response.getStatusLine().getStatusCode()
            reasonPhrase = response.getStatusLine().getReasonPhrase()

            // get headers
            for (Header hdr in response.getAllHeaders()) {
                String name = hdr.getName()
                List<String> curList = headers.get(name)
                if (curList == null) { curList = []; headers.put(name, curList) }
                curList.add(hdr.getValue())
            }

            // get the response body
            HttpEntity entity = response.getEntity()
            bytes = EntityUtils.toByteArray(entity)
        }

        RestClient.RestResponse checkError() {
            if (statusCode < 200 || statusCode >= 300) {
                logger.info("Error ${statusCode} (${reasonPhrase}) in response to ${rci.method} to ${rci.uri.toASCIIString()}, response text:\n${text()}")
                throw new HttpResponseException(statusCode, reasonPhrase)
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
        Map<String, List<String>> headers() { return headers }

        @Override
        String headerFirst(String name) {
            List<String> valueList = headers.get(name)
            return valueList ? valueList[0] : null
        }
    }
}
