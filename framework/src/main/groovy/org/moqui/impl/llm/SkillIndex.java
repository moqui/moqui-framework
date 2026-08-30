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
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.resource.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    public static List<SkillDoc> retrieve(ExecutionContext ec, String query, int limit) {
        if (limit <= 0) limit = DEFAULT_LIMIT;
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        for (SkillDoc doc : scanShipped(ec)) {
            int s = score(doc, q);
            if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc));
        }
        if (ec != null && ec.getEntity() != null) {
            try {
                EntityList rows = ec.getEntity().find("moqui.llm.LlmSkill")
                        .condition("statusId", "LsksActive")
                        .useCache(false).list();
                int n = rows.size();
                for (int i = 0; i < n; i++) {
                    EntityValue ev = rows.get(i);
                    SkillDoc doc = fromEntity(ev);
                    int s = score(doc, q);
                    if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc));
                }
            } catch (Throwable t) {
                if (logger.isDebugEnabled()) logger.debug("LlmSkill retrieve: " + t.getMessage());
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<SkillDoc> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Scored s : scored) {
            if (s.doc.name == null || !seen.add(s.doc.name)) continue;
            out.add(s.doc);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public static String formatInject(List<SkillDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return "No matching skill. Call enter_sim before run_service or request writes. Assist may write_ui a clarification form without sim.";
        }
        StringBuilder sb = new StringBuilder("Follow a matching skill before browse. Skills:\n");
        for (SkillDoc d : docs) {
            sb.append("\n## ").append(d.name);
            if (d.title != null && !d.title.equals(d.name)) sb.append(" — ").append(d.title);
            sb.append("\nrisk=").append(d.risk);
            if (d.description != null && !d.description.isEmpty()) sb.append("\n").append(d.description);
            sb.append("\n\n").append(d.body).append("\n");
            if (sb.length() > INJECT_CHARS) break;
        }
        if (sb.length() > INJECT_CHARS) return sb.substring(0, INJECT_CHARS);
        return sb.toString();
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

    private static String nz(String s) { return s == null ? "" : s; }

    private static final class Scored {
        final int score;
        final SkillDoc doc;
        Scored(int score, SkillDoc doc) { this.score = score; this.doc = doc; }
    }
}
