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
    static final String HTML_ERROR = "HTML screens are not valid tool results. " +
            "Use /apps (not /qapps). GET {screen}/actions for screen JSON, GET {screen}/actions/{formListName} " +
            "for form-list rows (browse jsonPath), or POST {screen}/{transitionName} for a named transition " +
            "from browse (not /actions/{transition})";
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
        return "Call a Moqui screen, transition, or /rest path as the current user. Path only (no host). JSON in/out. " +
                "Form-list finds: GET {screen}/actions/{formName} (browse jsonPath). Named transitions: POST " +
                "{screen}/{transition}. HTML screens are rejected.";
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
        MessageCapture.Snap prior = MessageCapture.take(ec);
        try {
            return renderOnScreen(ec, method, segments, query, body);
        } finally {
            MessageCapture.restore(ec, prior);
        }
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
            if (isDotDotSegment(decoded))
                throw new IllegalArgumentException("path must not contain .. segments");
            segments.add(decoded);
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("path must start with / (no host)");
        String first = segments.get(0);
        if (first.indexOf(':') >= 0) throw new IllegalArgumentException("path must not contain a host");
        return segments;
    }

    /** Exact {@code ..}, encoded leftover {@code ../…}, or a slash inside a segment. */
    static boolean isDotDotSegment(String decoded) {
        if (decoded == null || decoded.isEmpty()) return false;
        if ("..".equals(decoded) || decoded.startsWith("..")) return true;
        return decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0;
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
            if (query != null) params.putAll(ServiceCallTool.sanitizeArguments(query));
            if (body != null) params.putAll(ServiceCallTool.sanitizeArguments(body));

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
            json = wrapFormListJson(segments, json, headers);
            if (ToolResultTrim.isSearchActionsPath(segments) || ScreenSearchHints.isMantleSearchActions(eci, segments))
                json = ToolResultTrim.projectSearchActions(json);
            if (isHtmlDump(json, text, wfs.getHttpServletResponseStub().getContentType(), status)) {
                return withSubmitted(finish(result(400, null, HTML_ERROR, headers), eci), body);
            }
            return withSubmitted(finish(result(status, json, json != null ? null : text, headers), eci), body);
        } catch (ArtifactAuthorizationException e) {
            return withSubmitted(finish(result(403, null, e.getMessage(), null), ec), body);
        } catch (ArtifactTarpitException e) {
            return withSubmitted(finish(result(429, null, e.getMessage(), null), ec), body);
        } catch (AuthenticationRequiredException e) {
            return withSubmitted(finish(result(401, null, e.getMessage(), null), ec), body);
        } catch (Throwable t) {
            return withSubmitted(finish(result(statusFrom(t), null, t.getMessage(), null), ec), body);
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

    /** Drop the show-total footer row so Query counts and sums are not applied twice. */
    static List<?> withoutTotalRows(List<?> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        List<Object> kept = null;
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            boolean total = row instanceof Map && "total".equals(String.valueOf(((Map<?, ?>) row).get("_moquiRowType")));
            if (!total) {
                if (kept != null) kept.add(row);
                continue;
            }
            if (kept == null) {
                kept = new ArrayList<>(rows.size() - 1);
                for (int j = 0; j < i; j++) kept.add(rows.get(j));
            }
        }
        return kept != null ? kept : rows;
    }

    /** Form-list GET {screen}/actions/{formName} returns a JSON array; wrap as {rows,totalCount} for Query(data.rows). */
    static boolean isFormListJsonPath(List<String> segments) {
        if (segments == null || segments.size() < 3) return false;
        return "actions".equals(segments.get(segments.size() - 2));
    }

    static Object wrapFormListJson(List<String> segments, Object json, Map<String, Object> headers) {
        if (!isFormListJsonPath(segments) || !(json instanceof List)) return json;
        Map<String, Object> wrap = new LinkedHashMap<>();
        List<?> rows = withoutTotalRows((List<?>) json);
        wrap.put("rows", rows);
        Object tc = headers != null ? headers.get("X-Total-Count") : null;
        if (tc == null && headers != null) tc = headers.get("x-total-count");
        int total;
        if (tc instanceof Number) total = ((Number) tc).intValue();
        else if (tc != null) {
            try { total = Integer.parseInt(tc.toString().trim()); }
            catch (NumberFormatException e) { total = rows.size(); }
        } else total = rows.size();
        wrap.put("totalCount", total);
        return wrap;
    }

    /** Attach messages produced by the screen, including warnings on a 200. */
    public static Map<String, Object> finish(Map<String, Object> result, ExecutionContext ec) {
        MessageCapture.Snap snap = MessageCapture.take(ec);
        MessageCapture.attach(result, snap);
        if (result.get("text") == null && snap != null && !snap.errors.isEmpty() && result.get("json") == null) {
            result.put("text", String.join("\n", snap.errors));
        }
        return result;
    }

    /** Echo the submitted body so a redirect partyId stays tied to the call that created it. */
    static Map<String, Object> withSubmitted(Map<String, Object> result, Map<String, Object> body) {
        if (result != null && body != null && !body.isEmpty()) result.put("submitted", body);
        return result;
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
