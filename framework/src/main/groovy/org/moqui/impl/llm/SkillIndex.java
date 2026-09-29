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

import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityConditionFactory;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.FtsSql;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.resource.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shipped component://…/skill/*.md plus admitted LlmSkill rows. See framework/plans/LlmSkillLearning.md.
 */
public class SkillIndex {
    private static final Logger logger = LoggerFactory.getLogger(SkillIndex.class);
    public static final int DEFAULT_LIMIT = 5;
    public static final int INJECT_CHARS = 4000;

    public static class SkillDoc {
        public String name, title, description, body, risk, sourceLocation, skillId, statusId, provenanceId;
        public Map<String, String> frontMatter = new LinkedHashMap<>();
    }

    public static SkillDoc parseMarkdown(String text, String sourceLocation) {
        SkillDoc doc = new SkillDoc();
        doc.sourceLocation = sourceLocation;
        if (text == null) { doc.body = ""; return doc; }
        String t = text.replace("\r\n", "\n");
        if (t.startsWith("---\n")) {
            int end = t.indexOf("\n---", 4);
            if (end > 0) {
                String fm = t.substring(4, end);
                doc.body = t.substring(end + 4).trim();
                for (String line : fm.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
                    if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
                        v = v.substring(1, v.length() - 1);
                    doc.frontMatter.put(k, v);
                }
            } else {
                doc.body = t;
            }
        } else {
            doc.body = t;
        }
        doc.name = nz(doc.frontMatter.get("name"));
        if (doc.name.isEmpty() && sourceLocation != null) {
            int slash = sourceLocation.lastIndexOf('/');
            String file = slash >= 0 ? sourceLocation.substring(slash + 1) : sourceLocation;
            if (file.endsWith(".md")) file = file.substring(0, file.length() - 3);
            doc.name = file;
        }
        doc.title = nz(doc.frontMatter.get("title"));
        if (doc.title.isEmpty()) doc.title = doc.name;
        doc.description = nz(doc.frontMatter.get("description"));
        doc.risk = nz(doc.frontMatter.get("risk"));
        if (doc.risk.isEmpty()) doc.risk = "confirm";
        doc.statusId = "LsksActive";
        doc.provenanceId = "LskpHuman";
        return doc;
    }

    public static List<SkillDoc> scanShipped(ExecutionContext ec) {
        List<SkillDoc> out = new ArrayList<>();
        if (ec == null || ec.getFactory() == null) return out;
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ec.getFactory();
        Map<String, String> comps = ecfi.getComponentBaseLocations();
        for (Map.Entry<String, String> e : comps.entrySet()) {
            String loc = e.getValue();
            if (loc == null) continue;
            String skillDir = loc.endsWith("/") ? loc + "skill" : loc + "/skill";
            try {
                ResourceReference dir = ec.getResource().getLocationReference(skillDir);
                if (dir == null || !dir.getExists() || !dir.isDirectory()) continue;
                for (ResourceReference child : dir.getDirectoryEntries()) {
                    if (child == null) continue;
                    String name = child.getFileName();
                    if (name == null || !name.endsWith(".md")) continue;
                    String text = child.getText();
                    SkillDoc doc = parseMarkdown(text, child.getLocation());
                    if (doc.name != null && !doc.name.isEmpty()) out.add(doc);
                }
            } catch (Throwable t) {
                if (logger.isDebugEnabled()) logger.debug("Skill scan skipped for " + skillDir + ": " + t.getMessage());
            }
        }
        return out;
    }

    /**
     * Exact name lookup for find_skill select.
     * A shipped file or an active human/world row wins over a proposed, sim, infer, or mixed row
     * with the same name. A proposed row is selectable when nothing stronger has that name.
     * Superseded / rejected / deprecated rows are not selectable.
     */
    public static SkillDoc getByName(ExecutionContext ec, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        SkillDoc shipped = shippedByName(ec, n);
        SkillDoc entity = entityByName(ec, n);
        return prefer(shipped, entity);
    }

    /**
     * Search for inject and find_skill query. Active human/world rows and shipped files only,
     * plus an active row with no shipped file of that name. Proposed rows are not listed;
     * {@link #getByName} still returns one when select asks for it and the name is not reserved.
     */
    public static List<SkillDoc> retrieve(ExecutionContext ec, String query, int limit) {
        if (limit <= 0) limit = DEFAULT_LIMIT;
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<SkillDoc> shipped = scanShipped(ec);
        java.util.Set<String> shippedNames = new java.util.HashSet<>();
        for (SkillDoc doc : shipped) if (doc.name != null) shippedNames.add(doc.name);
        List<Scored> scored = new ArrayList<>();
        for (SkillDoc doc : shipped) {
            int s = score(doc, q);
            if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc, true));
        }
        if (ec != null && ec.getEntity() != null) {
            try {
                EntityList rows = withAuthzDisabled(ec, () -> activeRows(ec, q));
                int n = rows == null ? 0 : rows.size();
                for (int i = 0; i < n; i++) {
                    SkillDoc doc = fromEntity(rows.get(i));
                    if (doc.name != null && shippedNames.contains(doc.name) && !isHumanOrWorld(doc)) continue;
                    int s = score(doc, q);
                    if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc, false));
                }
            } catch (Throwable t) {
                logger.warn("LlmSkill retrieve: {} {}", t.getMessage(),
                        t.getCause() != null ? t.getCause().toString() : "");
            }
        }
        scored.sort((a, b) -> {
            int c = Integer.compare(b.score, a.score);
            if (c != 0) return c;
            if (a.shipped == b.shipped) return 0;
            return a.shipped ? 1 : -1;
        });
        List<SkillDoc> out = new ArrayList<>();
        Map<String, Integer> seenAt = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (s.doc.name == null) continue;
            Integer at = seenAt.get(s.doc.name);
            if (at == null) {
                seenAt.put(s.doc.name, out.size());
                out.add(s.doc);
            } else if (!s.shipped && isHumanOrWorld(s.doc) && !isHumanOrWorld(out.get(at))) {
                out.set(at, s.doc);
            }
        }
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    /** Shipped file or active human/world row already owns this name. Proposed sim rows must not take it. */
    public static boolean nameReserved(ExecutionContext ec, String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.trim();
        if (shippedByName(ec, n) != null) return true;
        SkillDoc entity = entityByName(ec, n);
        return entity != null && "LsksActive".equals(entity.statusId) && isHumanOrWorld(entity);
    }

    static boolean isHumanOrWorld(SkillDoc doc) {
        if (doc == null) return false;
        return "LskpHuman".equals(doc.provenanceId) || "LskpWorld".equals(doc.provenanceId);
    }

    private static SkillDoc prefer(SkillDoc shipped, SkillDoc entity) {
        if (entity == null) return shipped;
        if (shipped == null) return selectable(entity) ? entity : null;
        if ("LsksActive".equals(entity.statusId) && isHumanOrWorld(entity)) return entity;
        return shipped;
    }

    private static boolean selectable(SkillDoc doc) {
        return doc != null && ("LsksActive".equals(doc.statusId) || "LsksProposed".equals(doc.statusId));
    }

    private static SkillDoc shippedByName(ExecutionContext ec, String name) {
        for (SkillDoc doc : scanShipped(ec)) {
            if (name.equals(doc.name)) return doc;
        }
        return null;
    }

    private static SkillDoc entityByName(ExecutionContext ec, String name) {
        if (ec == null || ec.getEntity() == null) return null;
        try {
            EntityValue ev = withAuthzDisabled(ec, () -> ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", name).useCache(false).one());
            if (ev == null) return null;
            SkillDoc doc = fromEntity(ev);
            return selectable(doc) ? doc : null;
        } catch (Throwable t) {
            logger.warn("LlmSkill getByName: {}", t.getMessage());
            return null;
        }
    }

    /** True for concept cards such as marble-party-roles. They are not write playbooks. */
    public static boolean isReference(SkillDoc doc) {
        return doc != null && doc.name != null && doc.name.startsWith("marble-");
    }

    /** Widget lines are kept on the stored body and on the selected skill, not in catalog inject. */
    public static String withoutWidgets(String body) {
        if (body == null) return null;
        int i = widgetIndex(body);
        if (i < 0) return body;
        return body.substring(0, i).stripTrailing();
    }
    public static String widgetsSection(String body) {
        if (body == null) return null;
        int i = widgetIndex(body);
        if (i < 0) return null;
        String section = body.substring(i).trim();
        return section.isEmpty() ? null : section;
    }
    public static String activeWidgetText(ExecutionContext ec, String skillName) {
        if (skillName == null || skillName.isBlank() || ec == null) return null;
        SkillDoc doc = getByName(ec, skillName);
        if (doc == null) return null;
        return widgetsSection(doc.body);
    }
    private static int widgetIndex(String body) {
        int i = body.indexOf("\n## Widgets");
        if (i >= 0) return i + 1;
        if (body.startsWith("## Widgets")) return 0;
        return -1;
    }

    /**
     * Procedure skills fill the inject slots. Reference cards are a short gloss beside them,
     * not one of the three procedure slots.
     */
    public static String formatInjectForQuery(ExecutionContext ec, String query) {
        List<SkillDoc> found = retrieve(ec, query, 15);
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> references = new ArrayList<>();
        if (found != null) {
            for (SkillDoc d : found) {
                if (isReference(d)) {
                    if (references.size() >= 2) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("title", d.title);
                    m.put("description", d.description);
                    String body = withoutWidgets(d.body);
                    if (body == null) body = "";
                    else body = body.trim();
                    if (body.length() > 400) body = body.substring(0, 400);
                    m.put("body", body);
                    references.add(m);
                } else if (skills.size() < 3) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("title", d.title);
                    m.put("description", d.description);
                    m.put("risk", d.risk);
                    m.put("body", withoutWidgets(d.body));
                    m.put("lessons", lessonLines(ec, d.skillId));
                    skills.add(m);
                }
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skills", skills);
        ctx.put("references", references);
        String text = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SKILL_INJECT, ctx);
        if (text == null) return "";
        if (text.length() > INJECT_CHARS) return text.substring(0, INJECT_CHARS);
        return text;
    }

    /** Active rows for a query. A non-empty query is an entity find, not a full table load. */
    static EntityList activeRows(ExecutionContext ec, String query) {
        EntityFind find = ec.getEntity().find("moqui.llm.LlmSkill")
                .condition("statusId", "LsksActive").useCache(false);
        List<String> toks = FtsSql.tokens(query);
        if (toks.isEmpty()) return find.list();
        EntityConditionFactory cf = ec.getEntity().getConditionFactory();
        List<EntityCondition> ors = new ArrayList<>();
        for (String tok : toks) {
            String like = "%" + tok.replace("%", "").replace("_", "") + "%";
            ors.add(cf.makeCondition("name", EntityCondition.ComparisonOperator.LIKE, like));
            ors.add(cf.makeCondition("title", EntityCondition.ComparisonOperator.LIKE, like));
        }
        String docLike = "%" + String.join(" ", toks) + "%";
        ors.add(cf.makeCondition("description", EntityCondition.ComparisonOperator.LIKE, docLike));
        ors.add(cf.makeCondition("body", EntityCondition.ComparisonOperator.LIKE, docLike));
        return find.condition(cf.makeCondition(ors, EntityCondition.JoinOperator.OR)).limit(40).list();
    }

    public static String formatInject(ExecutionContext ec, List<SkillDoc> docs) {
        List<Map<String, Object>> skills = new ArrayList<>();
        if (docs != null) {
            int remaining = INJECT_CHARS;
            for (SkillDoc d : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.name);
                m.put("title", d.title);
                m.put("description", d.description);
                m.put("risk", d.risk);
                m.put("body", withoutWidgets(d.body));
                m.put("lessons", lessonLines(ec, d.skillId));
                skills.add(m);
                int approx = (d.body != null ? d.body.length() : 0) + 80;
                remaining -= approx;
                if (remaining <= 0) break;
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skills", skills);
        String text = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SKILL_INJECT, ctx);
        if (text == null) return "";
        if (text.length() > INJECT_CHARS) return text.substring(0, INJECT_CHARS);
        return text;
    }

    static int score(SkillDoc doc, String q) {
        if (q == null || q.isEmpty()) return 1;
        int s = 0;
        String name = nz(doc.name).toLowerCase(Locale.ROOT);
        String title = nz(doc.title).toLowerCase(Locale.ROOT);
        String desc = nz(doc.description).toLowerCase(Locale.ROOT);
        String body = nz(doc.body).toLowerCase(Locale.ROOT);
        if (name.equals(q) || title.equals(q)) s += 100;
        if (name.contains(q) || title.contains(q)) s += 40;
        for (String tok : q.split("\\s+")) {
            if (tok.length() < 3) continue;
            if (name.contains(tok) || title.contains(tok)) s += 20;
            else if (desc.contains(tok)) s += 8;
            else if (body.contains(tok)) s += 2;
        }
        return s;
    }

    static SkillDoc fromEntity(EntityValue ev) {
        SkillDoc d = new SkillDoc();
        d.skillId = ev.getString("skillId");
        d.name = ev.getString("name");
        d.title = ev.getString("title");
        d.description = ev.getString("description");
        d.body = ev.getString("body");
        d.sourceLocation = ev.getString("sourceLocation");
        d.statusId = ev.getString("statusId");
        d.provenanceId = ev.getString("provenanceId");
        String riskId = ev.getString("riskId");
        if ("LskReversible".equals(riskId)) d.risk = "reversible";
        else if ("LskIrreversible".equals(riskId)) d.risk = "irreversible";
        else d.risk = "confirm";
        return d;
    }

    public static EntityValue persistProposed(ExecutionContext ec, SkillDoc doc, String rawBody) {
        if (ec == null || doc == null || doc.name == null || doc.name.isEmpty()) return null;
        if (nameReserved(ec, doc.name)) return null;
        return withAuthzDisabled(ec, () -> {
            EntityValue existing = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", doc.name).useCache(false).one();
            if (existing != null) return existing;
            EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmSkill")
                    .set("name", doc.name)
                    .set("title", doc.title)
                    .set("description", doc.description)
                    .set("body", doc.body != null && !doc.body.isEmpty() ? doc.body : rawBody)
                    .set("riskId", riskId(doc.risk))
                    .set("statusId", "LsksProposed")
                    .set("provenanceId", "LskpSim")
                    .set("speaker", "sim")
                    .set("version", 1)
                    .set("worldSuccessCount", 0)
                    .set("simSuccessCount", 0);
            ev.setSequencedIdPrimary();
            return ev.create();
        });
    }

    /** Promote a sim-proposed skill after a successful world act. */
    public static EntityValue admitWorldPass(ExecutionContext ec, String skillName) {
        if (ec == null || skillName == null || skillName.isEmpty()) return null;
        return withAuthzDisabled(ec, () -> {
            EntityValue sk = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", skillName).useCache(false).one();
            if (sk == null) return null;
            Object worldObj = sk.get("worldSuccessCount");
            long world = worldObj instanceof Number ? ((Number) worldObj).longValue() : 0L;
            sk.set("worldSuccessCount", world + 1L);
            if ("LsksProposed".equals(sk.getString("statusId"))) {
                sk.set("statusId", "LsksActive");
                if ("LskpSim".equals(sk.getString("provenanceId")) || "LskpInfer".equals(sk.getString("provenanceId")))
                    sk.set("provenanceId", "LskpMixed");
            }
            sk.set("lastUsedDate", ec.getUser().getNowTimestamp());
            sk.update();
            try {
                EntityValue use = ec.getEntity().makeValue("moqui.llm.LlmSkillUse")
                        .set("skillId", sk.get("skillId"))
                        .set("contact", "world")
                        .set("outcome", "pass")
                        .set("usedDate", ec.getUser().getNowTimestamp());
                use.setSequencedIdPrimary();
                use.create();
            } catch (Throwable t) {
                logger.warn("LlmSkillUse write: {}", t.getMessage());
            }
            return sk;
        });
    }

    /** Pass/fail for the active skill. A fail also stores one lesson. Does not promote. */
    public static void recordOutcome(ExecutionContext ec, String skillName, String contact, String outcome,
            String lessonBody, String conversationId) {
        if (ec == null || skillName == null || skillName.isBlank()) return;
        if (outcome == null || outcome.isBlank()) return;
        withAuthzDisabled(ec, () -> {
            EntityValue sk = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", skillName).useCache(false).one();
            if (sk == null) return null;
            boolean pass = "pass".equals(outcome);
            if (pass) {
                String countField = "sim".equals(contact) ? "simSuccessCount" : "worldSuccessCount";
                Object cur = sk.get(countField);
                long n = cur instanceof Number ? ((Number) cur).longValue() : 0L;
                sk.set(countField, n + 1L);
            }
            sk.set("lastUsedDate", ec.getUser().getNowTimestamp());
            sk.update();
            try {
                EntityValue use = ec.getEntity().makeValue("moqui.llm.LlmSkillUse")
                        .set("skillId", sk.get("skillId"))
                        .set("conversationId", conversationId)
                        .set("contact", contact)
                        .set("outcome", outcome)
                        .set("notes", cap(lessonBody, 500))
                        .set("usedDate", ec.getUser().getNowTimestamp());
                use.setSequencedIdPrimary();
                use.create();
            } catch (Throwable t) {
                logger.warn("LlmSkillUse write: {}", t.getMessage());
            }
            if (!pass && lessonBody != null && !lessonBody.isBlank()) {
                String title = lessonTitle(lessonBody);
                EntityValue existing = ec.getEntity().find("moqui.llm.LlmLesson")
                        .condition("skillId", sk.get("skillId")).condition("title", title).useCache(false).one();
                if (existing == null) {
                    EntityValue lesson = ec.getEntity().makeValue("moqui.llm.LlmLesson")
                            .set("skillId", sk.get("skillId"))
                            .set("title", title)
                            .set("body", cap(lessonBody, 800))
                            .set("provenanceId", "sim".equals(contact) ? "LskpSim" : "LskpWorld")
                            .set("statusId", "LsksActive")
                            .set("createdDate", ec.getUser().getNowTimestamp());
                    lesson.setSequencedIdPrimary();
                    lesson.create();
                }
            }
            return sk;
        });
    }

    public static void recordOutcomeInTx(ExecutionContext ec, String skillName, String contact, String outcome,
            String lessonBody, String conversationId) {
        if (ec == null || ec.getTransaction() == null) {
            recordOutcome(ec, skillName, contact, outcome, lessonBody, conversationId);
            return;
        }
        boolean began = false;
        try {
            began = ec.getTransaction().begin(60);
            recordOutcome(ec, skillName, contact, outcome, lessonBody, conversationId);
            ec.getTransaction().commit(began);
        } catch (Throwable t) {
            try { ec.getTransaction().rollback(began, "record LlmSkill outcome", t); }
            catch (Throwable ignored) { }
            logger.warn("recordOutcome: {}", t.getMessage());
        }
    }

    static List<String> lessonLines(ExecutionContext ec, String skillId) {
        if (ec == null || skillId == null || skillId.isBlank()) return Collections.emptyList();
        try {
            return withAuthzDisabled(ec, () -> {
                List<String> lines = new ArrayList<>();
                EntityList list = ec.getEntity().find("moqui.llm.LlmLesson")
                        .condition("skillId", skillId).condition("statusId", "LsksActive")
                        .orderBy("-createdDate").limit(3).list();
                int size = list.size();
                for (int i = 0; i < size; i++) {
                    String body = list.get(i).getString("body");
                    if (body != null && !body.isBlank()) lines.add(cap(body.trim(), 400));
                }
                return lines;
            });
        } catch (Throwable t) {
            logger.warn("LlmLesson read: {}", t.getMessage());
            return Collections.emptyList();
        }
    }

    static String lessonTitle(String body) {
        String line = body == null ? "" : body.trim();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl).trim();
        if (line.length() > 120) line = line.substring(0, 120).trim();
        return line.isEmpty() ? "failed" : line;
    }

    static String cap(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max);
    }

    /** Same as {@link #admitWorldPass} but begins a short TX when the caller is not in one. */
    public static EntityValue admitWorldPassInTx(ExecutionContext ec, String skillName) {
        if (ec == null || ec.getTransaction() == null) return admitWorldPass(ec, skillName);
        boolean began = false;
        try {
            began = ec.getTransaction().begin(60);
            EntityValue ev = admitWorldPass(ec, skillName);
            ec.getTransaction().commit(began);
            return ev;
        } catch (Throwable t) {
            try { ec.getTransaction().rollback(began, "admit LlmSkill world pass", t); }
            catch (Throwable ignored) { }
            logger.warn("admitWorldPass: {}", t.getMessage());
            return null;
        }
    }

    /**
     * LlmServlet is not a screen, so inheritAuthz from ASSIST_APP does not cover these rows.
     * Same pattern as {@code LlmConversationImpl} persist.
     */
    static <T> T withAuthzDisabled(ExecutionContext ec, Supplier<T> work) {
        if (work == null) return null;
        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        boolean alreadyDisabled = aefi != null && aefi.disableAuthz();
        try {
            return work.get();
        } finally {
            if (aefi != null && !alreadyDisabled) aefi.enableAuthz();
        }
    }

    static String riskId(String risk) {
        if ("reversible".equalsIgnoreCase(risk)) return "LskReversible";
        if ("irreversible".equalsIgnoreCase(risk)) return "LskIrreversible";
        return "LskConfirm";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static final class Scored {
        final int score;
        final SkillDoc doc;
        final boolean shipped;
        Scored(int score, SkillDoc doc, boolean shipped) {
            this.score = score;
            this.doc = doc;
            this.shipped = shipped;
        }
    }
}
