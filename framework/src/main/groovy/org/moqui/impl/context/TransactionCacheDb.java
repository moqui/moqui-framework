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
package org.moqui.impl.context;

import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.entity.EntityFindBase;
import org.moqui.impl.entity.EntityJavaUtil.FindAugmentInfo;
import org.moqui.impl.entity.EntityJavaUtil.WriteMode;
import org.moqui.impl.entity.EntityListImpl;
import org.moqui.impl.entity.EntityValueBase;
import org.moqui.impl.entity.FieldInfo;
import org.moqui.util.LiteStringMap;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 in-memory working-set overlay. One instance spans many JTA transactions until
 * {@link org.moqui.entity.EntityFacade#stopTxCache()}. HOLD never writes production; FLUSH writes dirty rows on close.
 */
public class TransactionCacheDb implements EntityTxCache {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionCacheDb.class);
    public static final int COPY_CAP = 10000;
    private static final long SEQ_START = 900000000L;

    private final EntityFacadeImpl efi;
    private final boolean hold;
    private final String jdbcUrl;
    private final Set<String> tablesCreated = new HashSet<>();
    private final Set<String> schemasCreated = new HashSet<>();
    private final Set<Map<String, Object>> dirtyCreateKeys = new LinkedHashSet<>();
    private final Set<Map<String, Object>> dirtyUpdateKeys = new LinkedHashSet<>();
    private final Set<Map<String, Object>> tombstoneKeys = new LinkedHashSet<>();
    private final Map<Map<String, Object>, EntityValueBase> tombstoneValues = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> seqBanks = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> bypassDepth = ThreadLocal.withInitial(() -> 0);
    private boolean closed = false;
    private boolean readOnly = false;

    static {
        try { Class.forName("org.h2.Driver"); }
        catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }

    public TransactionCacheDb(EntityFacadeImpl efi, boolean hold) {
        this.efi = efi;
        this.hold = hold;
        String id = UUID.randomUUID().toString().replace("-", "");
        this.jdbcUrl = "jdbc:h2:mem:tcdb" + id + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=30000";
    }

    public boolean isHold() { return hold; }
    public boolean isBypass() { return bypassDepth.get() > 0; }
    public void beginBypass() { bypassDepth.set(bypassDepth.get() + 1); }
    public void endBypass() {
        int d = bypassDepth.get() - 1;
        if (d <= 0) bypassDepth.remove();
        else bypassDepth.set(d);
    }

    public boolean handles(EntityDefinition ed) {
        if (ed == null) return false;
        String name = ed.getFullEntityName();
        if (name.startsWith("moqui.llm.")) return false;
        if ("moqui.entity.SequenceValueItem".equals(name)) return false;
        if (ed.isViewEntity) return true;
        return ed.entityInfo.isEntityDatasourceFactoryImpl;
    }

    public String nextSeq(String seqName) {
        AtomicLong bank = seqBanks.computeIfAbsent(seqName, n -> new AtomicLong(SEQ_START));
        return Long.toString(bank.incrementAndGet());
    }

    public Connection getConnection() throws SQLException {
        checkOpen();
        Connection con = DriverManager.getConnection(jdbcUrl, "sa", "");
        con.setAutoCommit(true);
        return con;
    }

    @Override public boolean isReadOnly() { return readOnly; }
    @Override public void makeReadOnly() { readOnly = true; }
    @Override public void makeWriteThrough() { readOnly = false; }
    @Override public boolean shouldClearEntityCache() { return !hold; }

    @Override
    public boolean create(EntityValueBase evb) {
        if (readOnly) return false;
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed) || ed.isViewEntity) return false;
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        if (tombstoneKeys.contains(key)) {
            tombstoneKeys.remove(key);
            tombstoneValues.remove(key);
            dirtyCreateKeys.remove(key);
            dirtyUpdateKeys.add(key);
            ensureTable(ed);
            upsertH2(evb, ed, true);
            return true;
        }
        ensureTable(ed);
        if (existsInH2(evb, ed)) {
            throw new EntityException("Tried to create a value that already exists in TX cache DB, entity " +
                    evb.resolveEntityName() + ", PK " + evb.getPrimaryKeys());
        }
        insertH2(evb, ed);
        dirtyCreateKeys.add(key);
        dirtyUpdateKeys.remove(key);
        return true;
    }

    @Override
    public boolean update(EntityValueBase evb) {
        if (readOnly) return false;
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed) || ed.isViewEntity) return false;
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        if (tombstoneKeys.contains(key)) return true;
        ensureTable(ed);
        if (!existsInH2(evb, ed)) {
            EntityValueBase fromDb = loadFromWorld(evb, ed);
            if (fromDb != null) insertH2(fromDb, ed);
        }
        if (!existsInH2(evb, ed)) {
            insertH2(evb, ed);
            dirtyCreateKeys.add(key);
        } else {
            updateH2(evb, ed);
            if (!dirtyCreateKeys.contains(key)) dirtyUpdateKeys.add(key);
        }
        return true;
    }

    @Override
    public boolean delete(EntityValueBase evb) {
        if (readOnly) return false;
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed) || ed.isViewEntity) return false;
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        ensureTable(ed);
        if (dirtyCreateKeys.remove(key)) {
            deleteH2(evb, ed);
            dirtyUpdateKeys.remove(key);
            return true;
        }
        deleteH2(evb, ed);
        dirtyUpdateKeys.remove(key);
        tombstoneKeys.add(key);
        tombstoneValues.put(key, (EntityValueBase) evb.cloneValue());
        return true;
    }

    @Override
    public boolean refresh(EntityValueBase evb) {
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed) || ed.isViewEntity) return false;
        Map<String, Object> key = makeKey(evb);
        if (key != null && tombstoneKeys.contains(key)) return false;
        ensureTable(ed);
        return selectInto(evb, ed);
    }

    @Override
    public EntityValueBase oneGet(EntityFindBase efb) {
        EntityDefinition ed = efb.getEntityDef();
        if (!handles(ed) || ed.isViewEntity) return null;
        Map<String, Object> pk = efb.getSimpleMapPrimaryKeys();
        if (pk == null || pk.isEmpty()) return null;
        Map<String, Object> key = new HashMap<>(pk);
        key.put("_entityName", ed.getFullEntityName());
        if (tombstoneKeys.contains(key)) {
            return new EntityValueBase.DeletedEntityValue(ed, efi);
        }
        if (!tablesCreated.contains(ed.getFullEntityName())) return null;
        EntityValueBase evb = (EntityValueBase) efi.makeValue(ed.getFullEntityName());
        for (Map.Entry<String, Object> e : pk.entrySet()) evb.set(e.getKey(), e.getValue());
        if (selectInto(evb, ed)) return (EntityValueBase) evb.cloneValue();
        return null;
    }

    @Override
    public void onePut(EntityValueBase evb, boolean forUpdate) {
        if (evb == null) return;
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed)) return;
        if (ed.isViewEntity) {
            copyViewMembers(evb, ed);
            return;
        }
        Map<String, Object> key = makeKey(evb);
        if (key != null && (tombstoneKeys.contains(key) || dirtyCreateKeys.contains(key) || dirtyUpdateKeys.contains(key)))
            return;
        ensureTable(ed);
        if (existsInH2(evb, ed)) return;
        insertH2(evb, ed);
    }

    /** Copy a production (or view) row into H2 as a clean replica. View rows expand to member entities. */
    public void copyFromProduction(EntityValueBase evb) {
        if (evb == null) return;
        EntityDefinition ed = evb.getEntityDefinition();
        if (!handles(ed)) return;
        if (ed.isViewEntity) {
            copyViewMembers(evb, ed);
            return;
        }
        Map<String, Object> key = makeKey(evb);
        if (key != null && (tombstoneKeys.contains(key) || dirtyCreateKeys.contains(key) || dirtyUpdateKeys.contains(key)))
            return;
        ensureTable(ed);
        if (existsInH2(evb, ed)) return;
        insertH2(evb, ed);
    }

    public void ensureReady(EntityDefinition ed) {
        if (ed.isViewEntity) {
            ArrayList<MNode> members = ed.getEntityNode().children("member-entity");
            if (members != null) {
                for (int i = 0; i < members.size(); i++) {
                    String memberName = members.get(i).attribute("entity-name");
                    EntityDefinition memberEd = efi.getEntityDefinition(memberName);
                    if (memberEd != null && !memberEd.isViewEntity) ensureTable(memberEd);
                }
            }
        } else {
            ensureTable(ed);
        }
    }

    @Override
    public EntityListImpl listGet(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByExpanded) {
        return null;
    }

    @Override
    public void listPut(EntityDefinition ed, EntityCondition whereCondition, EntityListImpl eli) { }

    @Override
    public WriteMode checkUpdateValue(EntityValueBase evb, FindAugmentInfo fai) {
        return null;
    }

    @Override
    public FindAugmentInfo getFindAugmentInfo(String entityName, EntityCondition econd) {
        return new FindAugmentInfo(new ArrayList<>(), new HashSet<>(), econd);
    }

    @Override
    public boolean isTxCreate(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        return key != null && dirtyCreateKeys.contains(key);
    }

    @Override public boolean isKnownLocked(EntityValueBase evb) { return false; }

    @Override
    public void flushCache(boolean clearRead) {
        if (hold) return;
        checkOpen();
        EntityFacadeImpl localEfi = this.efi;
        try {
            for (Map<String, Object> key : dirtyCreateKeys) {
                EntityValueBase evb = loadFromH2ByKey(key);
                if (evb == null) continue;
                String group = evb.getEntityDefinition().getEntityGroupName();
                try (Connection con = localEfi.getConnection(group)) {
                    evb.basicCreate(con);
                }
            }
            for (Map<String, Object> key : dirtyUpdateKeys) {
                EntityValueBase evb = loadFromH2ByKey(key);
                if (evb == null) continue;
                String group = evb.getEntityDefinition().getEntityGroupName();
                try (Connection con = localEfi.getConnection(group)) {
                    evb.basicUpdate(con);
                }
            }
            for (Map<String, Object> key : tombstoneKeys) {
                EntityValueBase evb = tombstoneValues.get(key);
                if (evb == null) continue;
                String group = evb.getEntityDefinition().getEntityGroupName();
                try (Connection con = localEfi.getConnection(group)) {
                    evb.deleteExtended(con);
                }
            }
        } catch (Exception e) {
            throw new EntityException("Error flushing TransactionCacheDb to production", e);
        }
        dirtyCreateKeys.clear();
        dirtyUpdateKeys.clear();
        tombstoneKeys.clear();
        tombstoneValues.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            if (!hold) flushCache(true);
            try (Connection con = DriverManager.getConnection(jdbcUrl, "sa", "");
                 Statement st = con.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (SQLException e) {
                if (logger.isDebugEnabled()) logger.debug("H2 overlay shutdown: " + e.toString());
            }
        } finally {
            closed = true;
            tablesCreated.clear();
            dirtyCreateKeys.clear();
            dirtyUpdateKeys.clear();
            tombstoneKeys.clear();
            tombstoneValues.clear();
        }
    }

    private void checkOpen() {
        if (closed) throw new EntityException("TransactionCacheDb is closed");
    }

    static Map<String, Object> makeKey(EntityValueBase evb) {
        if (evb == null) return null;
        Map<String, Object> key = evb.getPrimaryKeys();
        if (key == null || key.isEmpty()) return null;
        key.put("_entityName", evb.resolveEntityName());
        return key;
    }

    private void copyViewMembers(EntityValueBase viewRow, EntityDefinition viewEd) {
        ArrayList<MNode> members = viewEd.getEntityNode().children("member-entity");
        if (members == null) return;
        beginBypass();
        try {
            for (int i = 0; i < members.size(); i++) {
                MNode member = members.get(i);
                String memberName = member.attribute("entity-name");
                EntityDefinition memberEd = efi.getEntityDefinition(memberName);
                if (memberEd == null || memberEd.isViewEntity || !handles(memberEd)) continue;
                ensureTable(memberEd);
                Map<String, ArrayList<MNode>> fieldAliases = viewEd.getMemberFieldAliases(memberEd.getFullEntityName());
                Map<String, Object> cond = new LinkedHashMap<>();
                ArrayList<String> pkNames = memberEd.getPkFieldNames();
                boolean fullPk = pkNames.size() > 0;
                for (int p = 0; p < pkNames.size(); p++) {
                    String pkField = pkNames.get(p);
                    String alias = aliasForMemberField(fieldAliases, pkField);
                    if (alias == null || !viewEd.isField(alias)) { fullPk = false; break; }
                    Object val = viewRow.get(alias);
                    if (val == null) { fullPk = false; break; }
                    cond.put(pkField, val);
                }
                if (!fullPk && fieldAliases != null) {
                    cond.clear();
                    for (Map.Entry<String, ArrayList<MNode>> e : fieldAliases.entrySet()) {
                        String memberField = e.getKey();
                        ArrayList<MNode> aliases = e.getValue();
                        if (aliases == null || aliases.isEmpty()) continue;
                        String aliasName = aliases.get(0).attribute("name");
                        if (aliasName == null || aliasName.isEmpty()) aliasName = memberField;
                        if (!viewEd.isField(aliasName)) continue;
                        Object val = viewRow.get(aliasName);
                        if (val != null) cond.put(memberField, val);
                    }
                }
                if (cond.isEmpty()) continue;
                if (fullPk) {
                    EntityValue one = efi.find(memberEd.getFullEntityName()).condition(cond).useCache(false).one();
                    if (one instanceof EntityValueBase) copyFromProduction((EntityValueBase) one);
                } else {
                    EntityList list = efi.find(memberEd.getFullEntityName()).condition(cond).useCache(false).list();
                    int sz = list.size();
                    for (int j = 0; j < sz; j++) {
                        EntityValue ev = list.get(j);
                        if (ev instanceof EntityValueBase) copyFromProduction((EntityValueBase) ev);
                    }
                }
            }
        } finally {
            endBypass();
        }
    }

    private static String aliasForMemberField(Map<String, ArrayList<MNode>> fieldAliases, String memberField) {
        if (fieldAliases == null) return memberField;
        ArrayList<MNode> aliases = fieldAliases.get(memberField);
        if (aliases == null || aliases.isEmpty()) return memberField;
        String name = aliases.get(0).attribute("name");
        return name != null && !name.isEmpty() ? name : memberField;
    }

    private EntityValueBase loadFromWorld(EntityValueBase evb, EntityDefinition ed) {
        beginBypass();
        try {
            EntityValue one = efi.find(ed.getFullEntityName()).condition(evb.getPrimaryKeys()).useCache(false).one();
            return one instanceof EntityValueBase ? (EntityValueBase) one : null;
        } finally {
            endBypass();
        }
    }

    private EntityValueBase loadFromH2ByKey(Map<String, Object> key) {
        String entityName = (String) key.get("_entityName");
        if (entityName == null) return null;
        EntityDefinition ed = efi.getEntityDefinition(entityName);
        EntityValueBase evb = (EntityValueBase) efi.makeValue(entityName);
        for (Map.Entry<String, Object> e : key.entrySet()) {
            if ("_entityName".equals(e.getKey())) continue;
            evb.set(e.getKey(), e.getValue());
        }
        if (selectInto(evb, ed)) return evb;
        return null;
    }

    private void ensureTable(EntityDefinition ed) {
        if (ed.isViewEntity) return;
        String fullName = ed.getFullEntityName();
        if (tablesCreated.contains(fullName)) return;
        synchronized (tablesCreated) {
            if (tablesCreated.contains(fullName)) return;
            checkOpen();
            String schema = ed.getSchemaName();
            try (Connection con = getConnection(); Statement st = con.createStatement()) {
                if (schema != null && !schema.isEmpty() && schemasCreated.add(schema)) {
                    st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                }
                st.execute(buildCreateTableSql(ed));
            } catch (SQLException e) {
                throw new EntityException("Could not create overlay table for " + fullName, e);
            }
            tablesCreated.add(fullName);
        }
    }

    private String buildCreateTableSql(EntityDefinition ed) {
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(ed.getFullTableName()).append(" (");
        FieldInfo[] all = ed.entityInfo.allFieldInfoArray;
        int pkCount = 0;
        for (int i = 0; i < all.length; i++) {
            FieldInfo fi = all[i];
            if (fi == null) break;
            if (i > 0) sql.append(", ");
            sql.append(fi.columnName).append(" ").append(h2SqlType(fi));
            if (fi.isPk) {
                sql.append(" NOT NULL");
                pkCount++;
            }
        }
        if (pkCount > 0) {
            sql.append(", PRIMARY KEY (");
            FieldInfo[] pks = ed.entityInfo.pkFieldInfoArray;
            boolean first = true;
            for (int i = 0; i < pks.length; i++) {
                FieldInfo fi = pks[i];
                if (fi == null) break;
                if (!first) sql.append(", ");
                first = false;
                sql.append(fi.columnName);
            }
            sql.append(")");
        }
        sql.append(")");
        return sql.toString();
    }

    private String h2SqlType(FieldInfo fi) {
        String fieldType = fi.type;
        MNode h2Node = efi.getDatabaseNodeByConf("h2");
        if (h2Node != null) {
            ArrayList<MNode> types = h2Node.children("database-type");
            if (types != null) {
                for (int i = 0; i < types.size(); i++) {
                    MNode dt = types.get(i);
                    if (fieldType.equals(dt.attribute("type")) && dt.attribute("sql-type") != null)
                        return dt.attribute("sql-type");
                }
            }
        }
        MNode dbList = efi.ecfi.getConfXmlRoot().first("database-list");
        if (dbList != null) {
            ArrayList<MNode> dict = dbList.children("dictionary-type");
            if (dict != null) {
                for (int i = 0; i < dict.size(); i++) {
                    MNode d = dict.get(i);
                    if (fieldType.equals(d.attribute("type")) && d.attribute("default-sql-type") != null)
                        return d.attribute("default-sql-type");
                }
            }
        }
        return "VARCHAR(255)";
    }

    private boolean existsInH2(EntityValueBase evb, EntityDefinition ed) {
        EntityValueBase tmp = (EntityValueBase) efi.makeValue(ed.getFullEntityName());
        tmp.setFields(evb.getPrimaryKeys(), true, null, true);
        return selectInto(tmp, ed);
    }

    private boolean selectInto(EntityValueBase evb, EntityDefinition ed) {
        if (!tablesCreated.contains(ed.getFullEntityName())) return false;
        FieldInfo[] all = ed.entityInfo.allFieldInfoArray;
        FieldInfo[] pks = ed.entityInfo.pkFieldInfoArray;
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < all.length; i++) {
            FieldInfo fi = all[i];
            if (fi == null) break;
            if (i > 0) sql.append(", ");
            sql.append(fi.columnName);
        }
        sql.append(" FROM ").append(ed.getFullTableName()).append(" WHERE ");
        appendPkWhere(sql, pks);
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            bindPk(ps, evb, pks, 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                LiteStringMap<Object> vm = evb.getValueMap();
                for (int i = 0; i < all.length; i++) {
                    FieldInfo fi = all[i];
                    if (fi == null) break;
                    fi.getResultSetValue(rs, i + 1, vm, efi);
                }
                evb.setSyncedWithDb();
                return true;
            }
        } catch (SQLException e) {
            throw new EntityException("Error selecting overlay row for " + ed.getFullEntityName(), e);
        }
    }

    private void insertH2(EntityValueBase evb, EntityDefinition ed) {
        FieldInfo[] all = ed.entityInfo.allFieldInfoArray;
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(ed.getFullTableName()).append(" (");
        StringBuilder values = new StringBuilder();
        int count = 0;
        for (int i = 0; i < all.length; i++) {
            FieldInfo fi = all[i];
            if (fi == null) break;
            if (count > 0) { sql.append(", "); values.append(", "); }
            sql.append(fi.columnName);
            values.append("?");
            count++;
        }
        sql.append(") VALUES (").append(values).append(")");
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < all.length; i++) {
                FieldInfo fi = all[i];
                if (fi == null) break;
                fi.setPreparedStatementValue(ps, i + 1,
                        evb.getValueMap().getByIString(fi.name, fi.index), ed, efi);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new EntityException("Error inserting overlay row for " + ed.getFullEntityName(), e);
        }
    }

    private void upsertH2(EntityValueBase evb, EntityDefinition ed, boolean deleteFirst) {
        if (deleteFirst) deleteH2(evb, ed);
        if (existsInH2(evb, ed)) updateH2(evb, ed);
        else insertH2(evb, ed);
    }

    private void updateH2(EntityValueBase evb, EntityDefinition ed) {
        FieldInfo[] nonPk = ed.entityInfo.nonPkFieldInfoArray;
        FieldInfo[] pks = ed.entityInfo.pkFieldInfoArray;
        StringBuilder sql = new StringBuilder("UPDATE ").append(ed.getFullTableName()).append(" SET ");
        int n = 0;
        for (int i = 0; i < nonPk.length; i++) {
            FieldInfo fi = nonPk[i];
            if (fi == null) break;
            if (n > 0) sql.append(", ");
            sql.append(fi.columnName).append("=?");
            n++;
        }
        if (n == 0) return;
        sql.append(" WHERE ");
        appendPkWhere(sql, pks);
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int idx = 1;
            for (int i = 0; i < nonPk.length; i++) {
                FieldInfo fi = nonPk[i];
                if (fi == null) break;
                fi.setPreparedStatementValue(ps, idx++,
                        evb.getValueMap().getByIString(fi.name, fi.index), ed, efi);
            }
            bindPk(ps, evb, pks, idx);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new EntityException("Error updating overlay row for " + ed.getFullEntityName(), e);
        }
    }

    private void deleteH2(EntityValueBase evb, EntityDefinition ed) {
        if (!tablesCreated.contains(ed.getFullEntityName())) return;
        FieldInfo[] pks = ed.entityInfo.pkFieldInfoArray;
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(ed.getFullTableName()).append(" WHERE ");
        appendPkWhere(sql, pks);
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            bindPk(ps, evb, pks, 1);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new EntityException("Error deleting overlay row for " + ed.getFullEntityName(), e);
        }
    }

    private static void appendPkWhere(StringBuilder sql, FieldInfo[] pks) {
        boolean first = true;
        for (int i = 0; i < pks.length; i++) {
            FieldInfo fi = pks[i];
            if (fi == null) break;
            if (!first) sql.append(" AND ");
            first = false;
            sql.append(fi.columnName).append("=?");
        }
    }

    private void bindPk(PreparedStatement ps, EntityValueBase evb, FieldInfo[] pks, int start) throws SQLException {
        int idx = start;
        EntityDefinition ed = evb.getEntityDefinition();
        for (int i = 0; i < pks.length; i++) {
            FieldInfo fi = pks[i];
            if (fi == null) break;
            fi.setPreparedStatementValue(ps, idx++,
                    evb.getValueMap().getByIString(fi.name, fi.index), ed, efi);
        }
    }
}
