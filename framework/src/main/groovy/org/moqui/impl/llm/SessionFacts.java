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
import org.moqui.entity.EntityValue;
import org.moqui.impl.service.ServiceFacadeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Facts the logged-in user already has: party, locale, time zone, and the
 * internal organizations {@code setup#UserOrganizationInfo} returns for them.
 */
public final class SessionFacts {
    private static final Logger logger = LoggerFactory.getLogger(SessionFacts.class);
    static final String ORG_SERVICE = "mantle.party.PartyServices.setup#UserOrganizationInfo";

    private SessionFacts() { }

    public static String text(ExecutionContext ec) {
        if (ec == null || ec.getUser() == null || ec.getUser().getUserId() == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("userId=").append(ec.getUser().getUserId());
        String username = ec.getUser().getUsername();
        if (username != null && !username.isBlank()) sb.append("\nusername=").append(username);
        EntityValue account = null;
        try { account = ec.getUser().getUserAccount(); }
        catch (Throwable ignored) { }
        String partyId = account != null ? account.getString("partyId") : null;
        if (partyId != null && !partyId.isBlank()) {
            sb.append("\npartyId=").append(partyId);
            String name = partyName(ec, partyId);
            if (name != null) sb.append("\npartyName=").append(name);
        }
        try {
            Locale locale = ec.getUser().getLocale();
            if (locale != null) sb.append("\nlocale=").append(locale.toLanguageTag());
        } catch (Throwable ignored) { }
        try {
            TimeZone tz = ec.getUser().getTimeZone();
            if (tz != null) sb.append("\ntimeZone=").append(tz.getID());
        } catch (Throwable ignored) { }
        appendOrgs(ec, sb);
        return sb.toString();
    }

    private static void appendOrgs(ExecutionContext ec, StringBuilder sb) {
        if (!(ec.getService() instanceof ServiceFacadeImpl)) return;
        if (!((ServiceFacadeImpl) ec.getService()).isServiceDefined(ORG_SERVICE)) return;
        Map<String, Object> out;
        try {
            out = ec.getService().sync().name(ORG_SERVICE).call();
        } catch (Throwable t) {
            logger.debug("setup#UserOrganizationInfo: {}", t.getMessage());
            return;
        }
        if (out == null) return;
        String activeId = str(out.get("activeOrgId"));
        String activeName = orgLabel(out.get("activeOrg"));
        List<Map<String, String>> orgs = orgRows(out.get("userOrgList"));
        if (activeId != null && !activeId.isBlank()) {
            sb.append("\nactiveOrgId=").append(activeId);
            if (activeName != null) sb.append("\nactiveOrg=").append(activeName);
        }
        if (!orgs.isEmpty()) {
            sb.append("\norganizations:");
            for (Map<String, String> row : orgs) {
                sb.append("\n- partyId=").append(row.get("partyId"));
                if (row.get("pseudoId") != null) sb.append(" pseudoId=").append(row.get("pseudoId"));
                if (row.get("name") != null) sb.append(" name=").append(row.get("name"));
            }
        }
        if ((activeId == null || activeId.isBlank()) && orgs.size() > 1) {
            sb.append("\nNo active organization. Do not guess. ");
            sb.append("Build a form the user submits with POST /apps/setPrefGoLast, ");
            sb.append("preferenceKey=ACTIVE_ORGANIZATION, preferenceValue set to one partyId listed above.");
        } else if ((activeId == null || activeId.isBlank()) && orgs.size() == 1) {
            sb.append("\nNo active organization is set. The only related organization is ")
                    .append(orgs.get(0).get("partyId"))
                    .append(". Ask before writing that id onto a record that needs an internal organization, ");
            sb.append("or let the user submit POST /apps/setPrefGoLast with preferenceKey=ACTIVE_ORGANIZATION.");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> orgRows(Object raw) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!(raw instanceof List)) return rows;
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String partyId = str(m.get("partyId"));
            if (partyId == null || partyId.isBlank()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("partyId", partyId);
            String pseudo = str(m.get("pseudoId"));
            if (pseudo != null) row.put("pseudoId", pseudo);
            String name = str(m.get("organizationName"));
            if (name == null) name = orgLabel(m);
            if (name != null) row.put("name", name);
            rows.add(row);
        }
        return rows;
    }

    private static String orgLabel(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) raw;
        String name = str(m.get("organizationName"));
        if (name != null) return name;
        String pseudo = str(m.get("pseudoId"));
        return pseudo;
    }

    private static String partyName(ExecutionContext ec, String partyId) {
        try {
            EntityValue party = ec.getEntity().find("mantle.party.PartyDetail")
                    .condition("partyId", partyId).useCache(true).one();
            if (party == null) return null;
            String org = party.getString("organizationName");
            if (org != null && !org.isBlank()) return org;
            String first = party.getString("firstName");
            String last = party.getString("lastName");
            StringBuilder sb = new StringBuilder();
            if (first != null) sb.append(first);
            if (last != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(last);
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
