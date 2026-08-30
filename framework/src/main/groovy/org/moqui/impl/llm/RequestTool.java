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
package org.moqui.impl.llm;

import org.moqui.context.ArtifactAuthorizationException;
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.AuthenticationRequiredException;
import org.moqui.context.ExecutionContext;
import org.moqui.context.WebFacade;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.screen.WebFacadeStub;
import org.moqui.llm.LlmTool;
import org.moqui.screen.ScreenRender;
import org.moqui.util.ContextStack;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Primary SERVER tool: HTTP-like method+path dispatched through ScreenRender on the same thread
 * with authz and tarpit ON (ScreenTestImpl.renderInternal pattern, no extra thread).
 */
public class RequestTool implements LlmTool {
    static final String NAME = "request";
    static final String HTML_ERROR = "use /actions or a named transition; HTML screens are not valid tool results";
    static final String PATH_NOT_ALLOWED = "path not allowed";
    private static final Set<String> METHODS = new LinkedHashSet<>(
            Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE"));
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("type", "string");
        method.put("enum", new ArrayList<>(METHODS));
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "Absolute path from webroot, e.g. /qapps/.../actions or /rest/s1/...");
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("method", method);
        props.put("path", path);
        props.put("query", obj);
        props.put("body", obj);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", Arrays.asList("method", "path"));
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    private final List<LlmFacadeImpl.AllowedPath> allowedPaths = new ArrayList<>();

    public RequestTool() { }

    public RequestTool addAllowedPath(String prefix, String methodsCsv) {
        if (prefix != null && !prefix.isBlank())
            allowedPaths.add(new LlmFacadeImpl.AllowedPath(prefix.trim(), methodsCsv));
        return this;
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Call a Moqui screen, transition, or /rest path as the current user. Path only (no host). JSON in/out.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        String method = str(args.get("method"));
        if (method == null || method.isBlank()) return result(400, null, "method is required", null);
        method = method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) return result(400, null, "method must be GET, POST, PUT, PATCH, or DELETE", null);

        String path = str(args.get("path"));
        String pathError = validatePath(path);
        if (pathError != null) return result(400, null, pathError, null);
        List<String> segments;
        try {
            segments = normalizePath(path);
        } catch (IllegalArgumentException e) {
            return result(400, null, e.getMessage(), null);
        }
        String normalized = toNormalizedPath(segments);
        if (!isPathAllowed(normalized, method)) return result(403, null, PATH_NOT_ALLOWED, null);

        Map<String, Object> query = asMap(args.get("query"));
        Map<String, Object> body = asMap(args.get("body"));
        return renderOnScreen(ec, method, segments, query, body);
    }

    /**
     * Reject open-proxy paths. Must start with {@code /}; no {@code ://}, {@code //}, or {@code ..} segments.
     * @return error text or null if valid
     */
    public static String validatePath(String path) {
        if (path == null || path.isBlank()) return "path is required";
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) return "path must start with / (no host)";
        if (trimmed.startsWith("//")) return "path must not start with //";
        if (trimmed.contains("://")) return "path must not contain a URL scheme or host";
        try {
            normalizePath(trimmed);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Split, URL-decode, drop empties. Throws if a segment is {@code ..} or the first segment looks like a host. */
    public static List<String> normalizePath(String path) {
        if (path == null) throw new IllegalArgumentException("path is required");
        String trimmed = path.trim();
        if (trimmed.startsWith("//") || trimmed.contains("://"))
            throw new IllegalArgumentException("path must not contain a URL scheme or host");
        if (!trimmed.startsWith("/")) throw new IllegalArgumentException("path must start with / (no host)");
        int q = trimmed.indexOf('?');
        if (q >= 0) trimmed = trimmed.substring(0, q);
        List<String> segments = new ArrayList<>();
        for (String raw : trimmed.split("/")) {
            if (raw == null || raw.isEmpty()) continue;
            String decoded;
            try { decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()); }
            catch (Exception e) { decoded = raw; }
            if ("..".equals(decoded)) throw new IllegalArgumentException("path must not contain .. segments");
            segments.add(decoded);
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("path must start with / (no host)");
        String first = segments.get(0);
        if (first.indexOf(':') >= 0) throw new IllegalArgumentException("path must not contain a host");
        return segments;
    }

    public boolean isPathAllowed(String normalizedPath, String method) {
        if (allowedPaths.isEmpty()) return true;
        String m = method != null ? method.toUpperCase(Locale.ROOT) : "";
        for (LlmFacadeImpl.AllowedPath ap : allowedPaths) {
            if (methodAllowed(ap.methodsCsv, m) && pathMatches(ap.prefix, normalizedPath)) return true;
        }
        return false;
    }

    static boolean pathMatches(String prefix, String path) {
        if (prefix == null || prefix.isBlank() || path == null) return false;
        String pfx = prefix.trim();
        if (!pfx.startsWith("/")) pfx = "/" + pfx;
        if (path.equals(pfx)) return true;
        String withSlash = pfx.endsWith("/") ? pfx : pfx + "/";
        return path.startsWith(withSlash);
    }

    static boolean methodAllowed(String methodsCsv, String method) {
        if (methodsCsv == null || methodsCsv.isBlank()) return true;
        for (String part : methodsCsv.split(",")) {
            if (part != null && method.equals(part.trim().toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * ScreenRender on the current thread. Overridable so tests can assert allow-list rejection
     * happens before render.
     */
    protected Map<String, Object> renderOnScreen(ExecutionContext ec, String method, List<String> segments,
            Map<String, Object> query, Map<String, Object> body) {
        if (!(ec instanceof ExecutionContextImpl))
            return result(500, null, "ExecutionContextImpl is required for request", null);
        ExecutionContextImpl eci = (ExecutionContextImpl) ec;
        WebFacade previous = eci.getWeb();
        ContextStack cs = eci.getContext();
        cs.push();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            if (query != null) params.putAll(query);
            if (body != null) params.putAll(body);

            Map<String, Object> sessionAttrs = new LinkedHashMap<>();
            if (previous != null && previous.getSessionAttributes() != null)
                sessionAttrs.putAll(previous.getSessionAttributes());

            WebFacadeStub wfs = new WebFacadeStub(eci.ecfi, params, sessionAttrs, method.toLowerCase(Locale.ROOT));
            wfs.setSkipJsonSerialize(true);
            // CSRF skip used by ScreenRenderImpl for login_key. Internal dispatch is not a browser POST.
            wfs.getRequest().setAttribute("moqui.request.authenticated", "true");
            eci.setWebFacade(wfs);

            ScreenRender render = eci.getScreen().makeRender()
                    .webappName("webroot")
                    .rootScreenFromHost("localhost")
                    .screenPath(segments);
            render.render(wfs.getRequest(), wfs.getResponse());

            int status = wfs.getHttpServletResponseStub().getStatus();
            Object json = wfs.getResponseJsonObj();
            String text = wfs.getResponseText();
            Map<String, Object> headers = headersFromStub(wfs);
            if (isHtmlDump(json, text, wfs.getHttpServletResponseStub().getContentType(), status)) {
                return result(400, null, HTML_ERROR, headers);
            }
            if (eci.getMessage().hasError()) {
                String errors = eci.getMessage().getErrorsString();
                eci.getMessage().clearErrors();
                if (json == null && (text == null || text.isBlank())) text = errors;
            }
            return result(status, json, json != null ? null : text, headers);
        } catch (ArtifactAuthorizationException e) {
            return result(403, null, e.getMessage(), null);
        } catch (ArtifactTarpitException e) {
            return result(429, null, e.getMessage(), null);
        } catch (AuthenticationRequiredException e) {
            return result(401, null, e.getMessage(), null);
        } catch (Throwable t) {
            return result(statusFrom(t), null, t.getMessage(), null);
        } finally {
            cs.pop();
            if (previous != null) eci.setWebFacade(previous);
            else eci.clearWebFacade();
        }
    }

    static boolean isHtmlDump(Object json, String text, String contentType, int status) {
        if (json != null) return false;
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html")) return true;
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        String lower = t.length() > 32 ? t.substring(0, 32).toLowerCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
        return lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<body")
                || lower.startsWith("<head");
    }

    static Map<String, Object> headersFromStub(WebFacadeStub wfs) {
        Map<String, Object> headers = new LinkedHashMap<>();
        Map<String, Object> stubHeaders = wfs.getHttpServletResponseStub().getHeaderMap();
        if (stubHeaders != null) headers.putAll(stubHeaders);
        String ct = wfs.getHttpServletResponseStub().getContentType();
        if (ct != null && !headers.containsKey("Content-Type")) headers.put("Content-Type", ct);
        return headers;
    }

    static int statusFrom(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ArtifactAuthorizationException) return 403;
            if (cur instanceof ArtifactTarpitException) return 429;
            if (cur instanceof AuthenticationRequiredException) return 401;
            cur = cur.getCause();
        }
        return 500;
    }

    static Map<String, Object> result(int status, Object json, String text, Map<String, Object> headers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("json", json);
        m.put("text", text);
        m.put("headers", headers != null ? headers : new LinkedHashMap<>());
        return m;
    }

    static String toNormalizedPath(List<String> segments) {
        if (segments == null || segments.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : segments) sb.append('/').append(s);
        return sb.toString();
    }

    static String str(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }
}
