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
package org.moqui.impl.llm

import groovy.transform.CompileStatic
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.screen.ScreenForm
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.impl.service.RestApi
import org.moqui.impl.service.RestApi.PathNode
import org.moqui.impl.service.RestApi.ResourceNode
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.llm.LlmTool
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * SERVER catalog tool: directory listing of screens, REST, services, and entities the current user can VIEW.
 * Default depth 1. Never disableAuthz.
 */
@CompileStatic
class BrowseTool implements LlmTool {
    private static final Logger logger = LoggerFactory.getLogger(BrowseTool.class)
    static final String NAME = "browse"
    static final int MAX_CHILDREN = 100
    static final int MAX_DEPTH = 6
    static final int MAX_MATCH_LEN = 200
    static final int MAX_FORM_FIELDS = 50
    static final int MAX_HITS = 12
    /** Structural screen index, dropped when the screen tree or a screen's load time changes. */
    private static final ConcurrentHashMap<Integer, Map<String, Object>> SCREEN_INDEX = new ConcurrentHashMap<>()
    static final Set<String> SKIP_TRANSITIONS = new HashSet<>(
            Arrays.asList("actions", "formSelectColumns", "formSaveFind", "screenDoc"))
    private static final Map<String, Object> SCHEMA
    static {
        Map<String, Object> props = new LinkedHashMap<>()
        props.put("q", [type:"string", description:
                "Plain text (not a regex) to find a screen the user can open. Preferred over path+match. " +
                "Ranks menu title above screen name above field title above field name above service. " +
                "At most 12 hits: path, title, and form name plus type under /qapps. No field lists. " +
                "Ignores path, match, depth, and detail."] as Map)
        props.put("path", [type:"string", description:
                "Virtual catalog path. Empty or / lists roots: /qapps, /apps, /rest, /services, /entities. " +
                "Use for a directory listing or a form-list jsonPath, not to find a screen by words."] as Map)
        props.put("match", [type:"string", description:
                "Optional case-insensitive regex on child name, title, screen/form/form-list/transition parameter names, form fields, entityName, and transition serviceName"] as Map)
        props.put("depth", [type:"integer", description:
                "1 = this directory only (default). 2-6 search descendants. Cap 6"] as Map)
        props.put("detail", [type:"boolean", description:
                "On one screen, service, or entity path, add that leaf: forms and findFields, transition inParameters, or entity/service fields. A search or directory stays a summary."] as Map)
        Map<String, Object> schema = new LinkedHashMap<>()
        schema.put("type", "object")
        schema.put("properties", props)
        SCHEMA = Collections.unmodifiableMap(schema)
    }

    @Override String getName() { return NAME }
    @Override String getDescription() {
        return "Find a screen with q (plain text). Hits are a short list of screens this user may view, " +
                "ranked, at most 12, path under /qapps: path, title, name, and form name plus type. No fields. " +
                "Use q before path. Open the screen with screen_use; snapshot is the field list. " +
                "Directory listing is path, match, and depth, 1 level deep by default, and omits screens and " +
                "transitions this user may not view. Those rows are names and paths. A form-list row has jsonPath " +
                "({screen}/actions/{formName}) and method GET. A transition row has method, readOnly, and " +
                "serviceName when it is one service-call. detail=true on that one screen path, not the jsonPath, " +
                "adds forms, findFields, and transition inParameters. request GET jsonPath only when no screen " +
                "fits, with findFields as query (option keys exactly; requireParameters means an empty query " +
                "returns 0 rows). JSON is {rows,totalCount}. Roots: /qapps (prefer), /apps, /rest (s1/e1/m1), " +
                "/services (package.verb#noun), /entities (package; slashes not dots). Search screens before " +
                "/rest/s1, then run_service, then /rest/e1 last. Entity rows include createService " +
                "(create#EntityName). match searches name, title, serviceName, and form fields on the server. " +
                "Use depth 3-6 with match to find descendants. q ignores path, match, depth, and detail."
    }
    @Override Map<String, Object> getParametersSchema() { return SCHEMA }
    @Override Execution getExecution() { return Execution.SERVER }

    @Override
    Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap()
        String q = str(args.get("q"))
        if (q != null && !q.isBlank()) {
            if (q.length() > MAX_MATCH_LEN) return error("q exceeds ${MAX_MATCH_LEN} characters")
            return searchScreens(ec, q.trim())
        }
        String path = str(args.get("path"))
        if (path == null || path.isBlank()) path = "/"
        path = normalizePath(path)
        String match = str(args.get("match"))
        Pattern matchPat = null
        if (match != null && !match.isBlank()) {
            if (match.length() > MAX_MATCH_LEN) return error("match exceeds ${MAX_MATCH_LEN} characters")
            try {
                matchPat = Pattern.compile(match, Pattern.CASE_INSENSITIVE)
            } catch (Throwable t) {
                return error("invalid match regex: " + t.getMessage())
            }
        }
        int depth = intVal(args.get("depth"), 1)
        if (depth < 1) depth = 1
        if (depth > MAX_DEPTH) depth = MAX_DEPTH
        boolean detail = boolVal(args.get("detail"))

        List<String> segs = splitPath(path)
        if (segs.isEmpty()) return roots(matchPat)

        String root = segs.get(0)
        if (ec == null && !isRootOnly(segs)) return error("no ExecutionContext for browse")
        try {
            if ("qapps".equals(root) || "apps".equals(root) || "vapps".equals(root))
                return browseScreens(ec, "/" + root, segs.subList(1, segs.size()), matchPat, depth, detail)
            if ("rest".equals(root))
                return browseRest(ec, segs.subList(1, segs.size()), matchPat, depth, detail)
            if ("services".equals(root))
                return browseServices(ec, segs.subList(1, segs.size()), matchPat, depth, detail)
            if ("entities".equals(root))
                return browseEntities(ec, segs.subList(1, segs.size()), matchPat, depth, detail)
            return error("unknown catalog root: /" + root)
        } catch (ArtifactAuthorizationException e) {
            return errorStatus(403, e.getMessage())
        } catch (Throwable t) {
            logger.warn("browse failed for path ${path}: ${t.message}", t)
            return error(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())
        }
    }

    static Map<String, Object> roots(Pattern matchPat) {
        List<Map<String, Object>> children = new ArrayList<>()
        addChild(children, matchPat, "qapps", "screens", "/qapps", "Quasar screens (this UI)")
        addChild(children, matchPat, "apps", "screens", "/apps", "HTML screens (same tree as /qapps)")
        addChild(children, matchPat, "vapps", "screens", "/vapps", "Bootstrap Vue screens (same tree as /qapps)")
        addChild(children, matchPat, "rest", "rest", "/rest", "REST: s1 services, e1 entities, m1 masters")
        addChild(children, matchPat, "services", "services", "/services", "Services by package (package.verb#noun)")
        addChild(children, matchPat, "entities", "entities", "/entities",
                "Entities by package (slashes: /entities/moqui/basic); /rest/e1 last after screens, /rest/s1, run_service")
        return listing("/", "root", children)
    }

    static boolean isRootOnly(List<String> segs) { return segs == null || segs.isEmpty() }

    Map<String, Object> browseScreens(ExecutionContext ec, String wrapper, List<String> rest,
            Pattern matchPat, int depth, boolean detail) {
        ExecutionContextImpl eci = asEci(ec)
        if (eci == null) return error("ExecutionContextImpl is required")
        ScreenDefinition apps = getAppsScreen(eci)
        if (apps == null) return error("apps screen tree not found")
        ScreenDefinition cur = apps
        StringBuilder pathB = new StringBuilder(wrapper)
        for (String seg : rest) {
            SubscreensItem si = cur.getSubscreensItem(seg)
            if (si == null) {
                // maybe a transition leaf
                TransitionItem ti = cur.getTransitionItem(seg, "any")
                if (ti == null) return error("not found: " + pathB.toString() + "/" + seg)
                if (!screenViewPermitted(eci, pathB.toString())) return errorStatus(403, "not authorized")
                if (!transitionPermitted(eci, pathB.toString(), ti)) return errorStatus(403, "not authorized")
                return screenTransitionDetail(pathB.toString() + "/" + seg, cur, ti, eci.serviceFacade)
            }
            // Isolated AT_XML_SCREEN checks miss inheritAuthz from parent apps (System/Tools).
            // Walk the tree; request/run_service still enforce authz on execution.
            ScreenDefinition next = eci.screenFacade.getScreenDefinition(si.location)
            if (next == null) return error("screen not found: " + si.location)
            pathB.append('/').append(seg)
            cur = next
        }
        if (!screenViewPermitted(eci, pathB.toString())) return errorStatus(403, "not authorized")
        List<Map<String, Object>> children = new ArrayList<>()
        boolean truncated = collectScreenChildren(eci, cur, pathB.toString(), children, matchPat, depth, 1, !detail)
        Map<String, Object> out = listing(pathB.toString(), "screens", children)
        out.put("truncated", truncated)
        String title = cur.defaultMenuName
        if (title != null && !title.isEmpty()) out.put("title", title)
        List<String> params = parameterNames(cur.getParameterMap())
        if (!params.isEmpty()) out.put("parameters", params)
        if (detail) out.put("leaf", screenDetail(cur, eci.serviceFacade, pathB.toString()))
        return out
    }

    boolean collectScreenChildren(ExecutionContextImpl eci, ScreenDefinition sd, String path,
            List<Map<String, Object>> children, Pattern matchPat, int depth, int level, boolean listForms) {
        boolean truncated = false
        ServiceFacadeImpl sfi = eci.serviceFacade
        List<Map<String, Object>> forms = listForms ? screenForms(sd, path) : null
        // Form-list JSON paths and write transitions first when searching so match hits
        // before MAX_CHILDREN fills with sibling screens. detail=true puts those on leaf instead.
        if (listForms && matchPat != null) {
            truncated = addFormListChildren(path, children, matchPat, forms) || truncated
            truncated = addTransitionChildren(eci, sd, path, children, matchPat, sfi, forms) || truncated
        }
        ArrayList<SubscreensItem> items = sd.getSubscreensItemsSorted()
        int n = items != null ? items.size() : 0
        for (int i = 0; i < n; i++) {
            SubscreensItem si = (SubscreensItem) items.get(i)
            if (si == null || si.location == null) continue
            if (!si.isValidInCurrentContext()) continue
            String childPath = path + "/" + si.name
            // Same path walk as the menu (inherit-authz), not an isolated screen artifact check.
            if (!screenViewPermitted(eci, childPath)) continue
            ScreenDefinition childSd = null
            try { childSd = eci.screenFacade.getScreenDefinition(si.location) } catch (Throwable ignored) { }
            Map<String, Object> row = child(si.name, "screen", childPath, si.menuTitle)
            List<String> search = new ArrayList<>()
            search.add(si.name)
            if (si.menuTitle != null) search.add(si.menuTitle)
            if (childSd != null) {
                List<String> childParams = parameterNames(childSd.getParameterMap())
                if (!childParams.isEmpty()) search.addAll(childParams)
                if (matchPat != null) addFormSearchTexts(search, screenForms(childSd, childPath))
            }
            if (matchesAny(matchPat, search)) {
                if (children.size() >= MAX_CHILDREN) { truncated = true; break }
                children.add(row)
            }
            boolean willRecurse = depth > level && !truncated && childSd != null
            if (willRecurse) {
                truncated = collectScreenChildren(eci, childSd, childPath, children, matchPat, depth, level + 1, listForms) || truncated
            } else if (listForms && matchPat != null && childSd != null && !truncated) {
                // Default depth is 1; still surface matching form-list JSON paths and write transitions.
                List<Map<String, Object>> childForms = screenForms(childSd, childPath)
                truncated = addFormListChildren(childPath, children, matchPat, childForms) || truncated
                truncated = addTransitionChildren(eci, childSd, childPath, children, matchPat, sfi, childForms) || truncated
            }
        }
        if (listForms && matchPat == null) {
            truncated = addFormListChildren(path, children, matchPat, forms) || truncated
            truncated = addTransitionChildren(eci, sd, path, children, matchPat, sfi, forms) || truncated
        }
        return truncated
    }

    boolean addFormListChildren(String path, List<Map<String, Object>> children, Pattern matchPat,
            List<Map<String, Object>> forms) {
        boolean truncated = false
        if (forms == null) return truncated
        for (Map<String, Object> form : forms) {
            if (form == null || !"form-list".equals(form.get("type"))) continue
            Object jp = form.get("jsonPath")
            if (jp == null) continue
            if (!matchesAny(matchPat, formListSearchTexts(form))) continue
            if (children.size() >= MAX_CHILDREN) { truncated = true; break }
            children.add(formListChild(path, form))
        }
        return truncated
    }

    static Map<String, Object> formListChild(String screenPath, Map<String, Object> form) {
        String name = form.get("name") != null ? form.get("name").toString() : "form"
        String jsonPath = str(form.get("jsonPath"))
        if (jsonPath == null || jsonPath.isEmpty())
            jsonPath = requestScreenPath(screenPath) + "/actions/" + name
        Map<String, Object> row = child(name, "form-list", jsonPath, name)
        row.put("jsonPath", jsonPath)
        row.put("method", "GET")
        Object entityName = form.get("entityName")
        if (entityName != null) row.put("entityName", entityName)
        return row
    }

    static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        Object v = from.get(key)
        if (v != null) to.put(key, v)
    }

    static List<String> formListSearchTexts(Map<String, Object> form) {
        List<String> texts = new ArrayList<>()
        if (form == null) return texts
        Object n = form.get("name")
        if (n != null) texts.add(n.toString())
        Object en = form.get("entityName")
        if (en != null) texts.add(en.toString())
        Object list = form.get("list")
        if (list != null) texts.add(list.toString())
        Object jp = form.get("jsonPath")
        if (jp != null) texts.add(jp.toString())
        Object fields = form.get("fields")
        if (fields instanceof List) {
            for (Object fn : (List) fields) if (fn != null) texts.add(fn.toString())
        }
        addTitleTexts(texts, form.get("fieldTitles"))
        if (Boolean.TRUE.equals(form.get("requireParameters"))) texts.add("requireParameters")
        Object findFields = form.get("findFields")
        if (findFields instanceof List) {
            for (Object ff : (List) findFields) {
                if (!(ff instanceof Map)) continue
                Map fm = (Map) ff
                Object opts = fm.get("options")
                if (opts instanceof List) {
                    for (Object o : (List) opts) if (o != null) texts.add(o.toString())
                }
            }
        }
        return texts
    }

    boolean addTransitionChildren(ExecutionContextImpl eci, ScreenDefinition sd, String path,
            List<Map<String, Object>> children, Pattern matchPat, ServiceFacadeImpl sfi, List<Map<String, Object>> forms) {
        boolean truncated = false
        for (TransitionItem ti : sd.getAllTransitions()) {
            if (ti == null || SKIP_TRANSITIONS.contains(ti.name)) continue
            if (!transitionPermitted(eci, path, ti)) continue
            List<String> extra = transitionSearchTexts(ti, sfi, forms)
            if (!matchesAny(matchPat, extra)) continue
            if (children.size() >= MAX_CHILDREN) { truncated = true; break }
            children.add(transitionSummary(path + "/" + ti.name, ti))
        }
        return truncated
    }

    /** Directory row. Field lists and service parameters stay on detail=true. */
    static Map<String, Object> transitionSummary(String childPath, TransitionItem ti) {
        Map<String, Object> row = child(ti.name, "transition", childPath, ti.name)
        row.put("method", ti.method)
        row.put("readOnly", ti.readOnly)
        String svc = ti.singleServiceName
        if (svc != null && !svc.isEmpty()) row.put("serviceName", svc)
        return row
    }

    static Map<String, Object> transitionChild(String childPath, TransitionItem ti, ServiceFacadeImpl sfi,
            List<Map<String, Object>> forms) {
        Map<String, Object> row = child(ti.name, "transition", childPath, ti.name)
        row.put("method", ti.method)
        row.put("readOnly", ti.readOnly)
        String svc = ti.singleServiceName
        if (svc != null && !svc.isEmpty()) {
            row.put("serviceName", svc)
            List<String> ins = serviceInParams(sfi, svc)
            if (!ins.isEmpty()) row.put("inParameters", ins)
        } else if (ti.hasActionsOrSingleService()) {
            row.put("hasActions", true)
        }
        List<String> params = parameterNames(ti.getParameterMap())
        if (!params.isEmpty()) row.put("parameters", params)
        if (ti.pathParameterList != null && !ti.pathParameterList.isEmpty())
            row.put("pathParameters", new ArrayList<String>(ti.pathParameterList))
        Map<String, Object> form = formForTransition(forms, ti.name)
        if (form != null) {
            Object formName = form.get("name")
            if (formName != null) row.put("form", formName)
            Object fields = form.get("fields")
            if (fields instanceof List && !((List) fields).isEmpty()) row.put("formFields", fields)
        }
        return row
    }

    static List<String> transitionSearchTexts(TransitionItem ti, ServiceFacadeImpl sfi,
            List<Map<String, Object>> forms) {
        List<String> texts = new ArrayList<>()
        texts.add(ti.name)
        if (ti.singleServiceName != null) texts.add(ti.singleServiceName)
        texts.addAll(parameterNames(ti.getParameterMap()))
        if (ti.singleServiceName != null) texts.addAll(serviceInParams(sfi, ti.singleServiceName))
        Map<String, Object> form = formForTransition(forms, ti.name)
        if (form != null) {
            Object formName = form.get("name")
            if (formName != null) texts.add(formName.toString())
            Object fields = form.get("fields")
            if (fields instanceof List) {
                for (Object fn : (List) fields) if (fn != null) texts.add(fn.toString())
            }
            addTitleTexts(texts, form.get("fieldTitles"))
        }
        return texts
    }

    static List<String> parameterNames(Map<String, ScreenDefinition.ParameterItem> map) {
        List<String> names = new ArrayList<>()
        if (map == null || map.isEmpty()) return names
        for (String n : map.keySet()) if (n != null) names.add(n)
        return names
    }

    static List<String> serviceInParams(ServiceFacadeImpl sfi, String serviceName) {
        List<String> names = new ArrayList<>()
        if (sfi == null || serviceName == null || serviceName.isEmpty()) return names
        try {
            if (!sfi.isServiceDefined(serviceName)) return names
            ServiceDefinition sd = sfi.getServiceDefinition(serviceName)
            if (sd == null) return names
            Collection<String> ins = sd.getInParameterNames()
            if (ins != null) names.addAll(ins)
        } catch (Throwable ignored) { }
        return names
    }

    static Map<String, Object> screenTransitionDetail(String path, ScreenDefinition sd, TransitionItem ti,
            ServiceFacadeImpl sfi) {
        Map<String, Object> out = listing(path, "transition", Collections.emptyList())
        String screenPath = path
        int slash = path != null ? path.lastIndexOf('/') : -1
        if (slash > 0) screenPath = path.substring(0, slash)
        List<Map<String, Object>> forms = screenForms(sd, screenPath)
        Map<String, Object> leaf = transitionChild(path, ti, sfi, forms)
        leaf.remove("kind")
        leaf.remove("path")
        leaf.remove("title")
        if (sd != null) {
            List<String> screenParams = parameterNames(sd.getParameterMap())
            if (!screenParams.isEmpty()) leaf.put("screenParameters", screenParams)
        }
        out.put("leaf", leaf)
        out.put("screenPath", screenPath)
        out.put("hint", "This path is a transition, not a catalog of screens or form-lists. "
                + "Browse the parent screen. Do not retry match here.")
        return out
    }
    static Map<String, Object> screenDetail(ScreenDefinition sd, ServiceFacadeImpl sfi, String screenPath) {
        Map<String, Object> leaf = new LinkedHashMap<>()
        leaf.put("location", sd.location)
        leaf.put("title", sd.defaultMenuName)
        List<String> params = parameterNames(sd.getParameterMap())
        if (!params.isEmpty()) leaf.put("parameters", params)
        List<Map<String, Object>> forms = screenForms(sd, screenPath)
        if (!forms.isEmpty()) leaf.put("forms", forms)
        List<Map<String, Object>> trans = new ArrayList<>()
        for (TransitionItem ti : sd.getAllTransitions()) {
            if (ti == null || SKIP_TRANSITIONS.contains(ti.name)) continue
            Map<String, Object> row = transitionChild(ti.name, ti, sfi, forms)
            row.remove("kind")
            row.remove("path")
            row.remove("title")
            trans.add(row)
        }
        if (!trans.isEmpty()) leaf.put("transitions", trans)
        return leaf
    }

    static List<Map<String, Object>> screenForms(ScreenDefinition sd) {
        return screenForms(sd, null)
    }
    static List<Map<String, Object>> screenForms(ScreenDefinition sd, String screenPath) {
        List<Map<String, Object>> forms = new ArrayList<>()
        if (sd == null) return forms
        try {
            for (ScreenForm sf : sd.getAllForms()) {
                if (sf == null) continue
                MNode node = sf.getOrCreateFormNode()
                if (node == null) continue
                Map<String, Object> row = new LinkedHashMap<>()
                String name = node.attribute("name")
                if (name != null && !name.isEmpty()) row.put("name", name)
                boolean isList = "form-list".equals(node.getName())
                row.put("type", isList ? "form-list" : "form-single")
                String trans = node.attribute("transition")
                if (trans != null && !trans.isEmpty()) row.put("transition", trans)
                TransitionItem formTi = null
                if (trans != null && !trans.isEmpty()) {
                    try { formTi = sd.getTransitionItem(trans, "any") } catch (Throwable ignored) { }
                }
                row.put("navigationOnly", Boolean.valueOf(isNavigationOnly(formTi)))
                String list = node.attribute("list")
                if (list != null && !list.isEmpty()) row.put("list", list)
                MNode entityFind = node.first("entity-find")
                if (entityFind != null) {
                    String entityName = entityFind.attribute("entity-name")
                    if (entityName != null && !entityName.isEmpty()) row.put("entityName", entityName)
                }
                if (isList && sf.hasDataPrep() && name != null && !name.isEmpty() &&
                        screenPath != null && !screenPath.isEmpty()) {
                    row.put("jsonPath", requestScreenPath(screenPath) + "/actions/" + name)
                    row.put("method", "GET")
                }
                List<String> fieldNames = new ArrayList<>()
                List<String> fieldTitles = new ArrayList<>()
                for (MNode field : node.children("field")) {
                    if (field == null) continue
                    String fn = field.attribute("name")
                    if (fn != null && !fn.isEmpty()) fieldNames.add(fn)
                    String title = fieldTitleText(field)
                    if (title != null) fieldTitles.add(title)
                    if (fieldNames.size() >= MAX_FORM_FIELDS) break
                }
                if (!fieldNames.isEmpty()) row.put("fields", fieldNames)
                if (!fieldTitles.isEmpty()) row.put("fieldTitles", fieldTitles)
                if (isList) addFormListFindMeta(row, node)
                forms.add(row)
            }
        } catch (Throwable ignored) { }
        return forms
    }

    /** Find-header widgets, require-parameters, and JSON shape so GET jsonPath is not an empty guess. */
    static void addFormListFindMeta(Map<String, Object> row, MNode formNode) {
        if (row == null || formNode == null) return
        row.put("jsonShape", "{rows,totalCount}")
        MNode entityFind = formNode.first("entity-find")
        if (entityFind != null) {
            String limit = entityFind.attribute("limit")
            if (limit != null && !limit.isEmpty() && !limit.contains("\$")) {
                try { row.put("pageSizeDefault", Integer.valueOf(limit.trim())) } catch (NumberFormatException ignored) { }
            }
            MNode sfi = entityFind.first("search-form-inputs")
            if (sfi != null) {
                if ("true".equals(sfi.attribute("require-parameters"))) row.put("requireParameters", Boolean.TRUE)
                String orderBy = sfi.attribute("default-order-by")
                if (orderBy != null && !orderBy.isEmpty()) row.put("defaultOrderBy", orderBy)
            }
        }
        List<Map<String, Object>> findFields = new ArrayList<>()
        for (MNode field : formNode.children("field")) {
            Map<String, Object> ff = findFieldMeta(field)
            if (ff != null) findFields.add(ff)
        }
        if (!findFields.isEmpty()) row.put("findFields", findFields)
    }

    static final Set<String> FIND_WIDGETS = new HashSet<>(
            Arrays.asList("drop-down", "text-find", "text-line", "date-period", "date-time", "range-find",
                    "check", "radio", "display-entity"))

    static Map<String, Object> findFieldMeta(MNode field) {
        if (field == null) return null
        String name = field.attribute("name")
        if (name == null || name.isEmpty() || "find".equals(name)) return null
        MNode header = field.first("header-field")
        if (header == null) return null
        MNode widget = null
        for (MNode ch : header.getChildren()) {
            if (ch == null) continue
            String wn = ch.getName()
            if ("submit".equals(wn)) return null
            if (FIND_WIDGETS.contains(wn)) { widget = ch; break }
        }
        if (widget == null) return null
        Map<String, Object> m = new LinkedHashMap<>()
        m.put("name", name)
        String widgetName = widget.getName()
        m.put("widget", widgetName)
        if ("drop-down".equals(widgetName) || "radio".equals(widgetName) || "check".equals(widgetName)) {
            List<String> options = new ArrayList<>()
            for (MNode opt : widget.children("option")) {
                String key = opt.attribute("key")
                if (key != null && !key.isEmpty()) options.add(key)
            }
            if (!options.isEmpty()) m.put("options", options)
        } else if ("date-period".equals(widgetName)) {
            m.put("params", Arrays.asList(name + "_period", name + "_poffset", name + "_pdate",
                    name + "_from", name + "_thru"))
        }
        return m
    }

    static Map<String, Object> formForTransition(List<Map<String, Object>> forms, String transitionName) {
        if (forms == null || transitionName == null || transitionName.isEmpty()) return null
        Map<String, Object> listHit = null
        for (Map<String, Object> form : forms) {
            if (form == null) continue
            if (!transitionName.equals(form.get("transition"))) continue
            if ("form-single".equals(form.get("type"))) return form
            if (listHit == null) listHit = form
        }
        return listHit
    }

    static void addFormSearchTexts(List<String> texts, List<Map<String, Object>> forms) {
        if (texts == null || forms == null) return
        for (Map<String, Object> form : forms) {
            if (form == null) continue
            Object n = form.get("name")
            if (n != null) texts.add(n.toString())
            Object t = form.get("transition")
            if (t != null) texts.add(t.toString())
            Object en = form.get("entityName")
            if (en != null) texts.add(en.toString())
            Object fields = form.get("fields")
            if (fields instanceof List) {
                for (Object fn : (List) fields) if (fn != null) texts.add(fn.toString())
            }
            addTitleTexts(texts, form.get("fieldTitles"))
        }
    }
    static void addTitleTexts(List<String> texts, Object titles) {
        if (texts == null || !(titles instanceof List)) return
        for (Object t : (List) titles) if (t != null) texts.add(t.toString())
    }

    Map<String, Object> browseRest(ExecutionContext ec, List<String> rest, Pattern matchPat, int depth, boolean detail) {
        ExecutionContextImpl eci = asEci(ec)
        if (eci == null) return error("ExecutionContextImpl is required")
        if (rest.isEmpty()) {
            List<Map<String, Object>> children = new ArrayList<>()
            addChild(children, matchPat, "s1", "rest", "/rest/s1", "Service REST")
            addChild(children, matchPat, "e1", "rest", "/rest/e1", "Entity REST")
            addChild(children, matchPat, "m1", "rest", "/rest/m1", "Master/entity REST")
            return listing("/rest", "rest", children)
        }
        String kind = rest.get(0)
        if ("e1".equals(kind) || "m1".equals(kind)) {
            String httpPrefix = "/rest/" + kind
            List<String> restTail = rest.size() > 1 ? new ArrayList<String>(rest.subList(1, rest.size())) : new ArrayList<String>()
            return browseEntityPackages(eci, restTail, matchPat, depth, detail, httpPrefix, "/rest/" + kind)
        }
        if (!"s1".equals(kind)) return error("unknown REST root: " + kind)
        RestApi api = eci.serviceFacade.restApi
        if (api == null) return error("REST API not available")
        if (rest.size() == 1) {
            List<Map<String, Object>> children = new ArrayList<>()
            boolean truncated = false
            for (ResourceNode rn : api.getFreshRootResources()) {
                if (rn == null) continue
                String artifact = "/" + rn.name
                if (!permitted(eci, artifact, ArtifactExecutionInfo.AT_REST_PATH)) continue
                if (!matches(matchPat, rn.name, rn.displayName)) continue
                if (children.size() >= MAX_CHILDREN) { truncated = true; break }
                Map<String, Object> row = child(rn.name, "resource", "/rest/s1/" + rn.name,
                        rn.displayName ?: rn.name)
                row.put("methods", new ArrayList<>(rn.methodMap.keySet()))
                children.add(row)
            }
            Map<String, Object> out = listing("/rest/s1", "rest", children)
            out.put("truncated", truncated)
            return out
        }
        ResourceNode node = api.getRootResourceNode(rest.get(1))
        if (node == null) return error("REST root not found: " + rest.get(1))
        PathNode cur = node
        StringBuilder pathB = new StringBuilder("/rest/s1/").append(rest.get(1))
        for (int i = 2; i < rest.size(); i++) {
            String seg = rest.get(i)
            ResourceNode childRn = cur.resourceMap.get(seg)
            if (childRn == null) return error("not found: " + pathB.toString() + "/" + seg)
            cur = childRn
            pathB.append('/').append(seg)
        }
        String artifact = restPathArtifact(cur)
        if (artifact != null && !permitted(eci, artifact, ArtifactExecutionInfo.AT_REST_PATH))
            return errorStatus(403, "not authorized")
        List<Map<String, Object>> children = new ArrayList<>()
        boolean truncated = collectRestChildren(eci, cur, pathB.toString(), children, matchPat, depth, 1)
        Map<String, Object> out = listing(pathB.toString(), "rest", children)
        out.put("truncated", truncated)
        if (detail) out.put("leaf", restDetail(cur))
        return out
    }

    boolean collectRestChildren(ExecutionContextImpl eci, PathNode node, String path,
            List<Map<String, Object>> children, Pattern matchPat, int depth, int level) {
        boolean truncated = false
        for (ResourceNode rn : node.resourceMap.values()) {
            if (rn == null) continue
            String artifact = restPathArtifact(rn)
            if (artifact != null && !permitted(eci, artifact, ArtifactExecutionInfo.AT_REST_PATH)) continue
            String childPath = path + "/" + rn.name
            if (matches(matchPat, rn.name, rn.displayName)) {
                if (children.size() >= MAX_CHILDREN) { truncated = true; break }
                Map<String, Object> row = child(rn.name, "resource", childPath, rn.displayName ?: rn.name)
                row.put("methods", new ArrayList<>(rn.methodMap.keySet()))
                children.add(row)
            }
            if (depth > level && !truncated)
                truncated = collectRestChildren(eci, rn, childPath, children, matchPat, depth, level + 1) || truncated
        }
        if (level == 1 && node.idNode != null && children.size() < MAX_CHILDREN) {
            Map<String, Object> idRow = child("{" + node.idNode.name + "}", "id",
                    path + "/{" + node.idNode.name + "}", "Path id " + node.idNode.name)
            if (matches(matchPat, node.idNode.name, null)) children.add(idRow)
        }
        return truncated
    }

    static String restPathArtifact(PathNode node) {
        if (node == null || node.fullPathList == null || node.fullPathList.isEmpty()) return null
        StringBuilder sb = new StringBuilder()
        for (String p : node.fullPathList) sb.append('/').append(p)
        return sb.toString()
    }
    static Map<String, Object> restDetail(PathNode node) {
        Map<String, Object> leaf = new LinkedHashMap<>()
        leaf.put("name", node.name)
        leaf.put("displayName", node.displayName)
        leaf.put("description", node.description)
        leaf.put("methods", new ArrayList<>(node.methodMap.keySet()))
        if (node.idNode != null) leaf.put("idName", node.idNode.name)
        return leaf
    }

    Map<String, Object> browseServices(ExecutionContext ec, List<String> rest, Pattern matchPat,
            int depth, boolean detail) {
        ExecutionContextImpl eci = asEci(ec)
        if (eci == null) return error("ExecutionContextImpl is required")
        ServiceFacadeImpl sfi = eci.serviceFacade
        Set<String> names = sfi.getKnownServiceNames()
        String prefix = rest.isEmpty() ? "" : String.join(".", rest)
        List<Map<String, Object>> children = new ArrayList<>()
        boolean truncated = false
        Set<String> seen = new LinkedHashSet<>()
        String leafService = null
        int showDepth = depth < 1 ? 1 : depth
        for (String sn : names) {
            if (sn == null) continue
            if (prefix.length() > 0) {
                if (sn.equals(prefix)) { leafService = sn; continue }
                if (!sn.startsWith(prefix + ".")) continue
            }
            if (!matches(matchPat, sn)) {
                boolean paramHit = false
                for (String pn : serviceInParams(sfi, sn)) {
                    if (matches(matchPat, pn)) { paramHit = true; break }
                }
                if (!paramHit) continue
            }
            String restName = prefix.length() == 0 ? sn : sn.substring(prefix.length() + 1)
            String[] parts = restName.split("\\.", -1)
            boolean isLeaf = parts.length <= showDepth
            String childName
            if (isLeaf) childName = restName
            else {
                StringBuilder cut = new StringBuilder()
                for (int i = 0; i < showDepth; i++) {
                    if (i > 0) cut.append('.')
                    cut.append(parts[i])
                }
                childName = cut.toString()
            }
            if (seen.contains(childName)) continue
            seen.add(childName)
            if (children.size() >= MAX_CHILDREN) { truncated = true; break }
            String childFull = prefix.length() == 0 ? childName : prefix + "." + childName
            String childPath = "/services/" + childFull.replace('.', '/')
            Map<String, Object> row = child(childName, isLeaf ? "service" : "package", childPath, isLeaf ? sn : childFull)
            if (isLeaf) row.put("serviceName", sn)
            children.add(row)
        }
        String listPath = prefix.length() == 0 ? "/services" : "/services/" + prefix.replace('.', '/')
        Map<String, Object> out = listing(listPath, leafService != null ? "service" : "services", children)
        out.put("truncated", truncated)
        if (detail && leafService != null) out.put("leaf", serviceDetail(sfi, leafService))
        else if (detail && children.size() == 1 && "service".equals(children.get(0).get("kind")))
            out.put("leaf", serviceDetail(sfi, (String) children.get(0).get("serviceName")))
        return out
    }

    static Map<String, Object> serviceDetail(ServiceFacadeImpl sfi, String serviceName) {
        Map<String, Object> leaf = new LinkedHashMap<>()
        leaf.put("serviceName", serviceName)
        try {
            if (!sfi.isServiceDefined(serviceName)) return leaf
            ServiceDefinition sd = sfi.getServiceDefinition(serviceName)
            if (sd == null) return leaf
            leaf.put("inParameters", new ArrayList<>(sd.getInParameterNames()))
            leaf.put("outParameters", new ArrayList<>(sd.getOutParameterNames()))
        } catch (Throwable ignored) { }
        return leaf
    }

    Map<String, Object> browseEntities(ExecutionContext ec, List<String> rest, Pattern matchPat,
            int depth, boolean detail) {
        ExecutionContextImpl eci = asEci(ec)
        if (eci == null) return error("ExecutionContextImpl is required")
        return browseEntityPackages(eci, rest, matchPat, depth, detail, "/rest/e1", "/entities")
    }

    Map<String, Object> browseEntityPackages(ExecutionContextImpl eci, List<String> rest, Pattern matchPat,
            int depth, boolean detail, String httpPrefix, String catalogPrefix) {
        EntityFacadeImpl efi = eci.entityFacade
        Set<String> names = efi.getAllEntityNames()
        String prefix = rest.isEmpty() ? "" : String.join(".", rest)
        List<Map<String, Object>> children = new ArrayList<>()
        boolean truncated = false
        Set<String> seen = new LinkedHashSet<>()
        String leafEntity = null
        for (String en : names) {
            if (en == null) continue
            if (prefix.length() > 0) {
                if (en.equals(prefix)) { leafEntity = en; continue }
                if (!en.startsWith(prefix + ".")) continue
            }
            String restName = prefix.length() == 0 ? en : en.substring(prefix.length() + 1)
            int dot = restName.indexOf('.')
            String childName = (depth <= 1 && dot >= 0) ? restName.substring(0, dot) : restName
            boolean isLeaf = dot < 0 || depth > 1
            if (depth <= 1) isLeaf = dot < 0
            if (depth <= 1 && seen.contains(childName)) continue
            if (depth <= 1) seen.add(childName)
            if (isLeaf && !permitted(eci, en, ArtifactExecutionInfo.AT_ENTITY)) continue
            if (isLeaf) {
                if (!entityMatches(matchPat, childName, en, efi, prefix)) continue
            } else if (!matches(matchPat, childName, en)) {
                continue
            }
            if (children.size() >= MAX_CHILDREN) { truncated = true; break }
            String childPath = catalogPrefix + "/" + (prefix.length() == 0 ? childName : prefix + "." + childName).replace('.', '/')
            Map<String, Object> row = child(childName, isLeaf ? "entity" : "package", childPath, en)
            if (isLeaf) {
                row.put("entityName", en)
                row.put("createService", "create#" + en)
                row.put("httpPath", httpPrefix + "/" + en)
            }
            children.add(row)
        }
        String listPath = prefix.length() == 0 ? catalogPrefix : catalogPrefix + "/" + prefix.replace('.', '/')
        Map<String, Object> out = listing(listPath, leafEntity != null ? "entity" : "entities", children)
        out.put("truncated", truncated)
        if (detail && leafEntity != null) out.put("leaf", entityDetail(efi, leafEntity, httpPrefix))
        return out
    }

    static Map<String, Object> entityDetail(EntityFacadeImpl efi, String entityName, String httpPrefix) {
        Map<String, Object> leaf = new LinkedHashMap<>()
        leaf.put("entityName", entityName)
        leaf.put("createService", "create#" + entityName)
        leaf.put("updateService", "update#" + entityName)
        leaf.put("deleteService", "delete#" + entityName)
        leaf.put("httpPath", httpPrefix + "/" + entityName)
        try {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (ed == null) return leaf
            leaf.put("fields", new ArrayList<>(ed.getAllFieldNames()))
            leaf.put("pkFields", new ArrayList<>(ed.getPkFieldNames()))
            leaf.put("isView", ed.isViewEntity)
        } catch (Throwable ignored) { }
        return leaf
    }

    static boolean entityMatches(Pattern matchPat, String childName, String entityName,
            EntityFacadeImpl efi, String prefix) {
        if (matches(matchPat, childName, entityName, "create#" + entityName, "create#" + childName,
                "update#" + entityName, "delete#" + entityName)) return true
        if (matchPat == null || prefix == null || prefix.isEmpty() || efi == null) return false
        try {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (ed == null) return false
            for (String fn : ed.getAllFieldNames()) {
                if (matches(matchPat, fn)) return true
            }
        } catch (Throwable ignored) { }
        return false
    }

    static ScreenDefinition getAppsScreen(ExecutionContextImpl eci) {
        ScreenFacadeImpl sfi = eci.screenFacade
        List<String> roots = sfi.getAllRootScreenLocations()
        for (String loc : roots) {
            ScreenDefinition root = sfi.getScreenDefinition(loc)
            if (root == null) continue
            SubscreensItem apps = root.getSubscreensItem("apps")
            if (apps == null) continue
            ScreenDefinition appsSd = sfi.getScreenDefinition(apps.location)
            if (appsSd != null) return appsSd
        }
        return null
    }

    static boolean permitted(ExecutionContextImpl eci, String name, ArtifactExecutionInfo.ArtifactType type) {
        if (eci == null || name == null || name.isEmpty()) return false
        try {
            ArtifactExecutionInfoImpl aeii = new ArtifactExecutionInfoImpl(name, type,
                    ArtifactExecutionInfo.AUTHZA_VIEW, "")
            return eci.artifactExecutionFacade.isPermitted(aeii, null, true, false, false, null)
        } catch (ArtifactAuthorizationException ignored) {
            return false
        } catch (Throwable t) {
            logger.debug("browse authz check failed for ${name}: ${t.message}")
            return false
        }
    }

    static ExecutionContextImpl asEci(ExecutionContext ec) {
        return ec instanceof ExecutionContextImpl ? (ExecutionContextImpl) ec : null
    }
    /** Vue shells /qapps and /vapps wrap the same tree as /apps; form-list JSON is under /apps. */
    static String requestScreenPath(String catalogPath) {
        if (catalogPath == null || catalogPath.isEmpty()) return catalogPath
        if (catalogPath.startsWith("/qapps/") || catalogPath.equals("/qapps"))
            return "/apps" + catalogPath.substring("/qapps".length())
        if (catalogPath.startsWith("/vapps/") || catalogPath.equals("/vapps"))
            return "/apps" + catalogPath.substring("/vapps".length())
        return catalogPath
    }
    static String normalizePath(String path) {
        String t = path.trim()
        if (!t.startsWith("/")) t = "/" + t
        while (t.endsWith("/") && t.length() > 1) t = t.substring(0, t.length() - 1)
        return t
    }
    static List<String> splitPath(String path) {
        List<String> segs = new ArrayList<>()
        for (String raw : path.split("/")) {
            if (raw != null && !raw.isEmpty()) segs.add(raw)
        }
        return segs
    }
    static boolean matches(Pattern pat, String... texts) {
        if (pat == null) return true
        if (texts == null) return false
        for (String t : texts) {
            if (t != null && t.length() > 0 && pat.matcher(t).find()) return true
        }
        return false
    }
    static boolean matchesAny(Pattern pat, List<String> texts) {
        if (pat == null) return true
        if (texts == null || texts.isEmpty()) return false
        for (String t : texts) if (matches(pat, t)) return true
        return false
    }
    static void addChild(List<Map<String, Object>> children, Pattern matchPat,
            String name, String kind, String path, String title) {
        if (!matches(matchPat, name, title)) return
        children.add(child(name, kind, path, title))
    }
    static Map<String, Object> child(String name, String kind, String path, String title) {
        Map<String, Object> m = new LinkedHashMap<>()
        m.put("name", name)
        m.put("kind", kind)
        m.put("path", path)
        if (title != null) m.put("title", title)
        return m
    }
    static Map<String, Object> listing(String path, String kind, List<Map<String, Object>> children) {
        Map<String, Object> m = new LinkedHashMap<>()
        m.put("path", path)
        m.put("kind", kind)
        m.put("children", children)
        m.put("truncated", false)
        return m
    }
    static String fieldTitleText(MNode field) {
        if (field == null) return null
        String title = plainTitle(field.attribute("title"))
        if (title != null) return title
        MNode header = field.first("header-field")
        if (header != null) {
            title = plainTitle(header.attribute("title"))
            if (title != null) return title
        }
        MNode defField = field.first("default-field")
        if (defField != null) title = plainTitle(defField.attribute("title"))
        return title
    }
    static String plainTitle(String title) {
        if (title == null) return null
        String t = title.trim()
        if (t.isEmpty() || t.contains("\${") || t.indexOf('<') >= 0) return null
        return t
    }
    /** No transition, a read-only transition, or a redirect that does not run a service or actions. */
    static boolean isNavigationOnly(TransitionItem ti) {
        if (ti == null) return true
        if (ti.readOnly) return true
        return !ti.hasActionsOrSingleService()
    }

    static ScreenDefinition webrootWithApps(ExecutionContextImpl eci) {
        if (eci == null) return null
        ScreenFacadeImpl sfi = eci.screenFacade
        List<String> roots = sfi.getAllRootScreenLocations()
        for (String loc : roots) {
            ScreenDefinition root = sfi.getScreenDefinition(loc)
            if (root != null && root.getSubscreensItem("apps") != null) return root
        }
        return null
    }
    /** Catalog path /qapps/a/b or /apps/a/b becomes the webroot path list [apps, a, b]. */
    static ArrayList<String> appsPathList(String catalogPath) {
        ArrayList<String> out = new ArrayList<>()
        List<String> segs = splitPath(catalogPath == null ? "" : catalogPath)
        if (segs.isEmpty()) { out.add("apps"); return out }
        String root = segs.get(0)
        if (!"qapps".equals(root) && !"apps".equals(root) && !"vapps".equals(root)) return null
        out.add("apps")
        for (int i = 1; i < segs.size(); i++) out.add(segs.get(i))
        return out
    }
    static ScreenUrlInfo screenUrl(ExecutionContextImpl eci, String catalogPath) {
        ArrayList<String> path = appsPathList(catalogPath)
        if (path == null || eci == null) return null
        ScreenDefinition root = webrootWithApps(eci)
        if (root == null) return null
        try {
            return ScreenUrlInfo.getScreenUrlInfo(eci.screenFacade, root, root, path, null, 0)
        } catch (Throwable t) {
            logger.debug("browse screen url failed for ${catalogPath}: ${t.message}")
            return null
        }
    }
    static boolean screenViewPermitted(ExecutionContextImpl eci, String catalogPath) {
        ScreenUrlInfo sui = screenUrl(eci, catalogPath)
        if (sui == null || !sui.targetExists) return false
        try {
            return sui.isPermitted(eci, (TransitionItem) null)
        } catch (Throwable t) {
            logger.debug("browse screen authz failed for ${catalogPath}: ${t.message}")
            return false
        }
    }
    static boolean transitionPermitted(ExecutionContextImpl eci, String screenCatalogPath, TransitionItem ti) {
        if (ti == null || eci == null) return false
        ScreenUrlInfo sui = screenUrl(eci, screenCatalogPath)
        if (sui == null || !sui.targetExists) return false
        try {
            return sui.isPermitted(eci, ti)
        } catch (Throwable t) {
            logger.debug("browse transition authz failed for ${screenCatalogPath}/${ti.name}: ${t.message}")
            return false
        }
    }

    Map<String, Object> searchScreens(ExecutionContext ec, String q) {
        ExecutionContextImpl eci = asEci(ec)
        if (eci == null) return error("ExecutionContextImpl is required")
        List<String> tokens = queryTokens(q)
        if (tokens.isEmpty()) return error("q has no words")
        List<Map<String, Object>> index = screenIndex(eci)
        String phrase = String.join(" ", tokens)
        List<Map<String, Object>> ranked = new ArrayList<>()
        for (Map<String, Object> row : index) {
            if (row == null) continue
            String groupId = str(row.get("userGroupId"))
            if (groupId != null && !groupId.isEmpty() && eci.getUser() != null &&
                    !eci.getUser().getUserGroupIdSet().contains(groupId)) continue
            int score = scoreScreen(row, tokens, phrase)
            if (score <= 0) continue
            Map<String, Object> copy = new LinkedHashMap<>(row)
            copy.put("_score", Integer.valueOf(score))
            ranked.add(copy)
        }
        Collections.sort(ranked, new Comparator<Map<String, Object>>() {
            @Override int compare(Map<String, Object> a, Map<String, Object> b) {
                int sa = ((Number) a.get("_score")).intValue()
                int sb = ((Number) b.get("_score")).intValue()
                if (sa != sb) return sb - sa
                boolean am = Boolean.TRUE.equals(a.get("inMenu"))
                boolean bm = Boolean.TRUE.equals(b.get("inMenu"))
                if (am != bm) return am ? -1 : 1
                String pa = String.valueOf(a.get("path"))
                String pb = String.valueOf(b.get("path"))
                if (pa.length() != pb.length()) return pa.length() - pb.length()
                return pa.compareTo(pb)
            }
        })
        List<Map<String, Object>> hits = new ArrayList<>()
        boolean truncated = false
        for (Map<String, Object> row : ranked) {
            if (hits.size() >= MAX_HITS) { truncated = true; break }
            String path = str(row.get("path"))
            if (!screenViewPermitted(eci, path)) continue
            hits.add(searchHit(row))
        }
        Map<String, Object> out = new LinkedHashMap<>()
        out.put("path", "/")
        out.put("kind", "search")
        out.put("query", q)
        out.put("hits", hits)
        out.put("truncated", truncated)
        return out
    }
    /** Search card. Ranking still uses fields in the index; the tool result does not. */
    static Map<String, Object> searchHit(Map<String, Object> row) {
        Map<String, Object> hit = new LinkedHashMap<>()
        hit.put("path", str(row.get("path")))
        hit.put("name", row.get("name"))
        hit.put("title", row.get("title"))
        hit.put("inMenu", row.get("inMenu"))
        List<Map<String, Object>> formHits = new ArrayList<>()
        Object forms = row.get("forms")
        if (forms instanceof List) {
            for (Object f : (List) forms) {
                if (!(f instanceof Map)) continue
                Map fm = (Map) f
                Map<String, Object> one = new LinkedHashMap<>()
                copyIfPresent(fm, one, "name")
                copyIfPresent(fm, one, "type")
                if (!one.isEmpty()) formHits.add(one)
            }
        }
        if (!formHits.isEmpty()) hit.put("forms", formHits)
        return hit
    }
    static int scoreScreen(Map<String, Object> row, List<String> tokens, String phrase) {
        String title = str(row.get("title"))
        String name = str(row.get("name"))
        String titleL = title == null ? "" : title.toLowerCase(Locale.ROOT)
        String nameL = name == null ? "" : name.toLowerCase(Locale.ROOT)
        int score = 0
        int titleHits = 0
        for (String tok : tokens) {
            boolean hit = false
            if (!titleL.isEmpty() && titleL.contains(tok)) { score += 80; titleHits++; hit = true }
            if (!nameL.isEmpty() && nameL.contains(tok)) { score += 40; hit = true }
            if (listHas(row.get("fieldTitles"), tok)) { score += 25; hit = true }
            if (listHas(row.get("fieldNames"), tok)) { score += 15; hit = true }
            if (listHas(row.get("services"), tok)) { score += 10; hit = true }
            if (listHas(row.get("parameters"), tok)) { score += 8; hit = true }
            if (!hit) return 0
        }
        if (phrase != null && !phrase.isEmpty() && titleL.contains(phrase)) score += 200
        boolean strongTitle = titleHits == tokens.size() && (tokens.size() == 1 || titleL.contains(phrase))
        if (!Boolean.TRUE.equals(row.get("inMenu")) && !strongTitle) score = score >> 1
        return score
    }
    static boolean listHas(Object list, String token) {
        if (!(list instanceof List) || token == null) return false
        for (Object o : (List) list) {
            if (o == null) continue
            if (o.toString().toLowerCase(Locale.ROOT).contains(token)) return true
        }
        return false
    }
    static List<String> queryTokens(String q) {
        List<String> out = new ArrayList<>()
        if (q == null) return out
        for (String raw : q.toLowerCase(Locale.ROOT).split("[^a-z0-9#]+")) {
            if (raw != null && !raw.isEmpty()) out.add(raw)
        }
        return out
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> screenIndex(ExecutionContextImpl eci) {
        ScreenDefinition apps = getAppsScreen(eci)
        if (apps == null) return Collections.emptyList()
        int key = System.identityHashCode(eci.ecfi)
        String stamp = treeStamp(eci, apps)
        Map<String, Object> cached = SCREEN_INDEX.get(Integer.valueOf(key))
        if (cached != null && stamp.equals(cached.get("stamp"))) return (List<Map<String, Object>>) cached.get("screens")
        synchronized (SCREEN_INDEX) {
            cached = SCREEN_INDEX.get(Integer.valueOf(key))
            if (cached != null && stamp.equals(cached.get("stamp"))) return (List<Map<String, Object>>) cached.get("screens")
            List<Map<String, Object>> screens = new ArrayList<>()
            indexWalk(eci, apps, new ArrayList<String>(), new HashSet<String>(), screens)
            Map<String, Object> box = new LinkedHashMap<>()
            box.put("stamp", stamp)
            box.put("screens", screens)
            SCREEN_INDEX.put(Integer.valueOf(key), box)
            return screens
        }
    }
    static String treeStamp(ExecutionContextImpl eci, ScreenDefinition apps) {
        StringBuilder sb = new StringBuilder()
        stampWalk(eci, apps, new ArrayList<String>(), new HashSet<String>(), sb)
        return sb.toString()
    }
    static void stampWalk(ExecutionContextImpl eci, ScreenDefinition sd, List<String> rel,
            Set<String> ancestors, StringBuilder sb) {
        if (sd == null || sd.location == null) return
        if (!ancestors.add(sd.location)) return
        try {
            sb.append(sd.location).append('@').append(sd.screenLoadedTime).append('|')
            ArrayList<SubscreensItem> items = sd.getSubscreensItemsSorted()
            int n = items != null ? items.size() : 0
            for (int i = 0; i < n; i++) {
                SubscreensItem si = (SubscreensItem) items.get(i)
                if (si == null || si.location == null || si.name == null) continue
                ScreenDefinition child = null
                try { child = eci.screenFacade.getScreenDefinition(si.location) } catch (Throwable ignored) { }
                if (child == null) continue
                rel.add(si.name)
                stampWalk(eci, child, rel, ancestors, sb)
                rel.remove(rel.size() - 1)
            }
        } finally {
            ancestors.remove(sd.location)
        }
    }
    void indexWalk(ExecutionContextImpl eci, ScreenDefinition sd, List<String> rel,
            Set<String> ancestors, List<Map<String, Object>> screens) {
        if (sd == null || sd.location == null) return
        if (!ancestors.add(sd.location)) return
        try {
            ArrayList<SubscreensItem> items = sd.getSubscreensItemsSorted()
            int n = items != null ? items.size() : 0
            ServiceFacadeImpl sfi = eci.serviceFacade
            for (int i = 0; i < n; i++) {
                SubscreensItem si = (SubscreensItem) items.get(i)
                if (si == null || si.location == null || si.name == null) continue
                ScreenDefinition child = null
                try { child = eci.screenFacade.getScreenDefinition(si.location) } catch (Throwable ignored) { }
                if (child == null) continue
                rel.add(si.name)
                StringBuilder pathB = new StringBuilder("/qapps")
                for (String seg : rel) pathB.append('/').append(seg)
                String path = pathB.toString()
                Map<String, Object> row = new LinkedHashMap<>()
                row.put("path", path)
                row.put("name", si.name)
                String title = si.menuTitle
                if (title == null || title.isEmpty()) title = child.defaultMenuName
                if (title == null || title.isEmpty()) title = si.name
                row.put("title", title)
                row.put("inMenu", Boolean.valueOf(si.menuInclude))
                if (si.userGroupId != null && !si.userGroupId.isEmpty()) row.put("userGroupId", si.userGroupId)
                List<String> params = parameterNames(child.getParameterMap())
                if (!params.isEmpty()) row.put("parameters", params)
                List<Map<String, Object>> forms = screenForms(child, path)
                if (!forms.isEmpty()) row.put("forms", forms)
                List<String> fieldNames = new ArrayList<>()
                List<String> fieldTitles = new ArrayList<>()
                for (Map<String, Object> form : forms) {
                    Object fns = form.get("fields")
                    if (fns instanceof List) for (Object fn : (List) fns) if (fn != null) fieldNames.add(fn.toString())
                    Object fts = form.get("fieldTitles")
                    if (fts instanceof List) for (Object ft : (List) fts) if (ft != null) fieldTitles.add(ft.toString())
                }
                if (!fieldNames.isEmpty()) row.put("fieldNames", fieldNames)
                if (!fieldTitles.isEmpty()) row.put("fieldTitles", fieldTitles)
                List<Map<String, Object>> trans = new ArrayList<>()
                Map<String, TransitionItem> tiByName = new LinkedHashMap<>()
                List<String> services = new ArrayList<>()
                for (TransitionItem ti : child.getAllTransitions()) {
                    if (ti == null || SKIP_TRANSITIONS.contains(ti.name)) continue
                    tiByName.put(ti.name, ti)
                    Map<String, Object> one = new LinkedHashMap<>()
                    one.put("name", ti.name)
                    one.put("readOnly", Boolean.valueOf(ti.readOnly))
                    if (ti.singleServiceName != null && !ti.singleServiceName.isEmpty()) {
                        one.put("serviceName", ti.singleServiceName)
                        services.add(ti.singleServiceName)
                    }
                    trans.add(one)
                }
                if (!trans.isEmpty()) row.put("transitions", trans)
                if (!tiByName.isEmpty()) row.put("_transitionItems", tiByName)
                if (!services.isEmpty()) row.put("services", services)
                screens.add(row)
                indexWalk(eci, child, rel, ancestors, screens)
                rel.remove(rel.size() - 1)
            }
        } finally {
            ancestors.remove(sd.location)
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>()
        m.put("error", message)
        return m
    }
    static Map<String, Object> errorStatus(int status, String message) {
        Map<String, Object> m = error(message)
        m.put("status", status)
        return m
    }
    static String str(Object o) { return o == null ? null : o.toString() }
    static int intVal(Object o, int defVal) {
        if (o instanceof Number) return ((Number) o).intValue()
        if (o == null) return defVal
        try { return Integer.parseInt(o.toString().trim()) } catch (Exception ignored) { return defVal }
    }
    static boolean boolVal(Object o) {
        if (o instanceof Boolean) return (Boolean) o
        if (o == null) return false
        return "true".equalsIgnoreCase(o.toString())
    }
}
