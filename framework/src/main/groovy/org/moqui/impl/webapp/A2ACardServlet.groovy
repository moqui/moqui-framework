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
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.a2a.A2ACardBuilderImpl
import org.moqui.util.SystemBinding

import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Public A2A discovery at /.well-known/agent-card.json. No auth: the card is built per request and holds only
 * identity, the JSON-RPC interface URL, security schemes, and one generic skill (skills are on the extended card).
 * Set a2a_enabled=false to hide it.
 */
@CompileStatic
class A2ACardServlet extends HttpServlet {
    @Override
    void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!A2ACardBuilderImpl.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        response.setHeader("Access-Control-Allow-Origin", "*")
        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : ""
        if (method == "OPTIONS") {
            response.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            response.setHeader("Access-Control-Allow-Headers", "A2A-Version, Content-Type")
            response.setStatus(HttpServletResponse.SC_NO_CONTENT)
            return
        }
        if (method != "GET" && method != "HEAD") {
            response.setHeader("Allow", "GET, HEAD, OPTIONS")
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            return
        }

        byte[] bytes = LlmGateway.toJson(A2ACardBuilderImpl.buildPublic(baseUrl(request))).getBytes(StandardCharsets.UTF_8)
        String etag = '"' + MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString().substring(0, 32) + '"'
        response.setHeader("ETag", etag)
        response.setHeader("Cache-Control", "public, max-age=300")
        if (etag == request.getHeader("If-None-Match")) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED)
            return
        }
        response.setStatus(HttpServletResponse.SC_OK)
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.setContentLength(bytes.length)
        if (method == "GET") response.getOutputStream().write(bytes)
    }

    /**
     * Public base URL advertised in the Agent Card. a2a_public_url wins; X-Forwarded-Proto/Host are used only when
     * a2a_trust_forwarded_headers is true, because any client can send them and would otherwise choose the URL
     * published in the card. Otherwise the URL the container parsed for this request is used.
     */
    static String baseUrl(HttpServletRequest request) {
        String configured = validBaseUrl(SystemBinding.getPropOrEnv("a2a_public_url"))
        if (configured != null) return configured

        String scheme = null
        String host = null
        if ("true".equalsIgnoreCase(SystemBinding.getPropOrEnv("a2a_trust_forwarded_headers"))) {
            scheme = validScheme(firstValue(request.getHeader("X-Forwarded-Proto")))
            host = validHost(firstValue(request.getHeader("X-Forwarded-Host")))
        }
        if (scheme == null) scheme = validScheme(request.getScheme()) ?: "http"
        if (host == null) {
            int port = request.getServerPort()
            boolean defaultPort = port <= 0 || ("http" == scheme && port == 80) || ("https" == scheme && port == 443)
            host = validHost(request.getServerName() + (defaultPort ? "" : ":" + port)) ?: "localhost"
        }
        scheme + "://" + host + (request.getContextPath() ?: "")
    }

    /** Absolute http(s) URL, no user-info, query, fragment or control characters; trailing slashes removed. */
    static String validBaseUrl(String value) {
        String url = value?.trim()
        if (!url) return null
        // no spaces or control characters (header injection, CR/LF)
        for (int i = 0; i < url.length(); i++) if (url.charAt(i) <= (char) ' ') return null
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1)
        URI uri
        try {
            uri = new URI(url)
        } catch (URISyntaxException ignored) {
            return null
        }
        if (validScheme(uri.getScheme()) == null || uri.getHost() == null) return null
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return null
        if (uri.getPath() != null && uri.getPath().contains("//")) return null
        url
    }

    private static String validScheme(String value) {
        String scheme = value?.trim()?.toLowerCase()
        (scheme == "http" || scheme == "https") ? scheme : null
    }

    /** host or host:port, no credentials, no control characters, no path. */
    private static String validHost(String value) {
        String host = value?.trim()
        if (!host || !(host ==~ /[A-Za-z0-9._~\-]+(:[0-9]{1,5})?|\[[0-9A-Fa-f:.]+\](:[0-9]{1,5})?/)) return null
        host
    }

    private static String firstValue(String header) {
        if (header == null) return null
        String first = header.split(",")[0].trim()
        return first ?: null
    }
}
