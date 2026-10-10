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

import org.moqui.context.ExecutionContext;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.screen.ScreenDefinition;
import org.moqui.impl.screen.ScreenUrlInfo;
import org.moqui.llm.LlmTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One client tool for the rendered screen: navigate, read, fill, find, and one-shot watch.
 * The server checks the navigate path and the user's view permission. The browser submits finds
 * and refuses mutation posts. An enrich map with {@code error} is a tool result, not a yield.
 */
public class ScreenUseTool implements LlmTool {
    static final String NAME = "screen_use";
    static final int MAX_KEYS = 40;
    static final int MAX_VALUE = 500;
    static final int MAX_CONTEXT = 2000;
    static final Pattern SENSITIVE = Pattern.compile("password|secret|api[_-]?key|authorization|ssn|creditcard");
    static final Set<String> ACTIONS = new LinkedHashSet<>(Arrays.asList(
            "navigate", "snapshot", "fill", "set_selection", "submit_find", "click_nav", "watch"));
    static final Set<String> UNTIL = new LinkedHashSet<>(Arrays.asList("submit", "navigate", "either"));
    static final List<String> LIST_KEYS = Arrays.asList("pageIndex", "pageSize", "orderByField");

    static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", new ArrayList<>(ACTIONS));
        action.put("description", "navigate: open a /qapps screen. snapshot: read the rendered screen. "
                + "fill: set form fields, no submit. set_selection: check rows, no submit. "
                + "submit_find: run a find or other navigation-only form. click_nav: follow a link or tab, or open a dialog id from snapshot. "
                + "watch: one user submit or navigation, then this ends.");
        Map<String, Object> until = new LinkedHashMap<>();
        until.put("type", "string");
        until.put("enum", new ArrayList<>(UNTIL));
        until.put("description", "watch only. submit, navigate, or either. Default either.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", action);
        Map<String, Object> path = mapOf("type", "string");
        path.put("description", "Screen path from browse, under /qapps. Not a transition, not /rest, not another origin.");
        props.put("path", path);
        Map<String, Object> parameters = mapOf("type", "object");
        parameters.put("description", "Declared screen parameters and find fields. Unknown names are returned as ignored.");
        props.put("parameters", parameters);
        Map<String, Object> fields = mapOf("type", "object");
        fields.put("description", "Form field name to value. Applied after navigation. Does not submit.");
        props.put("fields", fields);
        Map<String, Object> form = mapOf("type", "string");
        form.put("description", "Form name or id for snapshot, fill, set_selection, or submit_find.");
        props.put("form", form);
        Map<String, Object> id = mapOf("type", "string");
        id.put("description", "Control or dialog id for click_nav. A dialog id from snapshot opens that dialog. A control that posts is refused.");
        props.put("id", id);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", "array");
        values.put("items", mapOf("type", "string"));
        values.put("description", "Row ids for set_selection.");
        props.put("values", values);
        props.put("until", until);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", Collections.singletonList("action"));
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    public ScreenUseTool() { }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Use the screen the user is looking at. Prefer browse q, then action navigate to a /qapps screen "
                + "the user can view, with parameters and fields. snapshot reads the rendered forms. fill and "
                + "set_selection change values and do not submit. submit_find runs a find or other navigation-only "
                + "form; a save stays a user click. click_nav follows a link or tab, or opens a dialog id from snapshot, and refuses a post. watch waits "
                + "for one user submit or navigation. write_ui is for when no screen fits, or for a custom or batch "
                + "confirm. You never submit a mutation.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.CLIENT; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "client_only");
        err.put("instruction", "screen_use runs in the browser. The server does not drive the screen.");
        return err;
    }

    @Override
    public Map<String, Object> enrichForClient(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : new LinkedHashMap<>();
        String action = str(args.get("action"));
        if (action != null) action = action.trim().toLowerCase(Locale.ROOT);
        if (action == null || !ACTIONS.contains(action))
            return error("bad_action", "action must be navigate, snapshot, fill, set_selection, submit_find, click_nav, or watch.");
        if ("watch".equals(action)) return prepareWatch(args);
        if ("navigate".equals(action) || ("click_nav".equals(action) && str(args.get("path")) != null))
            return prepareNavigate(action, args, ec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        copyClean(out, "form", args.get("form"), 80);
        copyClean(out, "id", args.get("id"), 80);
        copyClean(out, "path", args.get("path"), 300);
        if ("fill".equals(action) || "set_selection".equals(action)) {
            Object fields = "set_selection".equals(action) ? null : cleanOpenMap(args.get("fields"));
            if (fields != null) out.put("fields", fields);
            List<String> values = cleanStrings(args.get("values"), 50);
            if (!values.isEmpty()) out.put("values", values);
        }
        return out;
    }

    /**
     * Same rule the browser uses. A form-link navigates. An m-form submits only when it is a read-only GET.
     * Anything else posts, and the user clicks.
     */
    public static boolean submitAllowed(String kind, String method, boolean readOnly) {
        if (kind == null) return false;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if ("m-form-link".equals(k) || "link".equals(k)) return true;
        if (!"m-form".equals(k) && !"form".equals(k)) return false;
        String m = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        return "GET".equals(m) && readOnly;
    }

    /** Replace the screen context block. Null or blank clears it. */
    public static String contextText(Object screen) {
        if (screen == null) return null;
        if (screen instanceof CharSequence) {
            String s = screen.toString().trim();
            return s.isEmpty() ? null : clip(s, MAX_CONTEXT);
        }
        if (!(screen instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) screen;
        StringBuilder sb = new StringBuilder();
        sb.append("The user is on this screen.");
        String path = str(m.get("path"));
        String title = str(m.get("title"));
        if (path != null && !path.isBlank()) sb.append("\npath: ").append(clip(path.trim(), 300));
        if (title != null && !title.isBlank()) sb.append("\ntitle: ").append(clip(title.trim(), 200));
        Object params = m.get("parameters");
        if (params instanceof Map && !((Map<?, ?>) params).isEmpty()) {
            sb.append("\nparameters:");
            int n = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) params).entrySet()) {
                if (n >= 20) break;
                String key = str(e.getKey());
                if (key == null || key.isBlank()) continue;
                sb.append("\n").append(key).append('=');
                sb.append(SENSITIVE.matcher(key.toLowerCase(Locale.ROOT)).find() ? "***" : clip(str(e.getValue()), 120));
                n++;
            }
        }
        Object selected = m.get("selected");
        if (selected instanceof List && !((List<?>) selected).isEmpty()) {
            List<String> ids = cleanStrings(selected, 20);
            if (!ids.isEmpty()) sb.append("\nselected: ").append(String.join(", ", ids));
        }
        String text = sb.toString().trim();
        if ("The user is on this screen.".equals(text)) return null;
        return clip(text, MAX_CONTEXT);
    }

    private static Map<String, Object> prepareWatch(Map<String, Object> args) {
        String until = str(args.get("until"));
        if (until == null || until.isBlank()) until = "either";
        else until = until.trim().toLowerCase(Locale.ROOT);
        if (!UNTIL.contains(until))
            return error("bad_until", "until must be submit, navigate, or either.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "watch");
        out.put("until", until);
        return out;
    }

    private static Map<String, Object> prepareNavigate(String action, Map<String, Object> args, ExecutionContext ec) {
        String raw = str(args.get("path"));
        String why = badPath(raw);
        if (why != null) return error("bad_path", why);
        String path = canonicalQapps(raw);
        if (!path.equals("/qapps") && !path.startsWith("/qapps/"))
            return error("bad_path", "path must be a screen under /qapps. browse q returns that path.");
        for (String seg : split(path)) {
            if ("actions".equals(seg) || "rest".equals(seg) || "services".equals(seg) || "entities".equals(seg))
                return error("bad_path", "That path is not a screen. Use the /qapps screen path from browse, not /actions, /rest, or a service.");
        }
        ExecutionContextImpl eci = BrowseTool.asEci(ec);
        if (eci == null) return error("no_context", "No screen tree is available for this call.");
        ScreenUrlInfo sui = BrowseTool.screenUrl(eci, path);
        String transitionName = sui != null ? sui.getTargetTransitionActualName() : null;
        if (transitionName != null && !transitionName.isEmpty())
            return error("transition", "That path is a transition. Navigate to the screen, then submit_find or tell the user which button to click.");
        ScreenDefinition screen = sui != null ? sui.getTargetScreen() : null;
        if (sui == null || !sui.isTargetExists() || screen == null)
            return error("not_found", "No screen at that path. browse q to find one the user can view.");
        if (!BrowseTool.screenViewPermitted(eci, path))
            return error("not_permitted", "The user cannot view that screen. Pick another browse hit.");
        Set<String> parameterNames = new LinkedHashSet<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        collectNames(screen, parameterNames, fieldNames);
        Split parameters = splitMap(args.get("parameters"), parameterNames);
        Split fields = splitMap(args.get("fields"), fieldNames);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("path", path);
        if (!parameters.kept.isEmpty()) out.put("parameters", parameters.kept);
        if (!fields.kept.isEmpty()) out.put("fields", fields.kept);
        if ("click_nav".equals(action)) copyClean(out, "id", args.get("id"), 80);
        List<String> ignored = new ArrayList<>();
        for (String name : parameters.ignored) if (!ignored.contains(name)) ignored.add(name);
        for (String name : fields.ignored) if (!ignored.contains(name)) ignored.add(name);
        if (!ignored.isEmpty()) out.put("ignored", ignored);
        return out;
    }

    static void collectNames(ScreenDefinition sd, Set<String> parameters, Set<String> fields) {
        if (sd == null) return;
        parameters.addAll(BrowseTool.parameterNames(sd.getParameterMap()));
        List<Map<String, Object>> forms = BrowseTool.screenForms(sd);
        boolean list = false;
        for (Map<String, Object> form : forms) {
            if (form == null) continue;
            if ("form-list".equals(String.valueOf(form.get("type")))) list = true;
            addStrings(fields, form.get("fields"));
            addStrings(parameters, form.get("fields"));
            Object findFields = form.get("findFields");
            if (!(findFields instanceof List)) continue;
            for (Object item : (List<?>) findFields) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> fm = (Map<?, ?>) item;
                String name = str(fm.get("name"));
                if (name != null && !name.isBlank()) {
                    parameters.add(name);
                    fields.add(name);
                }
                addStrings(parameters, fm.get("params"));
            }
        }
        if (list) parameters.addAll(LIST_KEYS);
    }

    static String badPath(String raw) {
        if (raw == null || raw.isBlank()) return "path is required. Use a /qapps path from browse.";
        String t = raw.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || t.startsWith("//") || t.contains("\\") || t.contains("..")
                || t.indexOf(' ') >= 0 || t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0)
            return "path must be a /qapps screen on this server. No other origin.";
        if (t.indexOf('?') >= 0 || t.indexOf('#') >= 0)
            return "Put query values in parameters, not in the path.";
        return null;
    }

    static String canonicalQapps(String raw) {
        String t = raw.trim();
        if (!t.startsWith("/")) t = "/" + t;
        while (t.contains("//")) t = t.replace("//", "/");
        while (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        if ("/apps".equals(t) || t.startsWith("/apps/")) t = "/qapps" + t.substring("/apps".length());
        return t;
    }

    private static List<String> split(String path) {
        List<String> segs = new ArrayList<>();
        for (String raw : path.split("/")) {
            if (raw != null && !raw.isEmpty()) segs.add(raw);
        }
        return segs;
    }

    private static void addStrings(Set<String> into, Object raw) {
        if (!(raw instanceof List)) return;
        for (Object item : (List<?>) raw) {
            String s = str(item);
            if (s != null && !s.isBlank()) into.add(s);
        }
    }

    private static final class Split {
        final Map<String, Object> kept = new LinkedHashMap<>();
        final List<String> ignored = new ArrayList<>();
    }

    private static Split splitMap(Object raw, Set<String> allowed) {
        Split split = new Split();
        if (!(raw instanceof Map)) return split;
        int n = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String key = str(e.getKey());
            if (key == null || key.isBlank()) continue;
            if (n >= MAX_KEYS || !allowed.contains(key)) {
                split.ignored.add(key);
                continue;
            }
            Object value = cleanValue(e.getValue());
            if (value == null) {
                if (e.getValue() != null) split.ignored.add(key);
                continue;
            }
            split.kept.put(key, value);
            n++;
        }
        return split;
    }

    /** Scalars and short string lists. Maps are dropped so a parameter cannot smuggle an object. */
    static Object cleanValue(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) return v;
        if (v instanceof CharSequence) return clip(v.toString(), MAX_VALUE);
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) v) {
                if (out.size() >= 20) break;
                if (item instanceof CharSequence || item instanceof Number || item instanceof Boolean)
                    out.add(item instanceof CharSequence ? clip(item.toString(), MAX_VALUE) : item);
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private static Map<String, Object> cleanOpenMap(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (n >= MAX_KEYS) break;
            String key = str(e.getKey());
            if (key == null || key.isBlank() || key.length() > 80) continue;
            Object value = cleanValue(e.getValue());
            if (value == null) continue;
            out.put(key, value);
            n++;
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> cleanStrings(Object raw, int max) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<?>) raw) {
            if (out.size() >= max) break;
            String s = str(item);
            if (s == null || s.isBlank()) continue;
            out.add(clip(s.trim(), 200));
        }
        return out;
    }

    private static void copyClean(Map<String, Object> out, String key, Object value, int max) {
        String s = str(value);
        if (s == null || s.isBlank()) return;
        out.put(key, clip(s.trim(), max));
    }

    private static Map<String, Object> error(String code, String instruction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("instruction", instruction);
        return m;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.replace('\u0000', ' ');
        return t.length() <= max ? t : t.substring(0, max);
    }

    static String str(Object o) { return o == null ? null : o.toString(); }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
