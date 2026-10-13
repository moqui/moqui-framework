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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shipped component://…/skill/*.md plus admitted LlmSkill rows. See framework/plans/LlmSkillLearning.md.
 */
public class SkillIndex {
    private static final Logger logger = LoggerFactory.getLogger(SkillIndex.class);
    public static final int DEFAULT_LIMIT = 5;
    /** Catalog inject budget. Over this, drop whole skills from the end. Never cut inside a skill. */
    public static final int INJECT_CHARS = 32000;
    /** Log signal only. A body at or over this length is still sent whole. */
    public static final int BODY_WARN_CHARS = 4000;
    /** Intent words. Kept for an exact name or title phrase, ignored as loose tokens. */
    private static final Set<String> QUERY_STOP = new HashSet<>(Arrays.asList(
            "show", "list", "find", "get", "what", "the", "for", "my", "a", "an", "open"));

    public static class SkillDoc {
        public String name, title, description, summary, body, risk, sourceLocation, skillId, statusId, provenanceId;
        /** procedure (blank) or reference. Set from the type front-matter key of a shipped file or the LlmSkill kind field. */
        public String kind;
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
        String summary = nz(doc.frontMatter.get("summary"));
        doc.summary = summary.isEmpty() ? null : summary;
        String kind = nz(doc.frontMatter.get("type"));
        if (kind.isEmpty()) kind = nz(doc.frontMatter.get("kind"));
        doc.kind = kind.isEmpty() ? null : kind.toLowerCase(Locale.ROOT);
        doc.risk = nz(doc.frontMatter.get("risk"));
        if (doc.risk.isEmpty()) doc.risk = "confirm";
        doc.statusId = "LsksActive";
        doc.provenanceId = "LskpHuman";
        return doc;
    }

    private static final long SHIPPED_TTL_MS = 15_000L;
    private static volatile List<SkillDoc> shippedCache = null;
    private static volatile Object shippedCacheOwner = null;
    private static volatile long shippedCacheTime = 0L;

    /** Shipped skill files, read from every component. Cached briefly because risk checks call this per tool call. */
    public static List<SkillDoc> scanShipped(ExecutionContext ec) {
        if (ec == null || ec.getFactory() == null) return new ArrayList<>();
        long now = System.currentTimeMillis();
        List<SkillDoc> cached = shippedCache;
        if (cached != null && shippedCacheOwner == ec.getFactory() && now - shippedCacheTime < SHIPPED_TTL_MS)
            return new ArrayList<>(cached);
        List<SkillDoc> fresh = scanShippedUncached(ec);
        shippedCache = fresh;
        shippedCacheOwner = ec.getFactory();
        shippedCacheTime = now;
        return new ArrayList<>(fresh);
    }

    private static List<SkillDoc> scanShippedUncached(ExecutionContext ec) {
        List<SkillDoc> out = new ArrayList<>();
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

    /** True when a shipped skill file owns this name. Screens use it to refuse edits to a shadowing database row. */
    public static boolean isShippedName(ExecutionContext ec, String name) {
        return name != null && shippedByName(ec, name.trim()) != null;
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

    /** True for concept cards, marked `type: reference` in a shipped file or kind=reference on the row. They are not write playbooks. */
    public static boolean isReference(SkillDoc doc) {
        return doc != null && "reference".equalsIgnoreCase(doc.kind);
    }

    /**
     * Catalog and query text. A summary when one is set, otherwise the full skill text.
     * No heading is stripped.
     */
    public static String summaryText(SkillDoc doc) {
        if (doc == null) return null;
        if (doc.summary != null && !doc.summary.isBlank()) return doc.summary.trim();
        return doc.body;
    }

    /**
     * Full skill text for the active-skill context block, only when a summary is set.
     * With no summary the catalog already carries the full text, so the block stays empty.
     */
    public static String activeDetailText(SkillDoc doc) {
        if (doc == null || doc.summary == null || doc.summary.isBlank()) return null;
        return doc.body;
    }
    public static String activeDetailText(ExecutionContext ec, String skillName) {
        if (skillName == null || skillName.isBlank() || ec == null) return null;
        return activeDetailText(getByName(ec, skillName));
    }

    /**
     * Procedure skills fill the inject slots. Reference cards are a gloss beside them,
     * not one of the three procedure slots. A procedure is injected only on a real match.
     * A blank query keeps the first procedures.
     */
    public static String formatInjectForQuery(ExecutionContext ec, String query) {
        return formatInjectForDocs(ec, retrieve(ec, query, 15), query);
    }

    /** Inject for an already chosen list, in that order. Reference cards stay out of the procedure slots. */
    public static String formatInjectForDocs(ExecutionContext ec, List<SkillDoc> found, String query) {
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();
        if (found != null) {
            for (SkillDoc d : found) {
                if (isReference(d)) {
                    if (cards.size() >= 2) continue;
                    cards.add(referenceMap(d));
                } else if (skills.size() < 3 && includeProcedure(d, query)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("title", d.title);
                    m.put("description", d.description);
                    m.put("risk", d.risk);
                    m.put("body", injectBody(d));
                    m.put("lessons", lessonLines(ec, d.skillId));
                    skills.add(m);
                }
            }
        }
        boolean hasProcedure = !skills.isEmpty();
        skills.addAll(cards);
        return fitInject(ec, skills, hasProcedure);
    }

    private static Map<String, Object> referenceMap(SkillDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name);
        m.put("title", d.title);
        m.put("description", d.description);
        m.put("reference", Boolean.TRUE);
        m.put("body", injectBody(d));
        return m;
    }

    /** Summary when set, otherwise the full body. Logged when large, always sent whole. */
    private static String injectBody(SkillDoc d) {
        String body = summaryText(d);
        if (body == null) body = "";
        else body = body.trim();
        warnIfLarge(d != null ? d.name : null, body);
        return body;
    }

    private static void warnIfLarge(String name, String body) {
        if (body != null && body.length() >= BODY_WARN_CHARS)
            logger.warn("LLM text {} is {} characters. It is sent whole.", name, body.length());
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
            for (SkillDoc d : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.name);
                m.put("title", d.title);
                m.put("description", d.description);
                m.put("risk", d.risk);
                m.put("body", injectBody(d));
                m.put("lessons", lessonLines(ec, d.skillId));
                skills.add(m);
            }
        }
        return fitInject(ec, skills, null);
    }

    /** Over the budget, drop whole skills from the end. One skill that is still over is returned whole. */
    private static String fitInject(ExecutionContext ec, List<Map<String, Object>> skills, Boolean hasProcedure) {
        String text = renderInject(ec, skills, hasProcedure);
        while (text.length() > INJECT_CHARS && skills.size() > 1) {
            Map<String, Object> dropped = skills.remove(skills.size() - 1);
            logger.warn("Skill inject over {} characters, dropped skill {}", INJECT_CHARS, dropped.get("name"));
            text = renderInject(ec, skills, hasProcedure);
        }
        return text;
    }

    private static String renderInject(ExecutionContext ec, List<Map<String, Object>> skills, Boolean hasProcedure) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skills", skills);
        if (hasProcedure != null) ctx.put("hasProcedure", hasProcedure);
        String text = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SKILL_INJECT, ctx);
        return text == null ? "" : text;
    }

    /** A blank query keeps the top procedures. Any other query needs a real match. */
    static boolean includeProcedure(SkillDoc doc, String query) {
        return query == null || query.isBlank() || strongMatch(doc, query);
    }

    /** Name or title content token, or a phrase in the name, title, or description. */
    public static boolean strongMatch(SkillDoc doc, String query) {
        if (query == null || query.isBlank()) return false;
        return scoreDetail(doc, query).strong;
    }

    public static final class ScoreDetail {
        public final int score;
        public final boolean strong;
        ScoreDetail(int score, boolean strong) { this.score = score; this.strong = strong; }
    }

    static int score(SkillDoc doc, String q) {
        return scoreDetail(doc, q).score;
    }

    public static ScoreDetail scoreDetail(SkillDoc doc, String q) {
        if (q == null || q.isEmpty()) return new ScoreDetail(1, false);
        if (doc == null) return new ScoreDetail(0, false);
        String query = normPhrase(q);
        if (query.isEmpty()) return new ScoreDetail(1, false);
        String name = normPhrase(nz(doc.name));
        String title = normPhrase(nz(doc.title));
        String desc = normPhrase(nz(doc.description));
        String body = normPhrase(nz(doc.body));
        int s = 0;
        boolean strong = false;
        if (name.equals(query) || title.equals(query)) { s += 100; strong = true; }
        List<String> content = contentTokens(query);
        if (!content.isEmpty() && (name.contains(query) || title.contains(query))) { s += 40; strong = true; }
        for (String tok : content) {
            if (containsTok(name, tok) || containsTok(title, tok)) { s += 20; strong = true; }
            else if (containsTok(desc, tok)) s += 8;
            else if (containsTok(body, tok)) s += 2;
        }
        if (content.size() >= 2 && (phraseIn(name, content) || phraseIn(title, content) || phraseIn(desc, content))) {
            s += 40;
            strong = true;
        }
        return new ScoreDetail(s, strong);
    }

    private static String normPhrase(String s) {
        return s.toLowerCase(Locale.ROOT).replace('-', ' ').trim().replaceAll("\\s+", " ");
    }

    private static List<String> contentTokens(String query) {
        List<String> out = new ArrayList<>();
        for (String tok : query.split(" ")) {
            if (tok.isEmpty() || QUERY_STOP.contains(tok) || tok.length() < 3) continue;
            String stemmed = stem(tok);
            if (stemmed.length() < 3 || QUERY_STOP.contains(stemmed)) continue;
            out.add(stemmed);
        }
        return out;
    }

    /** Trailing s, length at least 5, so orders matches order. A double s stays. */
    private static String stem(String tok) {
        if (tok.length() >= 5 && tok.endsWith("s") && !tok.endsWith("ss"))
            return tok.substring(0, tok.length() - 1);
        return tok;
    }

    private static boolean containsTok(String hay, String stemmedTok) {
        return !hay.isEmpty() && !stemmedTok.isEmpty() && hay.contains(stemmedTok);
    }

    private static boolean phraseIn(String hay, List<String> words) {
        int from = 0;
        for (String w : words) {
            int i = hay.indexOf(w, from);
            if (i < 0) return false;
            from = i + w.length();
        }
        return true;
    }

    static SkillDoc fromEntity(EntityValue ev) {
        SkillDoc d = new SkillDoc();
        d.skillId = ev.getString("skillId");
        d.name = ev.getString("name");
        d.title = ev.getString("title");
        d.description = ev.getString("description");
        d.summary = ev.getString("summary");
        d.body = ev.getString("body");
        d.sourceLocation = ev.getString("sourceLocation");
        d.statusId = ev.getString("statusId");
        d.provenanceId = ev.getString("provenanceId");
        d.kind = ev.getString("kind");
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
                    .set("summary", doc.summary != null && !doc.summary.isBlank() ? doc.summary.trim() : null)
                    .set("body", doc.body != null && !doc.body.isEmpty() ? doc.body : rawBody)
                    .set("riskId", riskId(doc.risk))
                    .set("statusId", "LsksProposed")
                    .set("provenanceId", "LskpSim")
                    .set("speaker", "sim")
                    .set("version", 1)
                    .set("worldSuccessCount", 0)
                    .set("simSuccessCount", 0);
            ev.setSequencedIdPrimary();
            try {
                return ev.create();
            } catch (org.moqui.entity.EntityException e) {
                // Two sims proposing the same name at once: the unique index on name lets one win. Use the winner's row.
                EntityValue winner = ec.getEntity().find("moqui.llm.LlmSkill")
                        .condition("name", doc.name.trim()).useCache(false).one();
                if (winner != null) return winner;
                throw e;
            }
        });
    }

    /**
     * Count a successful world act for a sim-proposed skill. The skill stays Proposed, which keeps it out of
     * every other user's prompt and search, until a person activates it. Setting the llm_skill_auto_admit
     * property or environment variable to true restores automatic promotion for trusted single-tenant setups.
     */
    public static EntityValue admitWorldPass(ExecutionContext ec, String skillName) {
        if (ec == null || skillName == null || skillName.isEmpty()) return null;
        return withAuthzDisabled(ec, () -> {
            EntityValue sk = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", skillName).useCache(false).one();
            if (sk == null) return null;
            Object worldObj = sk.get("worldSuccessCount");
            long world = worldObj instanceof Number ? ((Number) worldObj).longValue() : 0L;
            sk.set("worldSuccessCount", world + 1L);
            if ("LsksProposed".equals(sk.getString("statusId"))
                    && "true".equals(org.moqui.util.SystemBinding.getPropOrEnv("llm_skill_auto_admit"))) {
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
                            .set("body", lessonBody.trim())
                            .set("provenanceId", "sim".equals(contact) ? "LskpSim" : "LskpWorld")
                            // A model-written skill must not push text into every user's prompt unreviewed
                            .set("statusId", "LskpHuman".equals(sk.getString("provenanceId")) ? "LsksActive" : "LsksProposed")
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

    public static List<String> lessonLines(ExecutionContext ec, String skillId) {
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
                    if (body != null && !body.isBlank()) {
                        String text = body.trim();
                        warnIfLarge("lesson " + skillId, text);
                        lines.add(text);
                    }
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
     * LlmServlet is not a screen, so screen inheritAuthz does not cover these rows.
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
