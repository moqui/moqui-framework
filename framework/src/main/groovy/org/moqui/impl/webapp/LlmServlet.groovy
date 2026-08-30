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
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.llm.LlmException
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Managed LLM gateway at /llm/*. Pumps SSE on the request thread so MoquiAuthFilter's EC stays live
 * (no startAsync — the filter destroys the EC in finally after chain.doFilter).
 */
@CompileStatic
class LlmServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(LlmServlet.class)
    private static final ScheduledExecutorService PING_SCHED = Executors.newSingleThreadScheduledExecutor { Runnable r ->
        Thread t = new Thread(r, "LlmServlet-sse-ping")
        t.setDaemon(true)
        return t
    }

    LlmServlet() { super() }

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

        if (MoquiServlet.handleCors(request, response, webappName, ecfi)) return
        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")

        // Reuse the MoquiAuthFilter EC. Do not destroy it and do not startAsync().
        ExecutionContextImpl ec = ecfi.activeContext.get()
        if (ec == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No ExecutionContext for LLM request")
            return
        }

        // WebFacade without initWebFacade: that would re-run Basic/login_key onto the user stack.
        if (ec.getWeb() == null) {
            ec.setWebFacade(new WebFacadeImpl(webappName, request, response, ec))
        }

        if (LlmGateway.isApiKeyOrBasic(request))
            request.setAttribute("moqui.request.authenticated", "true")

        ExecutionContextFactoryImpl.WebappInfo webappInfo = ecfi.getWebappInfo(webappName)
        boolean requireSessionToken = webappInfo == null || webappInfo.requireSessionToken
        String csrfErr = LlmGateway.csrfError(request, ec.getWeb().getSessionToken(), requireSessionToken)
        if (csrfErr != null) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, csrfErr)
            return
        }

        LlmGateway.Route route = LlmGateway.parseRoute(request.getPathInfo())
        if (route == null) {
            sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown LLM path")
            return
        }
        String method = request.getMethod() != null ? request.getMethod().toUpperCase() : ""
        if (route.isGet() && method != "GET") {
            sendJsonError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "GET required")
            return
        }
        if (route.isPost() && method != "POST") {
            sendJsonError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST required")
            return
        }

        Map<String, Object> body = new LinkedHashMap<>()
        try {
            if (route.isPost()) {
                String raw = ec.getWeb().getRequestBodyText()
                body = LlmGateway.parseBody(raw)
                if (route.conversationId != null && body.get("conversationId") == null)
                    body.put("conversationId", route.conversationId)
            }
            switch (route.op) {
                case LlmGateway.Route.Op.GET_PROFILES:
                    sendJson(response, 200, [profiles: LlmGateway.listProfiles(ec)])
                    return
                case LlmGateway.Route.Op.GET_CONVERSATION:
                    sendJson(response, 200, LlmGateway.getConversationMap(ec, route.conversationId))
                    return
                case LlmGateway.Route.Op.CANCEL:
                    Map<String, Object> cancelled = LlmGateway.cancel(ec, route.conversationId)
                    int cst = cancelled.get("httpStatus") instanceof Number ?
                            ((Number) cancelled.get("httpStatus")).intValue() : 200
                    sendJson(response, cst, cancelled)
                    return
                case LlmGateway.Route.Op.CHAT:
                case LlmGateway.Route.Op.RESUME:
                    handleTurn(ec, request, response, body, route.op == LlmGateway.Route.Op.RESUME)
                    return
            }
        } catch (ArtifactAuthorizationException e) {
            logger.warn("LLM Access Forbidden: " + e.message)
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("LLM Too Many Requests: " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            sendJsonError(response, 429, e.message)
        } catch (LlmException e) {
            sendJsonError(response, LlmGateway.httpStatusOf(e), e.message)
        } catch (Throwable t) {
            logger.error("Error in LlmServlet", t)
            if (!response.isCommitted())
                sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, t.message ?: t.toString())
        } finally {
            MDC.remove("moqui_llm_conversationId")
            // Filter destroys the EC after service() returns.
        }
    }

    private static void handleTurn(ExecutionContextImpl ec, HttpServletRequest request, HttpServletResponse response,
            Map<String, Object> body, boolean resume) throws IOException {
        LlmClientImpl client = LlmGateway.prepareClient(ec, body, resume)
        String convId = client.convId()
        if (convId) MDC.put("moqui_llm_conversationId", convId)
        boolean stream = LlmGateway.wantsStream(body, request.getHeader("Accept"))
        if (!stream) {
            LlmResponse r = client.call()
            sendJson(response, LlmGateway.jsonStatus(r), LlmGateway.responseToMap(r))
            return
        }
        pumpSse(ec, response, client)
    }

    /** Request-thread SSE pump. Yield still finishes this HTTP response; resume is a new POST. */
    private static void pumpSse(ExecutionContextImpl ec, HttpServletResponse response, LlmClientImpl client)
            throws IOException {
        response.setStatus(200)
        response.setContentType("text/event-stream; charset=UTF-8")
        response.setHeader("Cache-Control", "no-cache, no-store")
        response.setHeader("X-Accel-Buffering", "no")
        response.flushBuffer()

        SseSink sink = new SseSink(response.getWriter())
        int pingSeconds = client.ssePingSeconds()
        ScheduledFuture<?> pingTask = null
        if (pingSeconds > 0) {
            long period = pingSeconds
            pingTask = PING_SCHED.scheduleAtFixedRate({
                try {
                    if (System.currentTimeMillis() - sink.lastWriteMs >= period * 1000L) sink.ping()
                } catch (Throwable t) {
                    sink.disconnected = true
                    client.abortActiveStream()
                }
            }, period, period, TimeUnit.SECONDS)
        }
        ServletStreamListener listener = new ServletStreamListener(sink, client)
        try {
            client.stream(listener)
        } catch (Throwable t) {
            if (!sink.disconnected && !sink.wroteTerminal) {
                try { sink.event("error", LlmGateway.errorData(t)) } catch (Throwable ignored) { }
            }
            if (sink.disconnected) {
                try { client.abortActiveStream() } catch (Throwable ignored) { }
                String id = client.convId()
                if (id && ec.getLlm() instanceof LlmFacadeImpl)
                    ((LlmFacadeImpl) ec.getLlm()).abortInFlight(id)
            }
            // Headers already committed to text/event-stream; error went out as event:error.
        } finally {
            if (pingTask != null) pingTask.cancel(false)
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

/** Visible for tests. Thread-safe writes so the ping timer can share the request writer. */
@CompileStatic
class SseSink {
    final Writer writer
    final Object lock = new Object()
    volatile long lastWriteMs = System.currentTimeMillis()
    volatile boolean disconnected = false
    volatile boolean wroteTerminal = false
    volatile boolean closed = false

    SseSink(Writer writer) { this.writer = writer }

    void event(String name, Object data) {
        synchronized (lock) {
            if (closed || disconnected) return
            try {
                writer.write(LlmGateway.formatSse(name, data))
                writer.flush()
                lastWriteMs = System.currentTimeMillis()
                if ("done".equals(name) || "error".equals(name) || "yield".equals(name))
                    wroteTerminal = true
            } catch (IOException e) {
                disconnected = true
                throw e
            }
        }
    }
    void ping() { event("ping", LlmGateway.pingData()) }
    void close() { closed = true }
}

@CompileStatic
class ServletStreamListener implements LlmStreamListener {
    final SseSink sink
    final LlmClientImpl client
    ServletStreamListener(SseSink sink, LlmClientImpl client) {
        this.sink = sink
        this.client = client
    }
    private void emit(String name, Object data) {
        if (sink.disconnected) return
        try {
            sink.event(name, data)
        } catch (IOException e) {
            sink.disconnected = true
            client.abortActiveStream()
        }
    }
    @Override void onConversation(String conversationId) {
        emit("conversation", [conversationId: conversationId] as Map<String, Object>)
    }
    @Override void onDelta(String textDelta) {
        emit("delta", [content: textDelta] as Map<String, Object>)
    }
    @Override void onToolCall(LlmToolCall call, LlmTool.Execution execution) {
        Map<String, Object> m = LlmGateway.toolCallToMap(call)
        m.put("execution", execution == LlmTool.Execution.CLIENT ? "client" : "server")
        emit("tool_call", m)
    }
    @Override void onToolResult(LlmToolCall call, Object result, LlmTool.Execution execution) {
        Map<String, Object> m = new LinkedHashMap<>()
        m.put("id", call != null ? call.id : null)
        m.put("name", call != null ? call.name : null)
        m.put("execution", execution == LlmTool.Execution.CLIENT ? "client" : "server")
        m.put("content", result)
        emit("tool_result", m)
    }
    @Override void onPing() { emit("ping", LlmGateway.pingData()) }
    @Override void onYield(List<LlmToolCall> pendingClientCalls) {
        emit("yield", LlmGateway.yieldData(pendingClientCalls))
    }
    @Override void onComplete(LlmResponse response) {
        emit("done", LlmGateway.doneData(response))
    }
    @Override void onError(LlmException error) { emit("error", LlmGateway.errorData(error)) }
    @Override void onFailure(Throwable t) { emit("error", LlmGateway.errorData(t)) }
}
