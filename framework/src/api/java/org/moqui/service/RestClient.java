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
package org.moqui.service;

import groovy.util.Node;

import java.net.URI;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public interface RestClient {
    String GET = "GET";
    String PATCH = "PATCH";
    String PUT = "PUT";
    String POST = "POST";
    String DELETE = "DELETE";
    String OPTIONS = "OPTIONS";
    String HEAD = "HEAD";
    String[] METHODS = { GET, PATCH, PUT, POST, DELETE, OPTIONS, HEAD };
    String[] BODY_METHODS = { PATCH, POST, PUT };

    /** Full URL String including protocol, host, path, parameters, etc */
    RestClient uri(String location);
    /** URL object including protocol, host, path, parameters, etc */
    RestClient uri(URI uri);

    /** Sets the HTTP request method, defaults to 'get'; must be in the METHODS array */
    RestClient method(String method);
    /** Defaults to 'application/json', could also be 'text/xml', etc */
    RestClient contentType(String contentType);
    /** The MIME character encoding for the body sent and response. Defaults to <code>UTF-8</code>. Must be a valid
     * charset in the java.nio.charset.Charset class. */
    RestClient encoding(String characterEncoding);

    RestClient addHeaders(Map<String, String> headers);
    RestClient addHeader(String name, String value);
    RestClient basicAuth(String username, String password);
    // FUTURE: add other auth options such as OAUTH, certificate, etc

    /** Set the body text to use */
    RestClient text(String bodyText);
    /** Set the body text as JSON from an Object */
    RestClient jsonObject(Object bodyJsonObject);
    /** Set the body text as XML from a Groovy Node */
    RestClient xmlNode(Node bodyXmlNode);
    /** Add fields to put in body form parameters */
    RestClient addBodyParameters(Map<String, String> formFields);
    /** Add a field to put in body form parameters */
    RestClient addBodyParameter(String name, String value);

    /** Do the HTTP request and get the response */
    RestResponse call();

    interface RestResponse {
        /** If status code is not in the 200 range throw an exception with details; call this first for easy error
         * handling or skip it to handle manually or allow errors */
        RestResponse checkError();

        int getStatusCode();
        String getReasonPhrase();

        /** Get the plain text of the response */
        String text();
        /** Parse the response as JSON and return an Object */
        Object jsonObject();
        /** Parse the response as XML and return a Groovy Node */
        Node xmlNode();
        /** Get bytes from a binary response */
        byte[] bytes();
        // FUTURE: handle stream response, but in a way that avoids requiring an explicit close for other methods

        Map<String, List<String>> headers();
        String headerFirst(String name);
    }
}
