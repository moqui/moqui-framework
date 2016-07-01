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
package org.moqui.context;

import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/** Web Facade for access to HTTP Servlet objects and information. */
@SuppressWarnings("unused")
public interface WebFacade {
    String getRequestUrl();
    Map<String, Object> getParameters();

    HttpServletRequest getRequest();
    Map<String, Object> getRequestAttributes();
    /** Returns a Map with request parameters including session saved, multi-part body, json body, declared and named
     * path parameters, and standard Servlet request parameters (query string parameters, form body parameters). */
    Map<String, Object> getRequestParameters();
    /** Returns a Map with only secure (encrypted if over HTTPS) request parameters including session saved,
     * multi-part body, json body, and form body parameters (standard Servlet request parameters IFF there is no query string). */
    Map<String, Object> getSecureRequestParameters();
    String getHostName(boolean withPort);

    HttpServletResponse getResponse();

    HttpSession getSession();
    Map<String, Object> getSessionAttributes();
    /** Get the token to include in all POST requests with the name moquiSessionToken (in the session as 'moqui.session.token') */
    String getSessionToken();

    ServletContext getServletContext();
    Map<String, Object> getApplicationAttributes();
    String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption);

    Map<String, Object> getErrorParameters();
    List<String> getSavedMessages();
    List<String> getSavedErrors();
    List<ValidationError> getSavedValidationErrors();

    /** A list of recent screen requests to show to a user (does not include requests to transitions or standalone screens).
     * Map contains 'name' (screen name plus up to 2 parameter values), 'url' (full URL with parameters),
     * 'screenLocation', 'image' (last menu image in screen render path), and 'imageType' fields. */
    List<Map> getScreenHistory();

    void sendJsonResponse(Object responseObj);
    void sendTextResponse(String text);
    void sendTextResponse(String text, String contentType, String filename);
    void sendResourceResponse(String location);

    void handleXmlRpcServiceCall();
    void handleJsonRpcServiceCall();
    void handleEntityRestCall(List<String> extraPathNameList, boolean masterNameInPath);
    void handleServiceRestCall(List<String> extraPathNameList);
}
