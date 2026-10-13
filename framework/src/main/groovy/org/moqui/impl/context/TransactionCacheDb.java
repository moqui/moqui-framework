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
import org.moqui.impl.entity.OverlayColumnNames;
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
    /** Rows copied from production over the life of one overlay. Bounds memory in a long sim. */
    public static final int TOTAL_COPY_CAP = 200_000;
    private final java.util.concurrent.atomic.AtomicLong copiedRows = new java.util.concurrent.atomic.AtomicLong();

    /** Count a row copied from production and fail the sim when the overlay has grown too large. */
    public void noteCopied() {
        if (copiedRows.incrementAndGet() > TOTAL_COPY_CAP)
            throw new EntityException("TX cache DB copied more than " + TOTAL_COPY_CAP
                    + " production rows in this sim. Narrow the query or end the sim.");
    }

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
    /** Set when a transaction that wrote to this overlay ended rolled back. Writes are not undone, so the sim must end. */
    private volatile String poisonedReason = null;
    private final Set<Object> trackedTransactions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    static {
        try { Class.forName("org.h2.Driver"); }
        catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }

    public TransactionCacheDb(EntityFacadeImpl efi, boolean hold) {
        if (!hold) throw new UnsupportedOperationException("TransactionCacheDb only supports hold mode, which never writes to production");
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
        if (allowsProductionWrite(ed)) return false;
        if (ed.isViewEntity) return true;
        return ed.entityInfo.isEntityDatasourceFactoryImpl;
    }

    /** These stay on the real database during sim. Everything else that this cache does not handle is refused. */
    public boolean allowsProductionWrite(EntityDefinition ed) {
        return ed != null && PRODUCTION_ENTITIES.contains(ed.getFullEntityName());
    }

    /**
     * Entities that stay on the real database during a sim: the LLM's own records (a sim is itself a conversation with
     * call logs and may propose a skill) and sequence banks. An explicit list rather than a package prefix, so a new
     * moqui.llm entity must be classified here or as an overlay entity. LlmEntityClassificationTests enforces that.
     */
    public static final Set<String> PRODUCTION_ENTITIES = java.util.Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
            "moqui.llm.LlmConversation", "moqui.llm.LlmMessage", "moqui.llm.LlmCallLog",
            "moqui.llm.LlmSkill", "moqui.llm.LlmSkillUse", "moqui.llm.LlmLesson", "moqui.llm.LlmCallUsage",
            "moqui.entity.SequenceValueItem")));

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
        noteWrite();
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
        // Production would reject this too. Without the check a sim could create what the real write cannot.
        if (loadFromWorld(evb, ed) != null) {
            throw new EntityException("Tried to create a value that already exists, entity " +
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
        noteWrite();
        if (tombstoneKeys.contains(key)) return true;
        ensureTable(ed);
        if (!existsInH2(evb, ed)) {
            EntityValueBase fromDb = loadFromWorld(evb, ed);
            if (fromDb != null) insertH2(fromDb, ed);
        }
        if (!existsInH2(evb, ed)) {
            // Updating a row that exists nowhere fails in production, so it must fail in the sim as well
            throw new EntityException("Tried to update a value that does not exist, entity " +
                    evb.resolveEntityName() + ", PK " + evb.getPrimaryKeys());
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
        noteWrite();
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
        // A find by name or status, or a key find with extra conditions, cannot be answered from one row by key
        if (!efb.isPurePkFind()) return null;
        Map<String, Object> pk = efb.getSimpleMapPrimaryKeys();
        if (pk == null || pk.isEmpty()) return null;
        // Build the value first so a String condition for a Timestamp or Long key is converted to the field type
        // before it is compared with tombstone keys, which hold typed values.
        EntityValueBase evb = (EntityValueBase) efi.makeValue(ed.getFullEntityName());
        for (Map.Entry<String, Object> e : pk.entrySet()) evb.set(e.getKey(), e.getValue());
        Map<String, Object> key = makeKey(evb);
        if (key != null && tombstoneKeys.contains(key)) {
            return new EntityValueBase.DeletedEntityValue(ed, efi);
        }
        if (!tablesCreated.contains(ed.getFullEntityName())) return null;
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
        noteCopied();
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

    /** Reads must not lock production rows. */
    @Override public boolean isKnownLocked(EntityValueBase evb) { return true; }

    /** Nothing to flush: this overlay never writes to production. */
    @Override
    public void flushCache(boolean clearRead) { }

    @Override
    public void close() {
        if (closed) return;
        try {
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
        String reason = poisonedReason;
        if (reason != null) throw new SimTerminatedException(reason);
    }

    public boolean isPoisoned() { return poisonedReason != null; }
    public String getPoisonedReason() { return poisonedReason; }
    /** Ends the sim from here on. Every later overlay operation throws SimTerminatedException. */
    public void poison(String reason) {
        if (poisonedReason == null) {
            poisonedReason = reason;
            logger.warn("Sim overlay terminated: " + reason);
        }
    }

    /**
     * Call before each overlay write. Registers, once per JTA transaction, a callback that terminates the sim if that
     * transaction ends rolled back, because the overlay writes made in it are not undone. A transaction that never wrote
     * (a validation error before the first write) is not tracked, so the sim carries on after it.
     */
    private void noteWrite() {
        checkOpen();
        try {
            jakarta.transaction.TransactionManager tm = efi.ecfi.transactionFacade.getTransactionManager();
            if (tm == null || tm.getStatus() != jakarta.transaction.Status.STATUS_ACTIVE) return;
            jakarta.transaction.Transaction tx = tm.getTransaction();
            if (tx == null) return;
            synchronized (trackedTransactions) { if (!trackedTransactions.add(tx)) return; }
            tx.registerSynchronization(new jakarta.transaction.Synchronization() {
                @Override public void beforeCompletion() { }
                @Override public void afterCompletion(int status) {
                    synchronized (trackedTransactions) { trackedTransactions.remove(tx); }
                    if (status == jakarta.transaction.Status.STATUS_ROLLEDBACK)
                        poison("A transaction that wrote to the sim was rolled back, and sim writes are not undone, so the sim state "
                                + "can no longer be trusted. The sim has been terminated. Start a new sim to continue testing.");
                }
            });
        } catch (SimTerminatedException e) {
            throw e;
        } catch (Exception e) {
            // Could not register: fail closed rather than let a later rollback go unnoticed
            poison("The sim could not track its transaction (" + e.getMessage() + "), so it has been terminated. Start a new sim.");
            throw new SimTerminatedException(poisonedReason);
        }
    }

    /** True when the sim has created, updated or deleted a row of this entity. */
    public boolean hasChanges(String fullEntityName) {
        for (Set<Map<String, Object>> keys : java.util.Arrays.asList(dirtyCreateKeys, dirtyUpdateKeys, tombstoneKeys)) {
            for (Map<String, Object> key : keys) if (fullEntityName.equals(key.get("_entityName"))) return true;
        }
        return false;
    }

    /**
     * How an aggregate view (sum, count, group by) is answered during a sim. The overlay normally copies rows for a view
     * from the production result, which cannot work when the result rows are themselves aggregates.
     * <ul>
     * <li>If the sim has changed none of the view's member entities, production's answer is right and is used as is.</li>
     * <li>If it has changed one, every member entity is copied whole into the overlay (when each is under the copy cap)
     * and the aggregate is computed there, so production rows and sim changes are both counted.</li>
     * <li>If a changed view has a member too large to copy, this throws and tells the model what to use instead.</li>
     * </ul>
     * @return true when the caller should answer from production, false to answer from the overlay
     */
    public boolean answerAggregateViewFromProduction(EntityDefinition viewEd) {
        if (viewEd == null || !viewEd.isViewEntity || !viewEd.entityInfo.hasFunctionAlias) return false;
        ArrayList<MNode> members = viewEd.getEntityNode().children("member-entity");
        if (members == null) return true;
        boolean changed = false;
        for (MNode member : members) {
            EntityDefinition memberEd = efi.getEntityDefinition(member.attribute("entity-name"));
            if (memberEd != null && hasChanges(memberEd.getFullEntityName())) { changed = true; break; }
        }
        if (!changed) return true;
        beginBypass();
        try {
            for (MNode member : members) {
                EntityDefinition memberEd = efi.getEntityDefinition(member.attribute("entity-name"));
                if (memberEd == null || memberEd.isViewEntity || !handles(memberEd)) continue;
                ensureTable(memberEd);
                long rows = efi.find(memberEd.getFullEntityName()).useCache(false).count();
                if (rows > COPY_CAP)
                    throw new EntityException("The aggregate view " + viewEd.getFullEntityName() + " includes "
                            + memberEd.getFullEntityName() + " (" + rows + " rows), which is too large to copy into the sim "
                            + "for aggregation after this sim changed data. Use a screen transition or reporting service that "
                            + "returns the data, or query the underlying entities directly.");
                EntityList list = efi.find(memberEd.getFullEntityName()).useCache(false).limit(COPY_CAP + 1).list();
                for (int i = 0; i < list.size(); i++) {
                    EntityValue ev = list.get(i);
                    if (ev instanceof EntityValueBase) copyFromProduction((EntityValueBase) ev);
                }
            }
        } finally {
            endBypass();
        }
        return false;
    }

    /** Rows deleted in this overlay. They are still returned by production and shrink a copied page. */
    public int deletedCount() { return tombstoneKeys.size(); }

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
                    EntityList list = efi.find(memberEd.getFullEntityName()).condition(cond).useCache(false)
                            .limit(COPY_CAP + 1).list();
                    int sz = list.size();
                    if (sz > COPY_CAP)
                        throw new EntityException("TX cache DB copy-on-read exceeded " + COPY_CAP +
                                " rows for " + memberEd.getFullEntityName());
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
            sql.append(OverlayColumnNames.column(fi)).append(" ").append(h2SqlType(fi));
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
                sql.append(OverlayColumnNames.column(fi));
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
            sql.append(OverlayColumnNames.column(fi));
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
            sql.append(OverlayColumnNames.column(fi));
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

    /** SET only fields present on the value. A partial update# must not null the other columns. */
    private void updateH2(EntityValueBase evb, EntityDefinition ed) {
        FieldInfo[] nonPk = ed.entityInfo.nonPkFieldInfoArray;
        FieldInfo[] pks = ed.entityInfo.pkFieldInfoArray;
        org.moqui.util.LiteStringMap<Object> values = evb.getValueMap();
        StringBuilder sql = new StringBuilder("UPDATE ").append(ed.getFullTableName()).append(" SET ");
        int n = 0;
        for (int i = 0; i < nonPk.length; i++) {
            FieldInfo fi = nonPk[i];
            if (fi == null) break;
            if (!values.containsKeyIString(fi.name, fi.index) && !values.containsKey(fi.name)) continue;
            if (n > 0) sql.append(", ");
            sql.append(OverlayColumnNames.column(fi)).append("=?");
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
                if (!values.containsKeyIString(fi.name, fi.index) && !values.containsKey(fi.name)) continue;
                fi.setPreparedStatementValue(ps, idx++,
                        values.getByIString(fi.name, fi.index), ed, efi);
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
            sql.append(OverlayColumnNames.column(fi)).append("=?");
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
