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
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactTarpitException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.a2a.A2ACardBuilderImpl
import org.moqui.impl.llm.a2a.A2AException
import org.moqui.impl.llm.a2a.A2AJsonRpc
import org.moqui.impl.llm.a2a.A2AStreamSink
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * A2A 1.0 JSON-RPC binding at /llm/a2a/*. POST /llm/a2a/jsonrpc is the endpoint advertised in the Agent Card;
 * SendStreamingMessage and SubscribeToTask answer on the same URL with text/event-stream (/llm/a2a/stream is an
 * alias). This intentionally does not reuse /rpc/json, which dispatches by Moqui service name.
 * Auth is LlmAuthFilter on /llm/*; application errors are HTTP 200 with a JSON-RPC error (spec 9.5).
 */
@CompileStatic
class A2AServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(A2AServlet.class)

    A2AServlet() { super() }

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        String webappName = config.getInitParameter("moqui-name") ?: config.getServletContext().getInitParameter("moqui-name")
        logger.info("${config.getServletName()} initialized for webapp ${webappName}")
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactoryImpl ecfi =
                (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String webappName = getServletContext().getInitParameter("moqui-name")
        if (ecfi == null || webappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }
        if (!A2ACardBuilderImpl.enabled()) {
            sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "A2A is disabled")
            return
        }

        if (MoquiServlet.handleCors(request, response, webappName, ecfi)) return
        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")

        ExecutionContextImpl ec = ecfi.activeContext.get()
        if (ec == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No ExecutionContext for A2A request")
            return
        }

        if (ec.getWeb() == null) {
            ec.setWebFacade(new WebFacadeImpl(webappName, request, response, ec))
        }

        ExecutionContextFactoryImpl.WebappInfo webappInfo = ecfi.getWebappInfo(webappName)
        boolean requireSessionToken = webappInfo == null || webappInfo.requireSessionToken
        String csrfErr = LlmGateway.csrfError(request, ec.getWeb().getSessionToken(), requireSessionToken)
        if (csrfErr != null) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, csrfErr)
            return
        }

        String path = normalizedPath(request.getPathInfo())
        if (path != "jsonrpc" && path != "stream") {
            sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown A2A path")
            return
        }
        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : ""
        if (method != "POST") {
            sendJsonError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST required")
            return
        }

        Object id = null
        try {
            Map<String, Object> call = A2AJsonRpc.parseRequest(ec.getWeb().getRequestBodyText())
            id = call.get("id")
            A2AJsonRpc.validateEnvelope(call)
            A2AJsonRpc.checkVersion(request.getHeader("A2A-Version") ?: request.getParameter("A2A-Version"))
            String rpcMethod = (String) call.get("method")
            Map<String, Object> params = A2AJsonRpc.params(call)
            if (A2AJsonRpc.STREAMING_METHODS.contains(rpcMethod)) {
                pumpSse(ec, response, id, rpcMethod, params)
                return
            }
            Object result = A2AJsonRpc.invoke(ec, rpcMethod, params, A2ACardServlet.baseUrl(request))
            sendJson(response, 200, A2AJsonRpc.success(id, result))
        } catch (ArtifactAuthorizationException e) {
            logger.warn("A2A Access Forbidden: " + e.message)
            if (!response.isCommitted()) sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("A2A Too Many Requests: " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            if (!response.isCommitted()) sendJsonError(response, 429, e.message)
        } catch (Throwable t) {
            A2AException error = A2AJsonRpc.toA2A(t)
            if (error.code == A2AException.INTERNAL_ERROR) logger.error("A2A JSON-RPC request failed", t)
            if (!response.isCommitted()) sendJson(response, 200, A2AJsonRpc.failure(id, error))
        }
    }

    private static String normalizedPath(String pathInfo) {
        String path = pathInfo ?: ""
        while (path.startsWith("/")) path = path.substring(1)
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1)
        path
    }

    /** Request-thread SSE: each data line is a JSON-RPC response whose result is a StreamResponse. */
    private static void pumpSse(ExecutionContextImpl ec, HttpServletResponse response, Object id, String method,
            Map<String, Object> params) throws IOException {
        response.setStatus(200)
        response.setContentType("text/event-stream; charset=UTF-8")
        response.setHeader("Cache-Control", "no-cache, no-store")
        response.setHeader("X-Accel-Buffering", "no")
        response.flushBuffer()

        A2ASseSink sink = new A2ASseSink(response.getWriter(), id)
        try {
            A2AJsonRpc.stream(ec, method, params, sink, new LinkedHashMap<String, Object>())
        } catch (Throwable t) {
            A2AException error = A2AJsonRpc.toA2A(t)
            if (error.code == A2AException.INTERNAL_ERROR) logger.error("A2A stream " + method + " failed", t)
            // after the first event the task outcome (e.g. TASK_STATE_FAILED) has already been streamed
            if (sink.emitted == 0) sink.error(A2AJsonRpc.failure(id, error))
            else logger.warn("A2A stream " + method + " ended after " + sink.emitted + " events: " + error.message)
        } finally {
            sink.close()
        }
    }

    private static void sendJson(HttpServletResponse response, int status, Object body) {
        String json = LlmGateway.toJson(body)
        byte[] bytes = json.getBytes("UTF-8")
        response.setStatus(status)
        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")
        response.setContentLength(bytes.length)
        response.getOutputStream().write(bytes)
        response.getOutputStream().flush()
    }

    private static void sendJsonError(HttpServletResponse response, int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>()
        body.put("message", message)
        body.put("error", status)
        sendJson(response, status, body)
    }
}

/** Visible for tests. JSON-RPC SSE framing for A2A: data lines only, keep-alive as SSE comments. */
@CompileStatic
class A2ASseSink implements A2AStreamSink {
    final Writer writer
    final Object id
    volatile boolean disconnected = false
    volatile boolean closed = false
    int emitted = 0

    A2ASseSink(Writer writer, Object id) {
        this.writer = writer
        this.id = id
    }

    @Override
    boolean emit(Map<String, Object> streamResponse) {
        Map<String, Object> envelope = new LinkedHashMap<>()
        envelope.put("jsonrpc", "2.0")
        envelope.put("id", id)
        envelope.put("result", streamResponse)
        if (!write("data: " + LlmGateway.toJson(envelope) + "\n\n")) return false
        emitted++
        return true
    }

    @Override
    boolean ping() { write(": ping\n\n") }

    void error(Map<String, Object> failure) { write("data: " + LlmGateway.toJson(failure) + "\n\n") }

    void close() { closed = true }

    private synchronized boolean write(String text) {
        if (closed || disconnected) return false
        try {
            writer.write(text)
            writer.flush()
            // the servlet PrintWriter swallows IOExceptions; checkError reports a dropped client
            if (writer instanceof PrintWriter && ((PrintWriter) writer).checkError()) disconnected = true
        } catch (IOException ignored) {
            disconnected = true
        }
        return !disconnected
    }
}
