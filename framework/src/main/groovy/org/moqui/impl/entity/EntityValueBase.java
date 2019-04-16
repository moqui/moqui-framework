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
package org.moqui.impl.entity;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import org.moqui.Moqui;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.*;
import org.moqui.util.CollectionUtilities;
import org.moqui.util.MNode;
import org.moqui.util.ObjectUtilities;
import org.moqui.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.Nonnull;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;

public abstract class EntityValueBase implements EntityValue {
    protected static final Logger logger = LoggerFactory.getLogger(EntityValueBase.class);

    // these error strings are here for convenience for LocalizedMessage records
    // NOTE: don't change these unless there is a really good reason, will break localization
    private static final String CREATE_ERROR = "Error creating ${entityName} ${primaryKeys}";
    private static final String UPDATE_ERROR = "Error updating ${entityName} ${primaryKeys}";
    private static final String DELETE_ERROR = "Error deleting ${entityName} ${primaryKeys}";
    private static final String REFRESH_ERROR = "Error finding ${entityName} ${primaryKeys}";
    private static final String PLACEHOLDER = "PLHLDR";

    private String entityName;
    final HashMap<String, Object> valueMapInternal = new HashMap<>();

    private transient EntityFacadeImpl efiTransient = null;
    private transient TransactionCache txCacheInternal = null;
    private transient EntityDefinition entityDefinitionTransient = null;

    private transient HashMap<String, Object> dbValueMap = null;
    private transient HashMap<String, Object> oldDbValueMap = null;
    private transient Map<String, Object> internalPkMap = null;
    private transient Map<String, Map<String, String>> localizedByLocaleByField = null;

    private transient boolean modified = false;
    private transient boolean mutable = true;
    private transient boolean isFromDb = false;
    private static final String indentString = "    ";

    /** Default constructor for deserialization ONLY. */
    public EntityValueBase() { }

    public EntityValueBase(EntityDefinition ed, EntityFacadeImpl efip) {
        efiTransient = efip;
        entityName = ed.fullEntityName;
        entityDefinitionTransient = ed;
    }

    @Override public void writeExternal(ObjectOutput out) throws IOException {
        // NOTE: found that the serializer in Hazelcast is REALLY slow with writeUTF(), uses String.chatAt() in a for loop, crazy
        // NOTE2: in Groovy this results in castToType() overhead anyway, so for now use writeUTF/readUTF as other serialization might be more efficient
        out.writeUTF(entityName);
        out.writeObject(valueMapInternal);
    }

    @SuppressWarnings("unchecked")
    @Override public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        entityName = objectInput.readUTF();
        valueMapInternal.putAll((Map<String, Object>) objectInput.readObject());
    }

    protected EntityFacadeImpl getEntityFacadeImpl() {
        // handle null after deserialize; this requires a static reference in Moqui.java or we'll get an error
        if (efiTransient == null) {
            ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory();
            if (ecfi == null) throw new EntityException("No ExecutionContextFactory found, cannot get EntityFacade for new EVB for entity " + entityName);
            efiTransient = ecfi.entityFacade;
        }
        return efiTransient;
    }
    private TransactionCache getTxCache(ExecutionContextFactoryImpl ecfi) {
        if (txCacheInternal == null) txCacheInternal = ecfi.transactionFacade.getTransactionCache();
        return txCacheInternal;
    }
    public EntityDefinition getEntityDefinition() {
        if (entityDefinitionTransient == null)
            entityDefinitionTransient = getEntityFacadeImpl().getEntityDefinition(entityName);
        return entityDefinitionTransient;
    }

    public HashMap<String, Object> getValueMap() { return valueMapInternal; }
    protected HashMap<String, Object> getDbValueMap() { return dbValueMap; }

    protected void setDbValueMap(Map<String, Object> map) {
        dbValueMap = new HashMap<>();
        // copy all fields, including pk to fix false positives in the old approach of only non-pk fields
        FieldInfo[] allFields = getEntityDefinition().entityInfo.allFieldInfoArray;
        for (int i = 0; i < allFields.length; i++) {
            FieldInfo fi = allFields[i];
            if (!map.containsKey(fi.name)) continue;
            Object curValue = map.get(fi.name);
            dbValueMap.put(fi.name, curValue);
            if (!valueMapInternal.containsKey(fi.name)) valueMapInternal.put(fi.name, curValue);
        }
        isFromDb = true;
    }
    public void setSyncedWithDb() {
        oldDbValueMap = dbValueMap;
        dbValueMap = null;
        modified = false;
        isFromDb = true;
    }
    public boolean getIsFromDb() { return isFromDb; }

    @Override public String getEntityName() { return entityName; }
    @Override public String getEntityNamePretty() { return StringUtilities.camelCaseToPretty(getEntityDefinition().getEntityName()); }
    @Override public boolean isModified() { return modified; }
    @Override public boolean isFieldModified(String name) {
        Object valueMapValue = valueMapInternal.getOrDefault(name, PLACEHOLDER);
        // identity compare as alternative to containsKey() call, if is PLACEHOLDER then Map didn't contain the key
        if (valueMapValue == PLACEHOLDER) return false;
        if (dbValueMap == null) return true;
        Object dbValue = dbValueMap.getOrDefault(name, PLACEHOLDER);
        if (dbValue == PLACEHOLDER) return true;
        return (valueMapValue == null && dbValue != null) || (valueMapValue != null && !valueMapValue.equals(dbValue));
        /*
        if (!valueMapInternal.containsKey(name)) return false;
        if (dbValueMap == null || !dbValueMap.containsKey(name)) return true;
        Object valueMapValue = valueMapInternal.get(name);
        Object dbValue = dbValueMap.get(name);
        return (valueMapValue == null && dbValue != null) || (valueMapValue != null && !valueMapValue.equals(dbValue));
        */
    }
    @Override public boolean isFieldSet(String name) { return valueMapInternal.containsKey(name); }
    @Override public boolean isField(String name) { return getEntityDefinition().isField(name); }
    @Override public boolean isMutable() { return mutable; }
    public void setFromCache() { mutable = false; }

    @Override
    public Map<String, Object> getMap() {
        // call get() for each field for localization, etc
        Map<String, Object> theMap = new LinkedHashMap<>();

        EntityDefinition ed = getEntityDefinition();
        FieldInfo[] allFieldInfos = ed.entityInfo.allFieldInfoArray;
        int allFieldInfosSize = allFieldInfos.length;
        for (int i = 0; i < allFieldInfosSize; i++) {
            FieldInfo fieldInfo = allFieldInfos[i];
            Object fieldValue = getKnownField(fieldInfo);
            // NOTE DEJ20151117 also put nulls in Map, make more complete, removed: if (fieldValue != null)
            theMap.put(fieldInfo.name, fieldValue);
        }

        if (ed.isViewEntity) {
            Map<String, MNode> pqExpressionNodeMap = ed.getPqExpressionNodeMap();
            if (pqExpressionNodeMap != null) for (String fieldName : pqExpressionNodeMap.keySet()) {
                theMap.put(fieldName, get(fieldName));
            }
        }

        return theMap;
    }

    @Override
    public Object get(final String name) {
        EntityDefinition ed = getEntityDefinition();

        FieldInfo fieldInfo = ed.getFieldInfo(name);
        if (fieldInfo != null) return getKnownField(fieldInfo);

        // if this is not a valid field name but is a valid relationship name, do a getRelated or getRelatedOne to return an EntityList or an EntityValue
        EntityJavaUtil.RelationshipInfo relInfo = ed.getRelationshipInfo(name);
        // logger.warn("====== get related relInfo: ${relInfo}")
        if (relInfo != null) {
            if (relInfo.isTypeOne) {
                return this.findRelatedOne(name, null, null);
            } else {
                return this.findRelated(name, null, null, null, null);
            }
        }

        // special case, see if this is a alias with a pq-expression, if so evaluate
        if (ed.isViewEntity) {
            MNode pqExprNode = ed.getPqExpressionNode(name);
            if (pqExprNode != null) {
                String pqExpression = pqExprNode.attribute("pq-expression");
                try {
                    EntityFacadeImpl efi = getEntityFacadeImpl();
                    return efi.ecfi.resourceFacade.expression(pqExpression, null, valueMapInternal);
                } catch (Throwable t) {
                    throw new EntityException("Error evaluating pq-expression for " + entityName + "." + name, t);
                }
            }
        }

        // logger.warn("========== relInfo Map keys: ${ed.getRelationshipInfoMap().keySet()}, relInfoList: ${ed.getRelationshipsInfo(false)}")
        throw new EntityException("The name [" + name + "] is not a valid field name or relationship name for entity " + entityName);
    }

    private Object getKnownField(FieldInfo fieldInfo) {
        EntityDefinition ed = fieldInfo.ed;
        String name = fieldInfo.name;
        // if this is a simple field (is field, no l10n, not user field) just get the value right away (vast majority of use)
        if (fieldInfo.isSimple) return valueMapInternal.get(name);

        // if enabled use moqui.basic.LocalizedEntityField for any localized fields
        if (fieldInfo.enableLocalization) {
            Locale locale = getEntityFacadeImpl().ecfi.getEci().userFacade.getLocale();
            String localeStr = locale != null ? locale.toString() : null;
            if (localeStr != null) {
                Object internalValue = valueMapInternal.get(name);

                boolean knownNoLocalized = false;
                if (localizedByLocaleByField == null) {
                    localizedByLocaleByField = new HashMap<>();
                } else {
                    Map<String, String> localizedByLocale = localizedByLocaleByField.get(name);
                    if (localizedByLocale != null) {
                        String cachedLocalized = localizedByLocale.get(localeStr);
                        if (cachedLocalized != null && cachedLocalized.length() > 0) {
                            // logger.warn("======== field ${name}:${internalValue} found cached localized ${cachedLocalized}")
                            return cachedLocalized;
                        } else {
                            // logger.warn("======== field ${name}:${internalValue} known no localized")
                            knownNoLocalized = localizedByLocale.containsKey(localeStr);
                        }
                    }
                }

                if (!knownNoLocalized) {
                    List<String> pks;
                    MNode aliasNode = null;
                    String memberEntityName = null;
                    if (ed.isViewEntity && !ed.entityInfo.isDynamicView) {
                        // NOTE: there are issues with dynamic view entities here, may be possible to fix them but for now not running for EntityDynamicView
                        aliasNode = ed.getFieldNode(name);
                        memberEntityName = ed.getMemberEntityName(aliasNode.attribute("entity-alias"));
                        EntityDefinition memberEd = getEntityFacadeImpl().getEntityDefinition(memberEntityName);
                        pks = memberEd.getPkFieldNames();
                    } else {
                        pks = ed.getPkFieldNames();
                    }

                    if (pks.size() == 1) {
                        String pk = pks.get(0);
                        if (aliasNode != null) {
                            pk = null;
                            Map<String, String> pkToAliasMap = ed.getMePkFieldToAliasNameMap(aliasNode.attribute("entity-alias"));
                            Set<String> pkSet = pkToAliasMap.keySet();
                            if (pkSet.size() == 1) pk = pkToAliasMap.get(pkSet.iterator().next());
                        }

                        String pkValue = pk != null ? (String) valueMapInternal.get(pk) : null;
                        if (pkValue != null) {
                            // logger.warn("======== field ${name}:${internalValue} finding LocalizedEntityField, localizedByLocaleByField=${localizedByLocaleByField}")
                            String entityName = ed.getFullEntityName();
                            String fieldName = name;
                            if (aliasNode != null) {
                                entityName = memberEntityName;
                                final String fieldAttr = aliasNode.attribute("field");
                                fieldName = fieldAttr != null && !fieldAttr.isEmpty() ? fieldAttr : aliasNode.attribute("name");
                                // logger.warn("localizing field for ViewEntity ${ed.fullEntityName} field ${name}, using entityName: ${entityName}, fieldName: ${fieldName}, pkValue: ${pkValue}, locale: ${localeStr}")
                            }

                            EntityFind lefFind = getEntityFacadeImpl().find("moqui.basic.LocalizedEntityField")
                                    .condition("entityName", entityName).condition("fieldName", fieldName)
                                    .condition("pkValue", pkValue).condition("locale", localeStr);
                            EntityValue lefValue = lefFind.useCache(true).one();
                            if (lefValue != null) {
                                String localized = (String) lefValue.get("localized");
                                CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                                return localized;
                            }

                            // no result found, try with shortened locale
                            if (localeStr.contains("_")) {
                                lefFind.condition("locale", localeStr.substring(0, localeStr.indexOf("_")));
                                lefValue = lefFind.useCache(true).one();
                                if (lefValue != null) {
                                    String localized = (String) lefValue.get("localized");
                                    CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                                    return localized;
                                }
                            }

                            // no result found, try "default" locale
                            lefFind.condition("locale", "default");
                            lefValue = lefFind.useCache(true).one();
                            if (lefValue != null) {
                                String localized = (String) lefValue.get("localized");
                                CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                                return localized;
                            }
                        }
                    }

                    // no luck? try getting a localized value from moqui.basic.LocalizedMessage
                    // logger.warn("======== field ${name}:${internalValue} finding LocalizedMessage")
                    EntityFind lmFind = getEntityFacadeImpl().find("moqui.basic.LocalizedMessage")
                            .condition("original", internalValue).condition("locale", localeStr);
                    EntityValue lmValue = lmFind.useCache(true).one();
                    if (lmValue != null) {
                        String localized = (String) lmValue.get("localized");
                        CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                        return localized;
                    }

                    if (localeStr.contains("_")) {
                        lmFind.condition("locale", localeStr.substring(0, localeStr.indexOf("_")));
                        lmValue = lmFind.useCache(true).one();
                        if (lmValue != null) {
                            String localized = (String) lmValue.get("localized");
                            CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                            return localized;
                        }
                    }

                    lmFind.condition("locale", "default");
                    lmValue = lmFind.useCache(true).one();
                    if (lmValue != null) {
                        String localized = (String) lmValue.get("localized");
                        CollectionUtilities.addToMapInMap(name, localeStr, localized, localizedByLocaleByField);
                        return localized;
                    }

                    // we didn't find a localized value, remember that so we don't do the queries again (common case)
                    CollectionUtilities.addToMapInMap(name, localeStr, null, localizedByLocaleByField);
                    // logger.warn("======== field ${name}:${internalValue} remembering no localized, localizedByLocaleByField=${localizedByLocaleByField}")
                }

                return internalValue;
            }
        }


        return valueMapInternal.get(name);
    }

    @Override public Object getNoCheckSimple(String name) { return valueMapInternal.get(name); }

    @Override public Object getOriginalDbValue(String name) {
        return (dbValueMap != null && dbValueMap.containsKey(name)) ? dbValueMap.get(name) : valueMapInternal.get(name);
    }
    protected Object getOldDbValue(String name) {
        if (oldDbValueMap != null && oldDbValueMap.containsKey(name)) return oldDbValueMap.get(name);
        return getOriginalDbValue(name);
    }

    @Override public boolean containsPrimaryKey() { return this.getEntityDefinition().containsPrimaryKey(valueMapInternal); }
    @Override public Map<String, Object> getPrimaryKeys() {
        if (internalPkMap != null) return new HashMap<>(internalPkMap);
        internalPkMap = getEntityDefinition().getPrimaryKeys(this.valueMapInternal);
        return new HashMap<>(internalPkMap);
    }

    public boolean primaryKeyMatches(EntityValueBase evb) {
        if (evb == null) return false;
        Map<String, Object> evbValue = evb.getValueMap();
        ArrayList<String> pkFields = getEntityDefinition().getPkFieldNames();
        int pkFieldsSize = pkFields.size();
        boolean allMatch = true;
        for (int i = 0; i < pkFieldsSize; i++) {
            String pkField = pkFields.get(i);
            Object thisValue = valueMapInternal.get(pkField);
            Object thatValue = evbValue.get(pkField);
            if (thisValue == null) {
                if (thatValue != null) { allMatch = false; break; }
            } else {
                if (!thisValue.equals(thatValue)) { allMatch = false; break; }
            }
        }
        return allMatch;
    }

    @Override public EntityValue set(String name, Object value) { put(name, value); return this; }
    @Override public EntityValue setAll(Map<String, Object> fields) {
        if (!mutable) throw new EntityException("Cannot set fields, this entity value is not mutable (it is read-only)");
        getEntityDefinition().entityInfo.setFieldsEv(fields, this, null);
        return this;
    }
    @Override public EntityValue setString(String name, String value) {
        // this will do a field name check
        ExecutionContextImpl eci = getEntityFacadeImpl().ecfi.getEci();
        Object converted = getEntityDefinition().convertFieldString(name, value, eci);
        putNoCheck(name, converted);
        return this;
    }

    @Override public Boolean getBoolean(String name) { return DefaultGroovyMethods.asType(get(name), Boolean.class); }
    @Override public String getString(String name) {
        EntityDefinition ed = getEntityDefinition();
        FieldInfo fieldInfo = ed.getFieldInfo(name);

        Object valueObj = getKnownField(fieldInfo);
        return fieldInfo.convertToString(valueObj);
    }
    @Override public Timestamp getTimestamp(String name) { return DefaultGroovyMethods.asType(get(name), Timestamp.class); }
    @Override public Time getTime(String name) { return DefaultGroovyMethods.asType(this.get(name), Time.class); }
    @Override public java.sql.Date getDate(String name) { return DefaultGroovyMethods.asType(this.get(name), Date.class); }
    @Override public Long getLong(String name) { return DefaultGroovyMethods.asType(this.get(name), Long.class); }
    @Override public Double getDouble(String name) { return DefaultGroovyMethods.asType(this.get(name), Double.class); }
    @Override public BigDecimal getBigDecimal(String name) { return DefaultGroovyMethods.asType(this.get(name), BigDecimal.class); }

    @Override public byte[] getBytes(String name) {
        Object o = this.get(name);
        if (o == null) return null;
        if (o instanceof SerialBlob) {
            try {
                if (((SerialBlob) o).length() == 0) return new byte[0];
                return ((SerialBlob) o).getBytes(1, (int) ((SerialBlob) o).length());
            } catch (Exception e) {
                throw new EntityException("Error getting bytes for field " + name + " in entity " + entityName, e);
            }
        }

        if (o instanceof byte[]) return (byte[]) o;
        // try groovy...
        return DefaultGroovyMethods.asType(o, byte[].class);
    }
    @Override public EntityValue setBytes(String name, byte[] theBytes) {
        try {
            if (theBytes != null) set(name, new SerialBlob(theBytes));
        } catch (Exception e) {
            throw new EntityException("Error setting bytes for field " + name + " in entity " + entityName, e);
        }
        return this;
    }
    @Override public SerialBlob getSerialBlob(String name) {
        Object o = this.get(name);
        if (o == null) return null;
        if (o instanceof SerialBlob) return (SerialBlob) o;
        try {
            if (o instanceof byte[]) return new SerialBlob((byte[]) o);
        } catch (Exception e) {
            throw new EntityException("Error getting SerialBlob for field " + name + " in entity " + entityName, e);
        }
        // try groovy...
        return DefaultGroovyMethods.asType(o, SerialBlob.class);
    }

    @Override public EntityValue setFields(Map<String, Object> fields, boolean setIfEmpty, String namePrefix, Boolean pks) {
        if (!setIfEmpty && (namePrefix == null || namePrefix.length() == 0)) {
            getEntityDefinition().entityInfo.setFields(fields, this, false, namePrefix, pks);
        } else {
            getEntityDefinition().entityInfo.setFieldsEv(fields, this, pks);
        }

        return this;
    }

    @Override
    public EntityValue setSequencedIdPrimary() {
        EntityDefinition ed = getEntityDefinition();
        EntityFacadeImpl localEfi = getEntityFacadeImpl();
        ArrayList<String> pkFields = ed.getPkFieldNames();

        // get the entity-specific prefix, support string expansion for it too
        String entityPrefix = null;
        String rawPrefix = ed.entityInfo.sequencePrimaryPrefix;
        if (rawPrefix != null && rawPrefix.length() > 0)
            entityPrefix = localEfi.ecfi.resourceFacade.expand(rawPrefix, null, valueMapInternal);
        String sequenceValue = localEfi.sequencedIdPrimaryEd(ed);

        putNoCheck(pkFields.get(0), entityPrefix != null ? entityPrefix + sequenceValue : sequenceValue);
        return this;
    }

    @Override
    public EntityValue setSequencedIdSecondary() {
        EntityDefinition ed = getEntityDefinition();
        List<String> pkFields = ed.getPkFieldNames();
        if (pkFields.size() < 2) throw new EntityException("Cannot call setSequencedIdSecondary() on entity " + entityName + ", must have at least 2 primary key fields.");
        // sequenced field will be the last pk
        final String seqFieldName = pkFields.get(pkFields.size() - 1);
        String paddedLengthStr = ed.getEntityNode().attribute("sequence-secondary-padded-length");
        int paddedLength = 2;
        if (paddedLengthStr != null && paddedLengthStr.length() > 0) paddedLength = Integer.valueOf(paddedLengthStr);

        this.remove(seqFieldName);
        Map<String, Object> otherPkMap = new LinkedHashMap<>();
        getEntityDefinition().entityInfo.setFields(this, otherPkMap, false, null, true);

        // temporarily disable authz for this, just doing lookup to get next value and to allow for a
        //     authorize-skip="create" with authorize-skip of view too this is necessary
        EntityFind ef = getEntityFacadeImpl().find(getEntityName()).selectField(seqFieldName).condition(otherPkMap);
        // logger.warn("TOREMOVE in setSequencedIdSecondary ef WHERE=${ef.getWhereEntityCondition()}")
        EntityList allValues = ef.disableAuthz().list();

        Integer highestSeqVal = null;
        for (EntityValue curValue : allValues) {
            final String currentSeqId = (String) curValue.getNoCheckSimple(seqFieldName);
            if (currentSeqId != null && !currentSeqId.isEmpty()) {
                try {
                    int seqVal = Integer.parseInt(currentSeqId);
                    if (highestSeqVal == null || seqVal > highestSeqVal) highestSeqVal = seqVal;
                } catch (Exception e) {
                    logger.warn("Error in secondary sequenced ID converting SeqId [" + currentSeqId + "] in field [" + seqFieldName + "] from entity [" + getEntityName() + "] to a number: " + e.toString());
                }
            }
        }

        int seqValToUse = highestSeqVal != null ? highestSeqVal + 1 : 1;
        this.set(seqFieldName, StringUtilities.paddedNumber(seqValToUse, paddedLength));
        return this;
    }

    @Override
    public int compareTo(EntityValue that) {
        // nulls go earlier
        // not needed? IDE says never null: if (that == null) return -1;

        // first entity names
        int result = entityName.compareTo(that.getEntityName());
        if (result != 0) return result;

        // next compare all fields (will compare PK fields first, generally first in list)
        ArrayList<String> allFieldNames = getEntityDefinition().getAllFieldNames();
        int allFieldNamesSize = allFieldNames.size();
        for (int i = 0; i < allFieldNamesSize; i++) {
            String pkFieldName = allFieldNames.get(i);
            result = compareFields(that, pkFieldName);
            if (result != 0) return result;
        }

        // all the same, result should be 0
        return result;
    }
    @SuppressWarnings("unchecked")
    private int compareFields(EntityValue that, String name) {
        Comparable thisVal = (Comparable) this.valueMapInternal.get(name);
        Comparable thatVal = (Comparable) that.get(name);
        // NOTE: nulls go earlier in the list
        if (thisVal == null) {
            return thatVal == null ? 0 : 1;
        } else {
            return (thatVal == null ? -1 : thisVal.compareTo(thatVal));
        }
    }

    @Override
    public boolean mapMatches(Map<String, Object> theMap) {
        boolean matches = true;
        for (Entry<String, Object> entry : theMap.entrySet()) {
            if (!entry.getValue().equals(this.valueMapInternal.get(entry.getKey()))) {
                matches = false;
                break;
            }
        }
        return matches;
    }

    @Override
    public EntityValue createOrUpdate() {
        boolean pkModified = false;
        if (isFromDb) {
            pkModified = (getEntityDefinition().getPrimaryKeys(this.valueMapInternal).equals(getEntityDefinition().getPrimaryKeys(this.dbValueMap)));
        } else {
            // make sure PK fields with defaults are filled in BEFORE doing the refresh to see if it exists
            checkSetFieldDefaults(getEntityDefinition(), getEntityFacadeImpl().ecfi.getEci(), true);
        }

        if ((isFromDb && !pkModified) || this.cloneValue().refresh()) {
            return update();
        } else {
            return create();
        }
    }

    @Override
    public EntityValue store() { return createOrUpdate(); }

    private void handleAuditLog(boolean isUpdate, Map oldValues, EntityDefinition ed, ExecutionContextImpl ec) {
        if ((isUpdate && oldValues == null) || !ed.entityInfo.needsAuditLog || ec.artifactExecutionFacade.entityAuditLogDisabled()) return;

        Timestamp nowTimestamp = ec.userFacade.getNowTimestamp();

        Map<String, Object> pksValueMap = new HashMap<>();
        addThreeFieldPkValues(pksValueMap, ed);

        FieldInfo[] fieldInfoList = ed.entityInfo.allFieldInfoArray;
        for (int i = 0; i < fieldInfoList.length; i++) {
            FieldInfo fieldInfo = fieldInfoList[i];
            boolean isLogUpdate = "update".equals(fieldInfo.enableAuditLog);
            if ((!isLogUpdate && "true".equals(fieldInfo.enableAuditLog)) || (isUpdate && isLogUpdate)) {
                String fieldName = fieldInfo.name;

                // is there a new value? if not continue
                if (!this.valueMapInternal.containsKey(fieldName)) continue;

                Object value = get(fieldName);
                Object oldValue = oldValues != null ? oldValues.get(fieldName) : null;
                // if set to log updates and old value is null don't consider it an update (is initial set of value)
                if (isLogUpdate && oldValue == null) continue;
                if (isUpdate) {
                    // if isUpdate but old value == new value, then it hasn't been updated, so skip it
                    if (value == null) {
                        if (oldValue == null) continue;
                    } else {
                        if (value instanceof BigDecimal && oldValue instanceof BigDecimal) {
                            // better handling for BigDecimal, perhaps others
                            if (((BigDecimal) value).compareTo((BigDecimal) oldValue) == 0) continue;
                        } else {
                            if (value.equals(oldValue)) continue;
                        }
                    }
                } else {
                    // if it's a create and there is no value don't log a change
                    if (value == null) continue;
                }
                // logger.warn("EntityAuditLog field " + fieldName + " old " + oldValue + " (" + (oldValue != null ? oldValue.getClass().getName() : "null") + ") new " + value + " (" + (value != null ? value.getClass().getName() : "null") + ")");

                // don't skip for this, if a field was reset then we want to record that: if (!value) continue

                // check for a changeReason
                String changeReason = null;
                Object changeReasonObj = ec.contextStack.getByString(fieldName.concat("_changeReason"));
                if (changeReasonObj != null) {
                    changeReason = changeReasonObj.toString();
                    if (changeReason.isEmpty()) changeReason = null;
                }

                String stackNameString = ec.artifactExecutionFacade.getStackNameString();
                if (stackNameString.length() > 4000) stackNameString = stackNameString.substring(0, 4000);
                LinkedHashMap<String, Object> parms = new LinkedHashMap<>();
                parms.put("changedEntityName", getEntityName());
                parms.put("changedFieldName", fieldName);
                parms.put("newValueText", ObjectUtilities.toPlainString(value));
                if (changeReason != null) parms.put("changeReason", changeReason);
                parms.put("changedDate", nowTimestamp);
                parms.put("changedByUserId", ec.getUser().getUserId());
                parms.put("changedInVisitId", ec.getUser().getVisitId());
                parms.put("artifactStack", stackNameString);
                if (oldValue != null) parms.put("oldValueText", ObjectUtilities.toPlainString(oldValue));
                parms.putAll(pksValueMap);

                // logger.warn("TOREMOVE: in handleAuditLog for [${ed.entityName}.${fieldName}] value=[${value}], oldValue=[${oldValue}], oldValues=[${oldValues}]", new Exception("AuditLog location"))

                // NOTE: if this is changed to async the time zone on nowTimestamp gets messed up (user's time zone lost)
                getEntityFacadeImpl().ecfi.serviceFacade.sync().name("create#moqui.entity.EntityAuditLog")
                        .parameters(parms).disableAuthz().call();
            }
        }
    }

    private void addThreeFieldPkValues(Map<String, Object> parms, EntityDefinition ed) {
        // get pkPrimaryValue, pkSecondaryValue, pkRestCombinedValue (just like the AuditLog stuff)
        ArrayList<FieldInfo> pkFieldList = new ArrayList<>();
        Collections.addAll(pkFieldList, ed.entityInfo.pkFieldInfoArray);
        FieldInfo firstPkField = pkFieldList.size() > 0 ? pkFieldList.remove(0) : null;
        FieldInfo secondPkField = pkFieldList.size() > 0 ? pkFieldList.remove(0) : null;
        StringBuilder pkTextSb = new StringBuilder();
        for (int i = 0; i < pkFieldList.size(); i++) {
            FieldInfo curFieldInfo = pkFieldList.get(i);
            if (i > 0) pkTextSb.append(",");
            pkTextSb.append(curFieldInfo.name).append(":'")
                    .append(EntityDefinition.getFieldStringForFile(curFieldInfo, getKnownField(curFieldInfo))).append("'");
        }
        String pkText = pkTextSb.toString();

        if (firstPkField != null) parms.put("pkPrimaryValue", getKnownField(firstPkField));
        if (secondPkField != null) parms.put("pkSecondaryValue", getKnownField(secondPkField));
        if (!pkText.isEmpty()) parms.put("pkRestCombinedValue", pkText);
    }

    @Override
    public EntityList findRelated(final String relationshipName, Map<String, Object> byAndFields, List<String> orderBy,
                                  Boolean useCache, Boolean forUpdate) {
        EntityJavaUtil.RelationshipInfo relInfo = getEntityDefinition().getRelationshipInfo(relationshipName);
        if (relInfo == null) throw new EntityException("Relationship " + relationshipName + " not found in entity " + entityName);
        return findRelated(relInfo, byAndFields, orderBy, useCache, forUpdate);
    }

    private EntityList findRelated(final EntityJavaUtil.RelationshipInfo relInfo, Map<String, Object> byAndFields,
                                   List<String> orderBy, Boolean useCache, Boolean forUpdate) {
        String relatedEntityName = relInfo.relatedEntityName;
        Map<String, String> keyMap = relInfo.keyMap;
        if (keyMap == null || keyMap.size() == 0) throw new EntityException("Relationship " + relInfo.relationshipName + " in entity " + entityName + " has no key-map sub-elements and no default values");

        // make a Map where the key is the related entity's field name, and the value is the value from this entity
        Map<String, Object> condMap = new HashMap<>();
        for (Entry<String, String> entry : keyMap.entrySet())
            condMap.put(entry.getValue(), valueMapInternal.get(entry.getKey()));
        if (relInfo.keyValueMap != null) {
            for (Map.Entry<String, String> keyValueEntry: relInfo.keyValueMap.entrySet())
                condMap.put(keyValueEntry.getKey(), keyValueEntry.getValue());
        }
        if (byAndFields != null && byAndFields.size() > 0) condMap.putAll(byAndFields);

        EntityFind find = getEntityFacadeImpl().find(relatedEntityName);
        return find.condition(condMap).orderBy(orderBy).useCache(useCache).forUpdate(forUpdate != null ? forUpdate : false).list();
    }

    @Override
    public EntityValue findRelatedOne(final String relationshipName, Boolean useCache, Boolean forUpdate) {
        EntityJavaUtil.RelationshipInfo relInfo = getEntityDefinition().getRelationshipInfo(relationshipName);
        if (relInfo == null) throw new EntityException("Relationship " + relationshipName + " not found in entity " + entityName);
        return findRelatedOne(relInfo, useCache, forUpdate);
    }

    private EntityValue findRelatedOne(final EntityJavaUtil.RelationshipInfo relInfo, Boolean useCache, Boolean forUpdate) {
        String relatedEntityName = relInfo.relatedEntityName;
        Map<String, String> keyMap = relInfo.keyMap;
        if (keyMap == null || keyMap.size() == 0) throw new EntityException("Relationship " + relInfo.relationshipName + " in entity " + entityName + " has no key-map sub-elements and no default values");

        // make a Map where the key is the related entity's field name, and the value is the value from this entity
        Map<String, Object> condMap = new HashMap<>();
        for (Entry<String, String> entry : keyMap.entrySet()) condMap.put(entry.getValue(), valueMapInternal.get(entry.getKey()));
        if (relInfo.keyValueMap != null) {
            for (Map.Entry<String, String> keyValueEntry: relInfo.keyValueMap.entrySet())
                condMap.put(keyValueEntry.getKey(), keyValueEntry.getValue());
        }

        // logger.warn("========== findRelatedOne ${relInfo.relationshipName} keyMap=${keyMap}, condMap=${condMap}")

        EntityFind find = getEntityFacadeImpl().find(relatedEntityName);
        return find.condition(condMap).useCache(useCache).forUpdate(forUpdate != null ? forUpdate : false).one();
    }

    @Override
    public long findRelatedCount(final String relationshipName, Boolean useCache) {
        EntityJavaUtil.RelationshipInfo relInfo = getEntityDefinition().getRelationshipInfo(relationshipName);
        if (relInfo == null) throw new EntityException("Relationship " + relationshipName + " not found in entity " + entityName);

        String relatedEntityName = relInfo.relatedEntityName;
        Map<String, String> keyMap = relInfo.keyMap;
        if (keyMap == null || keyMap.size() == 0) throw new EntityException("Relationship " + relInfo.relationshipName + " in entity " + entityName + " has no key-map sub-elements and no default values");

        // make a Map where the key is the related entity's field name, and the value is the value from this entity
        Map<String, Object> condMap = new HashMap<>();
        for (Entry<String, String> entry : keyMap.entrySet()) condMap.put(entry.getValue(), valueMapInternal.get(entry.getKey()));
        if (relInfo.keyValueMap != null) {
            for (Map.Entry<String, String> keyValueEntry: relInfo.keyValueMap.entrySet())
                condMap.put(keyValueEntry.getKey(), keyValueEntry.getValue());
        }

        EntityFind find = getEntityFacadeImpl().find(relatedEntityName);
        return find.condition(condMap).useCache(useCache).count();
    }

    @Override
    public EntityList findRelatedFk(Set<String> skipEntities) {
        EntityList relatedList = new EntityListImpl(getEntityFacadeImpl());
        ArrayList<EntityJavaUtil.RelationshipInfo> relInfoList = getEntityDefinition().getRelationshipsInfo(false);
        int relInfoListSize = relInfoList.size();
        for (int i = 0; i < relInfoListSize; i++) {
            EntityJavaUtil.RelationshipInfo relInfo = relInfoList.get(i);
            EntityJavaUtil.RelationshipInfo reverseInfo = relInfo.findReverse();
            if (reverseInfo == null || !reverseInfo.isTypeOne || (skipEntities != null && (skipEntities.contains(reverseInfo.fromEd.fullEntityName) ||
                    skipEntities.contains(reverseInfo.fromEd.getShortAlias()) || skipEntities.contains(reverseInfo.fromEd.getEntityName())))) continue;
            EntityList curList = findRelated(relInfo, null, null, null, null);
            relatedList.addAll(curList);
        }
        return relatedList;
    }

    @Override
    public void deleteRelated(String relationshipName) {
        // NOTE: this does a select for update, may consider not doing that by default
        EntityList relatedList = findRelated(relationshipName, null, null, false, true);
        for (EntityValue relatedValue : relatedList) relatedValue.delete();
    }

    @Override
    public boolean deleteWithRelated(Set<String> relationshipsToDelete) {
        if (relationshipsToDelete == null) relationshipsToDelete = new HashSet<>();
        ArrayList<EntityJavaUtil.RelationshipInfo> relInfoList = getEntityDefinition().getRelationshipsInfo(false);
        int relInfoListSize = relInfoList.size();

        // look for related records that exist and that we won't delete, if any return true
        boolean foundNonDeleteRelated = false;
        for (int i = 0; i < relInfoListSize; i++) {
            EntityJavaUtil.RelationshipInfo relInfo = relInfoList.get(i);
            if (relInfo.isTypeOne) continue;
            if (relationshipsToDelete.contains(relInfo.shortAlias) || relationshipsToDelete.contains(relInfo.relationshipName)) continue;

            if (findRelatedCount(relInfo.relationshipName, false) > 0) {
                if (logger.isInfoEnabled()) logger.info("Not deleting entity " + entityName + " value with PK " + getPrimaryKeys() + ", found record in relationship " + relInfo.relationshipName);
                foundNonDeleteRelated = true;
                break;
            }
        }
        if (foundNonDeleteRelated) return false;

        // delete related records to delete
        for (String delRelName : relationshipsToDelete) deleteRelated(delRelName);
        // delete this record
        delete();
        // done, successful delete
        return true;
    }

    @Override
    public void deleteWithCascade(Set<String> clearRefEntities, Set<String> validateAllowDeleteEntities) {
        ArrayList<EntityJavaUtil.RelationshipInfo> relInfoList = getEntityDefinition().getRelationshipsInfo(false);
        int relInfoListSize = relInfoList.size();
        for (int i = 0; i < relInfoListSize; i++) {
            // find relationships with a type one reverse (relationships for records that depend on this)
            EntityJavaUtil.RelationshipInfo relInfo = relInfoList.get(i);
            EntityJavaUtil.RelationshipInfo reverseInfo = relInfo.findReverse();
            if (reverseInfo == null || !reverseInfo.isTypeOne) continue;
            // see if we should clear ref fields or delete
            EntityDefinition relEd = relInfo.relatedEd;
            boolean clearRef = clearRefEntities != null && (clearRefEntities.contains(relEd.fullEntityName) ||
                    clearRefEntities.contains(relEd.getShortAlias()) || clearRefEntities.contains(relEd.getEntityName()));
            // find records
            EntityList relList = findRelated(relInfo, null, null, null, null);
            int relListSize = relList.size();
            for (int j = 0; j < relListSize; j++) {
                EntityValue relVal = relList.get(j);
                if (clearRef) {
                    for (String fieldName : reverseInfo.keyMap.keySet()) {
                        if (relEd.isPkField(fieldName)) throw new EntityException("In deleteWithCascade on entity " + getEntityName() + " related entity " + relEd.fullEntityName + " is in the clear ref set but field " + fieldName + " is a primary key field and cannot be cleared");
                        relVal.set(fieldName, null);
                    }
                    relVal.update();
                } else {
                    // if we should validate entities we are attempting to delete do that now
                    if (validateAllowDeleteEntities != null && !validateAllowDeleteEntities.contains(relEd.fullEntityName))
                        throw new EntityException("Cannot delete " + getEntityNamePretty() + " " + getPrimaryKeys() + ", found " + relVal.getEntityNamePretty() + " " + relVal.getPrimaryKeys() + " that depends on it");
                    // delete with cascade
                    relVal.deleteWithCascade(clearRefEntities, validateAllowDeleteEntities);
                }
            }
        }
        // delete this record
        delete();
    }

    @Override
    public boolean checkFks(boolean insertDummy) {
        boolean noneMissing = true;
        ExecutionContextImpl ec = getEntityFacadeImpl().ecfi.getEci();
        for (EntityJavaUtil.RelationshipInfo relInfo : getEntityDefinition().getRelationshipsInfo(false)) {
            if (!"one".equals(relInfo.type)) continue;

            EntityValue value = findRelatedOne(relInfo, false, false);
            // if (getEntityName().contains("foo")) logger.info("Checking fk " + getEntityName() + ':' + relInfo.relationshipName + " value: " + value);
            if (value == null) {
                if (insertDummy) {
                    noneMissing = false;
                    EntityValue newValue = relInfo.relatedEd.makeEntityValue();
                    if (relInfo.relatedEd.entityInfo.hasFieldDefaults && newValue instanceof EntityValueBase)
                        ((EntityValueBase) newValue).checkSetFieldDefaults(relInfo.relatedEd, ec, null);
                    Map<String, String> keyMap = relInfo.keyMap;
                    if (keyMap == null || keyMap.isEmpty()) throw new EntityException("Relationship " + relInfo.relationshipName + " in entity " + entityName + " has no key-map sub-elements and no default values");

                    // make a Map where the key is the related entity's field name, and the value is the value from this entity
                    for (Entry<String, String> entry : keyMap.entrySet())
                        newValue.set(entry.getValue(), valueMapInternal.get(entry.getKey()));

                    if (newValue.containsPrimaryKey()) {
                        newValue.checkFks(true);
                        newValue.create();
                        logger.warn("Created dummy " + newValue.getEntityName() + " PK " + newValue.getPrimaryKeys());
                    }
                } else {
                    return false;
                }
            }
        }
        return noneMissing;
    }

    @Override
    @SuppressWarnings("unchecked")
    public long checkAgainstDatabase(List<String> messages) {
        long fieldsChecked = 0;
        try {
            EntityValue dbValue = this.cloneValue();
            if (!dbValue.refresh()) {
                messages.add("Entity " + getEntityName() + " record not found for primary key " + getPrimaryKeys());
                return 0;
            }

            for (String nonpkFieldName : this.getEntityDefinition().getNonPkFieldNames()) {
                // skip the lastUpdatedStamp field
                if ("lastUpdatedStamp".equals(nonpkFieldName)) continue;

                final Object checkFieldValue = this.get(nonpkFieldName);
                final Object dbFieldValue = dbValue.get(nonpkFieldName);

                // use compareTo if available, generally more lenient (for BigDecimal ignores scale, etc)
                if (checkFieldValue != null) {
                    boolean areSame = true;
                    if (checkFieldValue instanceof Comparable && dbFieldValue != null) {
                        Comparable cfComp = (Comparable) checkFieldValue;
                        if (cfComp.compareTo(dbFieldValue) != 0) areSame = false;
                    } else {
                        if (!checkFieldValue.equals(dbFieldValue)) areSame = false;
                    }
                    if (!areSame) messages.add("Field " + getEntityName() + "." + nonpkFieldName + " did not match; check (file) value [" + checkFieldValue + "], db value [" + dbFieldValue + "] for primary key " + getPrimaryKeys());
                }
                fieldsChecked++;
            }
        } catch (EntityException e) {
            throw e;
        } catch (Throwable t) {
            String errMsg = "Error checking entity " + getEntityName() + " with pk " + getPrimaryKeys() + ": " + t.toString();
            messages.add(errMsg);
            logger.error(errMsg, t);
        }

        return fieldsChecked;
    }

    @Override
    public Element makeXmlElement(Document document, String prefix) {
        if (prefix == null) prefix = "";
        Element element = null;
        if (document != null) element = document.createElement(prefix + entityName);
        if (element == null) return null;

        for (String fieldName : getEntityDefinition().getAllFieldNames()) {
            String value = getString(fieldName);
            if (value != null && !value.isEmpty()) {
                if (value.contains("\n") || value.contains("\r")) {
                    Element childElement = document.createElement(fieldName);
                    element.appendChild(childElement);
                    childElement.appendChild(document.createCDATASection(value));
                } else {
                    element.setAttribute(fieldName, value);
                }
            }
        }
        return element;
    }

    @Override
    public int writeXmlText(Writer pw, String prefix, int dependentLevels) {
        Map<String, Object> plainMap = getPlainValueMap(dependentLevels);
        EntityDefinition ed = getEntityDefinition();
        try {
            return plainMapXmlWriter(pw, prefix, ed.getShortOrFullEntityName(), plainMap, 1);
        } catch (Exception e) {
            throw new EntityException("Error writing XML test for entity " + entityName + " dependent levels " + dependentLevels);
        }
    }

    @Override
    public int writeXmlTextMaster(Writer pw, String prefix, String masterName) {
        Map<String, Object> plainMap = getMasterValueMap(masterName);
        EntityDefinition ed = getEntityDefinition();
        try {
            return plainMapXmlWriter(pw, prefix, ed.getShortOrFullEntityName(), plainMap, 1);
        } catch (Exception e) {
            throw new EntityException("Error writing XML test for entity " + entityName + " master " + masterName);
        }
    }

    @SuppressWarnings("unchecked")
    private static int plainMapXmlWriter(Writer pw, String prefix, String objectName, Map<String, Object> plainMap, int level) throws IOException, SerialException {
        if (prefix == null) prefix = "";
        // if a CDATA element is needed for a field it goes in this Map to be added at the end
        Map<String, String> cdataMap = new LinkedHashMap<>();
        Map<String, Object> subPlainMap = new LinkedHashMap<>();
        String curEntity = objectName != null && objectName.length() > 0 ? objectName : (String) plainMap.get("_entity");

        for (int i = 0; i < level; i++) pw.append(indentString);
        // mostly for relationship names, see opposite code in the EntityDataLoaderImpl.startElement
        if (curEntity.contains("#")) curEntity = curEntity.replace("#", "-");
        pw.append("<").append(prefix).append(curEntity);

        int valueCount = 1;
        for (Entry<String, Object> entry : plainMap.entrySet()) {
            String fieldName = entry.getKey();
            // leave this out, not needed for XML where the element name represents the entity or relationship
            if ("_entity".equals(fieldName)) continue;
            Object fieldValue = entry.getValue();

            if (fieldValue instanceof Map || fieldValue instanceof List) {
                subPlainMap.put(fieldName, fieldValue);
                continue;
            } else if (fieldValue instanceof byte[]) {
                cdataMap.put(fieldName, Base64.getEncoder().encodeToString((byte[]) fieldValue));
                continue;
            } else if (fieldValue instanceof SerialBlob) {
                if (((SerialBlob) fieldValue).length() == 0) continue;
                byte[] objBytes = ((SerialBlob) fieldValue).getBytes(1, (int) ((SerialBlob) fieldValue).length());
                cdataMap.put(fieldName, Base64.getEncoder().encodeToString(objBytes));
                continue;
            }

            String valueStr = ObjectUtilities.toPlainString(fieldValue);
            if (valueStr == null || valueStr.isEmpty()) continue;
            if (valueStr.contains("\n") || valueStr.contains("\r") || valueStr.length() > 255) {
                cdataMap.put(fieldName, valueStr);
                continue;
            }

            pw.append(" ").append(fieldName).append("=\"");
            pw.append(StringUtilities.encodeForXmlAttribute(valueStr)).append("\"");
        }


        if (cdataMap.size() == 0 && subPlainMap.size() == 0) {
            // self-close the entity element
            pw.append("/>\n");
        } else {
            pw.append(">\n");

            // CDATA sub-elements
            for (Entry<String, String> entry : cdataMap.entrySet()) {
                pw.append(indentString).append(indentString);
                pw.append("<").append(entry.getKey()).append(">");
                pw.append("<![CDATA[").append(entry.getValue()).append("]]>");
                pw.append("</").append(entry.getKey()).append(">\n");
            }

            // related/dependent sub-elements
            for (Entry<String, Object> entry : subPlainMap.entrySet()) {
                final String entryKey = entry.getKey();
                Object entryVal = entry.getValue();
                if (entryVal instanceof List) {
                    for (Object listEntry : (List) entryVal) {
                        if (listEntry instanceof Map) {
                            valueCount += plainMapXmlWriter(pw, prefix, entryKey, (Map) listEntry, level + 1);
                        } else {
                            logger.warn("In entity auto create for entity " + curEntity + " found list for sub-object " + entryKey + " with a non-Map entry: " + String.valueOf(listEntry));
                        }
                    }
                } else if (entryVal instanceof Map) {
                    valueCount += plainMapXmlWriter(pw, prefix, entryKey, (Map) entryVal, level + 1);
                }
            }

            // close the entity element
            for (int i = 0; i < level; i++) pw.append(indentString);
            pw.append("</").append(curEntity).append(">\n");
        }

        return valueCount;
    }

    @Override
    public Map<String, Object> getPlainValueMap(int dependentLevels) {
        return internalPlainValueMap(dependentLevels, null);
    }

    private Map<String, Object> internalPlainValueMap(int dependentLevels, Set<String> parentPkFields) {
        Map<String, Object> vMap = new HashMap<>(valueMapInternal);
        CollectionUtilities.removeNullsFromMap(vMap);
        if (parentPkFields != null) for (String pkField : parentPkFields) vMap.remove(pkField);
        EntityDefinition ed = getEntityDefinition();
        vMap.put("_entity", ed.getShortOrFullEntityName());

        if (dependentLevels > 0) {
            Set<String> curPkFields = new HashSet<>(ed.getPkFieldNames());
            // keep track of all parent PK field names, even not part of this entity's PK, they will be inherited when read
            if (parentPkFields != null) curPkFields.addAll(parentPkFields);

            List<EntityJavaUtil.RelationshipInfo> relInfoList = getEntityDefinition().getRelationshipsInfo(true);
            for (EntityJavaUtil.RelationshipInfo relInfo : relInfoList) {
                String relationshipName = relInfo.relationshipName;
                final String alias = relInfo.shortAlias;
                String entryName = alias != null && !alias.isEmpty() ? alias : relationshipName;
                if (relInfo.isTypeOne) {
                    EntityValue relEv = findRelatedOne(relationshipName, null, false);
                    if (relEv != null)
                        vMap.put(entryName, ((EntityValueBase) relEv).internalPlainValueMap(dependentLevels - 1, curPkFields));
                } else {
                    EntityList relList = findRelated(relationshipName, null, null, null, false);
                    if (relList != null && !relList.isEmpty()) {
                        List<Map> plainRelList = new ArrayList<>();
                        for (EntityValue relEv : relList) {
                            plainRelList.add(((EntityValueBase) relEv).internalPlainValueMap(dependentLevels - 1, curPkFields));
                        }
                        vMap.put(entryName, plainRelList);
                    }
                }
            }
        }

        return vMap;
    }

    @Override
    public Map<String, Object> getMasterValueMap(final String name) {
        EntityDefinition.MasterDefinition masterDefinition = getEntityDefinition().getMasterDefinition(name);
        if (masterDefinition == null)
            throw new EntityException("No master definition found for name [" + name + "] in entity [" + entityName + "]");
        return internalMasterValueMap(masterDefinition.getDetailList(), null, null);
    }

    private Map<String, Object> internalMasterValueMap(ArrayList<EntityDefinition.MasterDetail> detailList, Set<String> parentPkFields, EntityJavaUtil.RelationshipInfo parentRelInfo) {
        Map<String, Object> vMap = new HashMap<>(valueMapInternal);
        CollectionUtilities.removeNullsFromMap(vMap);
        if (parentPkFields != null) {
            if (parentRelInfo != null) {
                // handle cases like the Product toAssocs relationship where ProductAssoc.productId != Product.productId, needs to look at relationship field map
                for (String pkField : parentPkFields) {
                    String relatedName = parentRelInfo.keyMap.get(pkField);
                    if (pkField.equals(relatedName)) vMap.remove(pkField);
                }
            } else {
                for (String pkField : parentPkFields) vMap.remove(pkField);
            }
        }
        EntityDefinition ed = getEntityDefinition();
        vMap.put("_entity", ed.getShortOrFullEntityName());

        if (detailList != null && !detailList.isEmpty()) {
            Set<String> curPkFields = new HashSet<>(ed.getPkFieldNames());
            // keep track of all parent PK field names, even not part of this entity's PK, they will be inherited when read
            if (parentPkFields != null) curPkFields.addAll(parentPkFields);

            int detailListSize = detailList.size();
            for (int i = 0; i < detailListSize; i++) {
                EntityDefinition.MasterDetail detail = detailList.get(i);

                EntityJavaUtil.RelationshipInfo relInfo = detail.getRelInfo();
                String relationshipName = relInfo.relationshipName;
                final String relAlias = relInfo.shortAlias;
                String entryName = relAlias != null && !relAlias.isEmpty() ? relAlias : relationshipName;
                if (relInfo.isTypeOne) {
                    EntityValue relEv = findRelatedOne(relationshipName, null, false);
                    if (relEv != null) vMap.put(entryName, ((EntityValueBase) relEv).internalMasterValueMap(detail.getDetailList(), curPkFields, relInfo));
                } else {
                    EntityList relList = findRelated(relationshipName, null, null, null, false);
                    if (relList != null && !relList.isEmpty()) {
                        List<Map> plainRelList = new ArrayList<>();
                        int relListSize = relList.size();
                        for (int rlIndex = 0; rlIndex < relListSize; rlIndex++) {
                            EntityValue relEv = relList.get(rlIndex);
                            plainRelList.add(((EntityValueBase) relEv).internalMasterValueMap(detail.getDetailList(), curPkFields, relInfo));
                        }
                        vMap.put(entryName, plainRelList);
                    }
                }
            }
        }

        return vMap;
    }

    @Override public int size() { return valueMapInternal.size(); }
    @Override public boolean isEmpty() { return valueMapInternal.isEmpty(); }
    @Override public boolean containsKey(Object o) { return o instanceof CharSequence && valueMapInternal.containsKey(o.toString()); }
    @Override public boolean containsValue(Object o) { return values().contains(o); }
    @Override public Object get(Object o) {
        if (o instanceof CharSequence) {
            // This may throw an exception, and let it; the Map interface doesn't provide for EntityException
            //   but it is far more useful than a log message that is likely to be ignored.
            return this.get(o.toString());
        } else {
            return null;
        }
    }
    @Override public Object put(final String name, Object value) {
        if (!getEntityDefinition().isField(name)) throw new EntityException("The field name " + name + " is not valid for entity " + entityName);
        return putNoCheck(name, value);
    }
    public Object putNoCheck(final String name, Object value) {
        if (!mutable) throw new EntityException("Cannot set field " + name + ", this entity value is not mutable (it is read-only)");
        Object curValue = null;
        if (isFromDb) {
            curValue = valueMapInternal.get(name);
            if (curValue == null) {
                if (value != null) modified = true;
            } else {
                if (!curValue.equals(value)) {
                    modified = true;
                    if (dbValueMap == null) dbValueMap = new HashMap<>();
                    dbValueMap.put(name, curValue);
                }
            }
        } else {
            modified = true;
        }

        valueMapInternal.put(name, value);
        return curValue;
    }

    @Override public Object remove(Object o) {
        if (o instanceof CharSequence) {
            String name = o.toString();
            if (valueMapInternal.containsKey(name)) modified = true;
            return valueMapInternal.remove(name);
        } else {
            return null;
        }
    }

    @Override public void putAll(Map<? extends String, ?> map) {
        for (Entry entry : map.entrySet()) put((String) entry.getKey(), entry.getValue());
    }

    @Override public void clear() { modified = true; valueMapInternal.clear(); }
    @Override public @Nonnull Set<String> keySet() { return new HashSet<>(getEntityDefinition().getAllFieldNames()); }
    @Override public @Nonnull Collection<Object> values() {
        // everything needs to go through the get method, so iterate through the fields and get the values
        List<String> allFieldNames = getEntityDefinition().getAllFieldNames();
        List<Object> values = new ArrayList<>(allFieldNames.size());
        for (String fieldName : allFieldNames) values.add(get(fieldName));
        return values;
    }

    @Override public @Nonnull Set<Entry<String, Object>> entrySet() {
        // everything needs to go through the get method, so iterate through the fields and get the values
        FieldInfo[] allFieldInfos = getEntityDefinition().entityInfo.allFieldInfoArray;
        Set<Entry<String, Object>> entries = new HashSet<>();
        int allFieldInfosSize = allFieldInfos.length;
        for (int i = 0; i < allFieldInfosSize; i++) {
            FieldInfo fi = allFieldInfos[i];
            entries.add(new EntityFieldEntry(fi, this));
        }
        return entries;
    }

    @Override public boolean equals(Object obj) {
        if (obj == null || !obj.getClass().equals(this.getClass())) return false;
        // reuse the compare method
        return this.compareTo((EntityValue) obj) == 0;
    }

    // NOTE: consider caching the hash code in the future for performance
    @Override public int hashCode() { return entityName.hashCode() + valueMapInternal.hashCode(); }
    @Override public String toString() { return "[" + entityName + ": " + valueMapInternal.toString() + "]"; }
    @Override public Object clone() { return cloneValue(); }
    @Override public abstract EntityValue cloneValue();
    public abstract EntityValue cloneDbValue(boolean getOld);

    private boolean doDataFeed(ExecutionContextImpl ec) {
        if (ec.artifactExecutionFacade.entityDataFeedDisabled()) return false;
        // skip ArtifactHitBin, causes funny recursion
        return !"moqui.server.ArtifactHitBin".equals(entityName);
    }

    private void checkSetFieldDefaults(EntityDefinition ed, ExecutionContext ec, Boolean pks) {
        // allow updating a record without specifying default PK fields, so don't check this: if (isCreate) {
        Map<String, String> pkDefaults = ed.entityInfo.pkFieldDefaults;
        if ((pks == null || pks) && pkDefaults != null && pkDefaults.size() > 0) for (Entry<String, String> entry : pkDefaults.entrySet())
            checkSetDefault(entry.getKey(), entry.getValue(), ec);
        Map<String, String> nonPkDefaults = ed.entityInfo.nonPkFieldDefaults;
        if ((pks == null || !pks) && nonPkDefaults != null && nonPkDefaults.size() > 0)
            for (Entry<String, String> entry : nonPkDefaults.entrySet())
                checkSetDefault(entry.getKey(), entry.getValue(), ec);
    }

    private void checkSetDefault(String fieldName, String defaultStr, ExecutionContext ec) {
        Object curVal = null;
        if (valueMapInternal.containsKey(fieldName)) {
            curVal = valueMapInternal.get(fieldName);
        } else if (dbValueMap != null) {
            curVal = dbValueMap.get(fieldName);
        }

        if (ObjectUtilities.isEmpty(curVal)) {
            if (dbValueMap != null) ec.getContext().push(dbValueMap);
            ec.getContext().push(valueMapInternal);
            try {
                Object newVal = ec.getResource().expression(defaultStr, "");
                if (newVal != null) valueMapInternal.put(fieldName, newVal);
            } finally {
                ec.getContext().pop();
                if (dbValueMap != null) ec.getContext().pop();
            }
        }
    }

    private String makeErrorMsg(String baseMsg, String expandMsg, EntityDefinition ed, ExecutionContextImpl ec) {
        Map<String, Object> errorContext = new HashMap<>();
        errorContext.put("entityName", ed.getEntityName()); errorContext.put("primaryKeys", getPrimaryKeys());
        String errorMessage = null;
        // TODO: need a different approach for localization, getting from DB may not be reliable after an error and may cause other errors (especially with Postgres and the auto rollback only)
        if (false && !"LocalizedMessage".equals(ed.getEntityName())) {
            try { errorMessage = ec.resourceFacade.expand(expandMsg, null, errorContext); }
            catch (Throwable t) { logger.trace("Error expanding error message", t); }
        }
        if (errorMessage == null) errorMessage = baseMsg + " " + ed.getEntityName() + " " + getPrimaryKeys();
        return errorMessage;
    }

    @Override
    public EntityValue create() {
        final EntityDefinition ed = getEntityDefinition();
        final EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo;
        final EntityFacadeImpl efi = getEntityFacadeImpl();
        final ExecutionContextFactoryImpl ecfi = efi.ecfi;
        final ExecutionContextImpl ec = ecfi.getEci();
        final ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade;

        // check/set defaults
        if (entityInfo.hasFieldDefaults) checkSetFieldDefaults(ed, ec, null);

        // set lastUpdatedStamp
        final Long time = ecfi.transactionFacade.getCurrentTransactionStartTime();
        Long lastUpdatedLong = time != null && time > 0 ? time : System.currentTimeMillis();
        if (ed.isField("lastUpdatedStamp") && valueMapInternal.get("lastUpdatedStamp") == null)
            valueMapInternal.put("lastUpdatedStamp", new Timestamp(lastUpdatedLong));

        // do the artifact push/authz
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(entityName, ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_CREATE, "create").setParameters(valueMapInternal);
        aefi.pushInternal(aei, !entityInfo.authorizeSkipCreate, false);

        try {
            // run EECA before rules
            efi.runEecaRules(entityName, this, "create", true);

            // do this before the db change so modified flag isn't cleared
            if (doDataFeed(ec)) efi.getEntityDataFeed().dataFeedCheckAndRegister(this, false, valueMapInternal, null);

            // if there is not a txCache or the txCache doesn't handle the create, call the abstract method to create the main record
            TransactionCache curTxCache = getTxCache(ecfi);
            if (curTxCache == null || !curTxCache.create(this)) this.basicCreate(null);

            // NOTE: cache clear is the same for create, update, delete; even on create need to clear one cache because it
            // might have a null value for a previous query attempt
            efi.getEntityCache().clearCacheForValue(this, true);
            // save audit log(s) if applicable
            handleAuditLog(false, null, ed, ec);
            // run EECA after rules
            efi.runEecaRules(entityName, this, "create", false);
        } catch (SQLException e) {
            throw new EntitySqlException(makeErrorMsg("Error creating", CREATE_ERROR, ed, ec), e);
        } catch (Exception e) {
            throw new EntityException(makeErrorMsg("Error creating", CREATE_ERROR, ed, ec), e);
        } finally {
            // pop the ArtifactExecutionInfo to clean it up, also counts artifact hit
            aefi.pop(aei);
        }

        return this;
    }

    public void basicCreate(Connection con) throws SQLException {
        EntityDefinition ed = getEntityDefinition();
        FieldInfo[] allFieldArray = ed.entityInfo.allFieldInfoArray;
        FieldInfo[] fieldArray = new FieldInfo[allFieldArray.length];
        int size = allFieldArray.length;
        int fieldArrayIndex = 0;
        for (int i = 0; i < size; i++) {
            FieldInfo fi = allFieldArray[i];
            if (valueMapInternal.containsKey(fi.name)) {
                fieldArray[fieldArrayIndex] = fi;
                fieldArrayIndex++;
            }
        }
        createExtended(fieldArray, con);
    }

    /**
     * This method should create a corresponding record in the datasource. NOTE: fieldInfoArray may have null values
     * after valid ones, the length is not the actual number of fields.
     */
    public abstract void createExtended(FieldInfo[] fieldInfoArray, Connection con) throws SQLException;

    @Override
    public EntityValue update() {
        final EntityDefinition ed = getEntityDefinition();
        final EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo;
        final EntityFacadeImpl efi = getEntityFacadeImpl();
        final ExecutionContextFactoryImpl ecfi = efi.ecfi;
        final ExecutionContextImpl ec = ecfi.getEci();
        final ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade;
        final TransactionCache curTxCache = getTxCache(ecfi);
        final boolean optimisticLock = entityInfo.optimisticLock;
        final boolean hasFieldDefaults = entityInfo.hasFieldDefaults;
        final boolean needsAuditLog = entityInfo.needsAuditLog;
        final boolean createOnlyAny = entityInfo.createOnly || entityInfo.createOnlyFields;

        // check/set defaults for pk fields, do this first to fill in optional pk fields
        if (hasFieldDefaults) checkSetFieldDefaults(ed, ec, true);

        // if there is one or more DataFeed configs associated with this entity get info about them
        boolean curDataFeed = doDataFeed(ec);
        if (curDataFeed) {
            ArrayList<EntityDataFeed.DocumentEntityInfo> entityInfoList = efi.getEntityDataFeed().getDataFeedEntityInfoList(entityName);
            if (entityInfoList.size() == 0) curDataFeed = false;
        }

        // need actual DB values for various scenarios? get them here
        if (needsAuditLog || createOnlyAny || curDataFeed || optimisticLock || hasFieldDefaults) {
            EntityValueBase refreshedValue = (EntityValueBase) this.cloneValue();
            refreshedValue.refresh();
            this.setDbValueMap(refreshedValue.getValueMap());
        }

        // check/set defaults for non-pk fields, after getting dbValueMap
        if (hasFieldDefaults) checkSetFieldDefaults(ed, ec, false);

        // Save original values before anything is changed for DataFeed and audit log
        Map<String, Object> originalValues = dbValueMap != null && !dbValueMap.isEmpty() ? new HashMap<>(dbValueMap) : null;

        // do the artifact push/authz
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(entityName, ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_UPDATE, "update").setParameters(valueMapInternal);
        aefi.pushInternal(aei, !entityInfo.authorizeSkipTrue, false);

        try {
            // run EECA before rules
            efi.runEecaRules(entityName, this, "update", true);

            FieldInfo[] pkFieldArray = entityInfo.pkFieldInfoArray;
            FieldInfo[] allNonPkFieldArray = entityInfo.nonPkFieldInfoArray;
            FieldInfo[] nonPkFieldArray = new FieldInfo[allNonPkFieldArray.length];
            ArrayList<String> changedCreateOnlyFields = null;
            boolean modifiedLastUpdatedStamp = false;
            int size = allNonPkFieldArray.length;
            int nonPkFieldArrayIndex = 0;
            for (int i = 0; i < size; i++) {
                FieldInfo fieldInfo = allNonPkFieldArray[i];
                String fieldName = fieldInfo.name;
                if (isFieldModified(fieldName)) {
                    if (fieldInfo.isLastUpdatedStamp) {
                        // more stringent is modified check for lastUpdatedStamp
                        if (dbValueMap == null || dbValueMap.get(fieldName) == null) continue;
                        modifiedLastUpdatedStamp = true;
                    }
                    nonPkFieldArray[nonPkFieldArrayIndex] = fieldInfo;
                    nonPkFieldArrayIndex++;
                    if (createOnlyAny && fieldInfo.createOnly) {
                        if (changedCreateOnlyFields == null) changedCreateOnlyFields = new ArrayList<>();
                        changedCreateOnlyFields.add(fieldName);
                    }
                }
            }

            // if (ed.getEntityName() == "foo") logger.warn("================ evb.update() ${getEntityName()} nonPkFieldList=${nonPkFieldList};\nvalueMap=${valueMap};\noldValues=${oldValues}")
            if (nonPkFieldArrayIndex == 0 || (nonPkFieldArrayIndex == 1 && modifiedLastUpdatedStamp)) {
                if (logger.isTraceEnabled()) logger.trace("Not doing update on entity with no changed non-PK fields; value=" + this.toString());
                return this;
            }

            // do this after the empty nonPkFieldList check so that if nothing has changed then ignore the attempt to update
            if (changedCreateOnlyFields != null && changedCreateOnlyFields.size() > 0)
                throw new EntityException("Cannot update create-only (immutable) fields " + changedCreateOnlyFields + " on entity " + getEntityName());

            // check optimistic lock with lastUpdatedStamp; if optimisticLock() dbValueMap will have latest from DB
            if (optimisticLock) {
                Object valueLus = valueMapInternal.get("lastUpdatedStamp");
                Object dbLus = dbValueMap.get("lastUpdatedStamp");
                if (valueLus != null && dbLus != null && !dbLus.equals(valueLus))
                    throw new EntityException("This record was updated by someone else at " + dbLus + " which was after the version you loaded at " + valueLus + ". Not updating to avoid overwriting data.");
            }

            // set lastUpdatedStamp
            FieldInfo lastUpdatedStampInfo = ed.entityInfo.lastUpdatedStampInfo;
            if (!modifiedLastUpdatedStamp && lastUpdatedStampInfo != null) {
                final Long time = ecfi.transactionFacade.getCurrentTransactionStartTime();
                long lastUpdatedLong = time != null && time > 0 ? time : System.currentTimeMillis();
                valueMapInternal.put("lastUpdatedStamp", new Timestamp(lastUpdatedLong));
                nonPkFieldArray[nonPkFieldArrayIndex] = lastUpdatedStampInfo;
                // never gets used after this point, but if ever does will need to: nonPkFieldArrayIndex++
            }

            // do this before the db change so modified flag isn't cleared
            if (curDataFeed) efi.getEntityDataFeed().dataFeedCheckAndRegister(this, true, valueMapInternal, originalValues);

            // if there is not a txCache or the txCache doesn't handle the update, call the abstract method to update the main record
            if (curTxCache == null || !curTxCache.update(this)) {
                // no TX cache update, etc: ready to do actual update
                updateExtended(pkFieldArray, nonPkFieldArray, null);
                // if ("OrderHeader".equals(ed.getEntityName()) && "55500".equals(valueMapInternal.get("orderId"))) logger.warn("Called updateExtended order " + this.valueMapInternal.toString());
            }

            // clear the entity cache
            efi.getEntityCache().clearCacheForValue(this, false);
            // save audit log(s) if applicable
            if (needsAuditLog) handleAuditLog(true, originalValues, ed, ec);
            // run EECA after rules
            efi.runEecaRules(entityName, this, "update", false);
        } catch (SQLException e) {
            throw new EntitySqlException(makeErrorMsg("Error updating", UPDATE_ERROR, ed, ec), e);
        } catch (Exception e) {
            throw new EntityException(makeErrorMsg("Error updating", UPDATE_ERROR, ed, ec), e);
        } finally {
            // pop the ArtifactExecutionInfo to clean it up, also counts artifact hit
            aefi.pop(aei);
        }

        return this;
    }

    public void basicUpdate(Connection con) throws SQLException {
        EntityDefinition ed = getEntityDefinition();

        /* Shouldn't need this any more, was from a weird old issue:
        boolean dbValueMapFromDb = false
        // it may be that the oldValues map is full of null values because the EntityValue didn't come from the db
        if (dbValueMap) for (Object val in dbValueMap.values()) if (val != null) { dbValueMapFromDb = true; break }
        */

        FieldInfo[] pkFieldArray = ed.entityInfo.pkFieldInfoArray;
        FieldInfo[] allNonPkFieldArray = ed.entityInfo.nonPkFieldInfoArray;
        FieldInfo[] nonPkFieldArray = new FieldInfo[allNonPkFieldArray.length];
        int size = allNonPkFieldArray.length;
        int nonPkFieldArrayIndex = 0;
        for (int i = 0; i < size; i++) {
            FieldInfo fi = allNonPkFieldArray[i];
            String fieldName = fi.name;
            if (isFieldModified(fieldName)) {
                nonPkFieldArray[nonPkFieldArrayIndex] = fi;
                nonPkFieldArrayIndex++;
            }
        }

        updateExtended(pkFieldArray, nonPkFieldArray, con);
    }

    /**
     * This method should update the corresponding record in the datasource. NOTE: fieldInfoArray may have null values
     * after valid ones, the length is not the actual number of fields.
     */
    public abstract void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) throws SQLException;

    @Override
    public EntityValue delete() {
        final EntityDefinition ed = getEntityDefinition();
        final EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo;
        final EntityFacadeImpl efi = getEntityFacadeImpl();
        final ExecutionContextFactoryImpl ecfi = efi.ecfi;
        final ExecutionContextImpl ec = ecfi.getEci();
        final ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade;

        // NOTE: this is create-only on the entity, ignores setting on fields (only considered in update)
        if (entityInfo.createOnly) throw new EntityException("Entity [" + getEntityName() + "] is create-only (immutable), cannot be deleted.");

        // do the artifact push/authz
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(entityName, ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_DELETE, "delete").setParameters(valueMapInternal);
        aefi.pushInternal(aei, !entityInfo.authorizeSkipTrue, false);

        try {
            // run EECA before rules
            efi.runEecaRules(entityName, this, "delete", true);
            // this needs to be called before the actual update so we know which fields are modified
            // NOTE: consider not doing this on delete, DataDocuments are not great for representing absence of records
            // NOTE2: this might be useful, but is a bit of a pain and utility is dubious, leave out for now
            // efi.getEntityDataFeed().dataFeedCheckAndRegister(this, true, valueMap, null)

            // if there is not a txCache or the txCache doesn't handle the delete, call the abstract method to delete the main record
            TransactionCache curTxCache = getTxCache(ecfi);
            if (curTxCache == null || !curTxCache.delete(this)) this.deleteExtended(null);

            // clear the entity cache
            efi.getEntityCache().clearCacheForValue(this, false);
            // run EECA after rules
            efi.runEecaRules(entityName, this, "delete", false);
        } catch (SQLException e) {
            throw new EntitySqlException(makeErrorMsg("Error deleting", DELETE_ERROR, ed, ec), e);
        } catch (Exception e) {
            throw new EntityException(makeErrorMsg("Error deleting", DELETE_ERROR, ed, ec), e);
        } finally {
            // pop the ArtifactExecutionInfo to clean it up, also counts artifact hit
            aefi.pop(aei);
        }

        return this;
    }

    public abstract void deleteExtended(Connection con) throws SQLException;

    @Override
    public boolean refresh() {
        final EntityDefinition ed = getEntityDefinition();
        final EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo;
        final EntityFacadeImpl efi = getEntityFacadeImpl();
        final ExecutionContextFactoryImpl ecfi = efi.ecfi;
        final ExecutionContextImpl ec = ecfi.getEci();
        final ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade;

        List<String> pkFieldList = ed.getPkFieldNames();
        if (pkFieldList.size() == 0) {
            // throw new EntityException("Entity ${getEntityName()} has no primary key fields, cannot do refresh.")
            if (logger.isTraceEnabled()) logger.trace("Entity " + getEntityName() + " has no primary key fields, cannot do refresh.");
            return false;
        }

        // check/set defaults
        if (entityInfo.hasFieldDefaults) checkSetFieldDefaults(ed, ec, null);

        // do the artifact push/authz
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(entityName, ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "refresh").setParameters(valueMapInternal);
        aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false);

        boolean retVal = false;
        try {
            // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(fullEntityName, this, "find-one", true);

            // if there is not a txCache or the txCache doesn't handle the refresh, call the abstract method to refresh
            TransactionCache curTxCache = getTxCache(ecfi);
            if (curTxCache != null) retVal = curTxCache.refresh(this);
            // call the abstract method
            if (!retVal) {
                retVal = this.refreshExtended();
                if (retVal && curTxCache != null) curTxCache.onePut(this, false);
            }

            // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(fullEntityName, this, "find-one", false);
        } catch (SQLException e) {
            throw new EntitySqlException(makeErrorMsg("Error finding", REFRESH_ERROR, ed, ec), e);
        } catch (Exception e) {
            throw new EntityException(makeErrorMsg("Error finding", REFRESH_ERROR, ed, ec), e);
        } finally {
            // pop the ArtifactExecutionInfo to clean it up, also counts artifact hit
            aefi.pop(aei);
        }

        return retVal;
    }
    public abstract boolean refreshExtended() throws SQLException;

    @Override public String getEtlType() { return entityName; }
    @Override public Map<String, Object> getEtlValues() { return valueMapInternal; }

    private static class EntityFieldEntry implements Entry<String, Object> {
        protected FieldInfo fi;
        EntityValueBase evb;
        private EntityFieldEntry(FieldInfo fi, EntityValueBase evb) {
            this.fi = fi;
            this.evb = evb;
        }
        @Override public String getKey() { return fi.name; }
        @Override public Object getValue() { return evb.getKnownField(fi); }
        @Override public Object setValue(Object v) { return evb.set(fi.name, v); }
        @Override public int hashCode() {
            Object val = getValue();
            return fi.name.hashCode() + (val != null ? val.hashCode() : 0);
        }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof EntityFieldEntry)) return false;
            EntityFieldEntry other = (EntityFieldEntry) obj;
            if (!fi.name.equals(other.fi.name)) return false;
            Object thisVal = getValue();
            Object otherVal = other.getValue();
            return thisVal == null ? otherVal == null : thisVal.equals(otherVal);
        }
    }

    public static class DeletedEntityValue extends EntityValueBase {
        public DeletedEntityValue(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip); }
        @Override public EntityValue cloneValue() { return this; }
        @Override public EntityValue cloneDbValue(boolean getOld) { return this; }
        @Override public void createExtended(FieldInfo[] fieldInfoArray, Connection con) {
            throw new UnsupportedOperationException("Not implemented on DeletedEntityValue"); }
        @Override public void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) {
            throw new UnsupportedOperationException("Not implemented on DeletedEntityValue"); }
        @Override public void deleteExtended(Connection con) { throw new UnsupportedOperationException("Not implemented on DeletedEntityValue"); }
        @Override public boolean refreshExtended() { throw new UnsupportedOperationException("Not implemented on DeletedEntityValue"); }
    }
}
