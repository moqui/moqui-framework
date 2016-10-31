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

import org.moqui.BaseException;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.entity.EntityDatasourceFactory;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityNotFoundException;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.MNode;

import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.xml.bind.DatatypeConverter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class EntityJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(EntityJavaUtil.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    private static final int saltBytes = 8;
    static String enDeCrypt(String value, boolean encrypt, EntityFacadeImpl efi) {
        MNode entityFacadeNode = efi.ecfi.getConfXmlRoot().first("entity-facade");
        String pwStr = entityFacadeNode.attribute("crypt-pass");
        if (pwStr == null || pwStr.length() == 0)
            throw new IllegalArgumentException("No entity-facade.@crypt-pass setting found, NOT doing encryption");

        String saltStr = entityFacadeNode.attribute("crypt-salt");
        byte[] salt = (saltStr != null && saltStr.length() > 0 ? saltStr : "default1").getBytes();
        if (salt.length > saltBytes) {
            byte[] trimmed = new byte[saltBytes];
            System.arraycopy(salt, 0, trimmed, 0, saltBytes);
            salt = trimmed;
        }
        if (salt.length < saltBytes) {
            byte[] newSalt = new byte[saltBytes];
            for (int i = 0; i < saltBytes; i++) {
                if (i < salt.length) newSalt[i] = salt[i];
                else newSalt[i] = 0x45;
            }
            salt = newSalt;
        }
        String iterStr = entityFacadeNode.attribute("crypt-iter");
        int count = iterStr != null && iterStr.length() > 0 ? Integer.valueOf(iterStr) : 10;
        char[] pass = pwStr.toCharArray();


        String algo = entityFacadeNode.attribute("crypt-algo");
        if (algo == null || algo.length() == 0) algo = "PBEWithMD5AndDES";

        // logger.info("TOREMOVE salt [${salt}] count [${count}] pass [${pass}] algo [${algo}]")
        PBEParameterSpec pbeParamSpec = new PBEParameterSpec(salt, count);
        PBEKeySpec pbeKeySpec = new PBEKeySpec(pass);
        try {
            SecretKeyFactory keyFac = SecretKeyFactory.getInstance(algo);
            SecretKey pbeKey = keyFac.generateSecret(pbeKeySpec);

            Cipher pbeCipher = Cipher.getInstance(algo);
            int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
            pbeCipher.init(mode, pbeKey, pbeParamSpec);

            byte[] inBytes;
            if (encrypt) {
                inBytes = value.getBytes();
            } else {
                inBytes = DatatypeConverter.parseHexBinary(value);
            }
            byte[] outBytes = pbeCipher.doFinal(inBytes);
            return encrypt ? DatatypeConverter.printHexBinary(outBytes) : new String(outBytes);
        } catch (Exception e) {
            throw new EntityException("Encryption error", e);
        }
    }

    @SuppressWarnings("unused")
    public static FieldOrderOptions makeFieldOrderOptions(String orderByName) { return new FieldOrderOptions(orderByName); }
    public static class FieldOrderOptions {
        final static char spaceChar = ' ';
        final static char minusChar = '-';
        final static char plusChar = '+';
        final static char caretChar = '^';
        final static char openParenChar = '(';
        final static char closeParenChar = ')';

        String fieldName = null;
        Boolean nullsFirstLast = null;
        boolean descending = false;
        Boolean caseUpperLower = null;

        public String getFieldName() { return fieldName; }
        Boolean getNullsFirstLast() { return nullsFirstLast; }
        public boolean getDescending() { return descending; }
        Boolean getCaseUpperLower() { return caseUpperLower; }

        FieldOrderOptions(String orderByName) {
            StringBuilder fnSb = new StringBuilder(40);
            // simple first parse pass, single run through and as fast as possible
            boolean containsSpace = false;
            boolean foundNonSpace = false;
            boolean containsOpenParen = false;
            int obnLength = orderByName.length();
            char[] obnCharArray = orderByName.toCharArray();
            for (int i = 0; i < obnLength; i++) {
                char curChar = obnCharArray[i];
                if (curChar == spaceChar) {
                    if (foundNonSpace) {
                        containsSpace = true;
                        fnSb.append(curChar);
                    }
                    // otherwise ignore the space
                } else {
                    // leading characters (-,+,^), don't consider them non-spaces so we'll remove spaces after
                    if (curChar == minusChar) {
                        descending = true;
                    } else if (curChar == plusChar) {
                        descending = false;
                    } else if (curChar == caretChar) {
                        caseUpperLower = true;
                    } else {
                        foundNonSpace = true;
                        fnSb.append(curChar);
                        if (curChar == openParenChar) containsOpenParen = true;
                    }
                }
            }

            if (fnSb.length() == 0) return;

            if (containsSpace) {
                // trim ending spaces
                while (fnSb.charAt(fnSb.length() - 1) == spaceChar) fnSb.delete(fnSb.length() - 1, fnSb.length());

                String orderByUpper = fnSb.toString().toUpperCase();
                int fnSbLength = fnSb.length();
                if (orderByUpper.endsWith(" NULLS FIRST")) {
                    nullsFirstLast = true;
                    fnSb.delete(fnSbLength - 12, fnSbLength);
                    // remove from orderByUpper as we'll use it below
                    orderByUpper = orderByUpper.substring(0, orderByName.length() - 12);
                } else if (orderByUpper.endsWith(" NULLS LAST")) {
                    nullsFirstLast = false;
                    fnSb.delete(fnSbLength - 11, fnSbLength);
                    // remove from orderByUpper as we'll use it below
                    orderByUpper = orderByUpper.substring(0, orderByName.length() - 11);
                }

                fnSbLength = fnSb.length();
                if (orderByUpper.endsWith(" DESC")) {
                    descending = true;
                    fnSb.delete(fnSbLength - 5, fnSbLength);
                } else if (orderByUpper.endsWith(" ASC")) {
                    descending = false;
                    fnSb.delete(fnSbLength - 4, fnSbLength);
                }
            }
            if (containsOpenParen) {
                String upperText = fnSb.toString().toUpperCase();
                if (upperText.startsWith("UPPER(")) {
                    caseUpperLower = true;
                    fnSb.delete(0, 6);
                } else if (upperText.startsWith("LOWER(")) {
                    caseUpperLower = false;
                    fnSb.delete(0, 6);
                }
                int fnSbLength = fnSb.length();
                if (fnSb.charAt(fnSbLength - 1) == closeParenChar) fnSb.delete(fnSbLength - 1, fnSbLength);
            }

            fieldName = fnSb.toString();
        }
    }

    public static class EntityInfo {
        private final EntityDefinition ed;
        private final EntityFacadeImpl efi;
        public final String internalEntityName, fullEntityName, shortAlias, groupName;
        public final String tableName, schemaName, fullTableName;

        public final EntityDatasourceFactory datasourceFactory;
        public final boolean isEntityDatasourceFactoryImpl;
        public final boolean isView, isDynamicView, isInvalidViewEntity;
        final boolean hasFunctionAlias;
        public final boolean createOnly, createOnlyFields;
        final boolean optimisticLock, needsAuditLog, needsEncrypt;
        public final String useCache;
        public final boolean neverCache;
        final String sequencePrimaryPrefix;
        public final long sequencePrimaryStagger, sequenceBankSize;
        public final boolean sequencePrimaryUseUuid;

        final boolean hasFieldDefaults;
        final String authorizeSkipStr;
        final boolean authorizeSkipTrue;
        final boolean authorizeSkipCreate;
        public final boolean authorizeSkipView;

        public final FieldInfo[] pkFieldInfoArray, nonPkFieldInfoArray, allFieldInfoArray;
        final FieldInfo lastUpdatedStampInfo;
        final String allFieldsSqlSelect;
        final Map<String, String> pkFieldDefaults, nonPkFieldDefaults;


        EntityInfo(EntityDefinition ed, boolean memberNeverCache) {
            this.ed = ed;
            this.efi = ed.efi;
            MNode internalEntityNode = ed.internalEntityNode;
            EntityFacadeImpl efi = ed.efi;
            ArrayList<FieldInfo> allFieldInfoList = ed.allFieldInfoList;

            internalEntityName = internalEntityNode.attribute("entity-name");
            String packageName = internalEntityNode.attribute("package");
            if (packageName == null || packageName.isEmpty()) packageName = internalEntityNode.attribute("package-name");
            fullEntityName = packageName + "." + internalEntityName;
            String shortAliasAttr = internalEntityNode.attribute("short-alias");
            shortAlias = shortAliasAttr != null && !shortAliasAttr.isEmpty() ? shortAliasAttr : null;

            isView = ed.isViewEntity;
            isDynamicView = ed.isDynamicView;
            createOnly = "true".equals(internalEntityNode.attribute("create-only"));
            isInvalidViewEntity = isView && (!internalEntityNode.hasChild("member-entity") || !internalEntityNode.hasChild("alias"));

            groupName = ed.groupName;
            datasourceFactory = efi.getDatasourceFactory(groupName);
            isEntityDatasourceFactoryImpl = datasourceFactory instanceof EntityDatasourceFactoryImpl;
            MNode datasourceNode = efi.getDatasourceNode(groupName);
            MNode databaseNode = efi.getDatabaseNode(groupName);

            String tableNameAttr = internalEntityNode.attribute("table-name");
            if (tableNameAttr == null || tableNameAttr.isEmpty()) tableNameAttr = EntityJavaUtil.camelCaseToUnderscored(internalEntityName);
            tableName = tableNameAttr;
            String schemaNameAttr = datasourceNode != null ? datasourceNode.attribute("schema-name") : null;
            if (schemaNameAttr != null && schemaNameAttr.length() == 0) schemaNameAttr = null;
            schemaName = schemaNameAttr;
            if (databaseNode == null || !"false".equals(databaseNode.attribute("use-schemas"))) {
                fullTableName = schemaName != null ? schemaName + "." + tableNameAttr : tableNameAttr;
            } else {
                fullTableName = tableNameAttr;
            }

            String sppAttr = internalEntityNode.attribute("sequence-primary-prefix");
            if (sppAttr == null) sppAttr = "";
            sequencePrimaryPrefix = sppAttr;

            String spsAttr = internalEntityNode.attribute("sequence-primary-stagger");
            if (spsAttr != null && !spsAttr.isEmpty()) sequencePrimaryStagger = Long.parseLong(spsAttr);
            else sequencePrimaryStagger = 1;

            String sbsAttr = internalEntityNode.attribute("sequence-bank-size");
            if (sbsAttr != null && !sbsAttr.isEmpty()) sequenceBankSize = Long.parseLong(sbsAttr);
            else sequenceBankSize = EntityFacadeImpl.defaultBankSize;

            sequencePrimaryUseUuid = "true".equals(internalEntityNode.attribute("sequence-primary-use-uuid")) ||
                    (datasourceNode != null && "true".equals(datasourceNode.attribute("sequence-primary-use-uuid")));

            optimisticLock = "true".equals(internalEntityNode.attribute("optimistic-lock"));

            authorizeSkipStr = internalEntityNode.attribute("authorize-skip");
            authorizeSkipTrue = "true".equals(authorizeSkipStr);
            authorizeSkipCreate = authorizeSkipTrue || (authorizeSkipStr != null && authorizeSkipStr.contains("create"));
            authorizeSkipView = authorizeSkipTrue || (authorizeSkipStr != null && authorizeSkipStr.contains("view"));

            // NOTE: see code in initFields that may set this to never if any member-entity is set to cache=never
            if (memberNeverCache) {
                useCache = "never";
                neverCache = true;
            } else {
                String cacheAttr = internalEntityNode.attribute("cache");
                if (cacheAttr == null || cacheAttr.isEmpty()) cacheAttr = "false";
                useCache = cacheAttr;
                neverCache = "never".equals(useCache);
            }

            // init the FieldInfo arrays and see if we have create only fields, etc
            int allFieldInfoSize = allFieldInfoList.size();
            ArrayList<FieldInfo> pkFieldInfoList = new ArrayList<>();
            ArrayList<FieldInfo> nonPkFieldInfoList = new ArrayList<>();
            allFieldInfoArray = new FieldInfo[allFieldInfoSize];
            boolean createOnlyFieldsTemp = false;
            boolean needsAuditLogTemp = false;
            boolean needsEncryptTemp = false;
            boolean hasFunctionAliasTemp = false;
            Map<String, String> pkFieldDefaultsTemp = new HashMap<>();
            Map<String, String> nonPkFieldDefaultsTemp = new HashMap<>();
            FieldInfo lastUpdatedTemp = null;
            for (int i = 0; i < allFieldInfoSize; i++) {
                FieldInfo fi = allFieldInfoList.get(i);
                allFieldInfoArray[i] = fi;
                if (fi.isPk) pkFieldInfoList.add(fi); else nonPkFieldInfoList.add(fi);
                if (fi.createOnly) createOnlyFieldsTemp = true;
                if ("true".equals(fi.enableAuditLog) || "update".equals(fi.enableAuditLog)) needsAuditLogTemp = true;
                if ("true".equals(fi.fieldNode.attribute("encrypt"))) needsEncryptTemp = true;
                String functionAttr = fi.fieldNode.attribute("function");
                if (isView && functionAttr != null && !functionAttr.isEmpty()) hasFunctionAliasTemp = true;
                String defaultStr = fi.fieldNode.attribute("default");
                if (defaultStr != null && !defaultStr.isEmpty()) {
                    if (fi.isPk) pkFieldDefaultsTemp.put(fi.name, defaultStr);
                    else nonPkFieldDefaultsTemp.put(fi.name, defaultStr);
                }
                if ("lastUpdatedStamp".equals(fi.name)) lastUpdatedTemp = fi;
            }
            createOnlyFields = createOnlyFieldsTemp;
            needsAuditLog = needsAuditLogTemp;
            needsEncrypt = needsEncryptTemp;
            hasFunctionAlias = hasFunctionAliasTemp;
            hasFieldDefaults = pkFieldDefaultsTemp.size() > 0 || nonPkFieldDefaultsTemp.size() > 0;
            pkFieldDefaults = pkFieldDefaultsTemp.size() > 0 ? pkFieldDefaultsTemp : null;
            nonPkFieldDefaults = nonPkFieldDefaultsTemp.size() > 0 ? nonPkFieldDefaultsTemp : null;
            lastUpdatedStampInfo = lastUpdatedTemp;

            pkFieldInfoArray = new FieldInfo[pkFieldInfoList.size()];
            pkFieldInfoList.toArray(pkFieldInfoArray);
            nonPkFieldInfoArray = new FieldInfo[nonPkFieldInfoList.size()];
            nonPkFieldInfoList.toArray(nonPkFieldInfoArray);

            // init allFieldsSqlSelect
            if (isView) {
                allFieldsSqlSelect = null;
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < allFieldInfoList.size(); i++) {
                    FieldInfo fi = allFieldInfoList.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(fi.fullColumnNameInternal);
                }
                allFieldsSqlSelect = sb.toString();
            }
        }

        void setFields(Map<String, Object> src, Map<String, Object> dest, boolean setIfEmpty, String namePrefix, Boolean pks) {
            if (src == null || dest == null) return;

            ExecutionContextImpl eci = efi.ecfi.getEci();
            boolean destIsEntityValueBase = dest instanceof EntityValueBase;
            EntityValueBase destEvb = destIsEntityValueBase ? (EntityValueBase) dest : null;

            boolean hasNamePrefix = namePrefix != null && namePrefix.length() > 0;
            boolean srcIsEntityValueBase = src instanceof EntityValueBase;
            EntityValueBase evb = srcIsEntityValueBase ? (EntityValueBase) src : null;
            FieldInfo[] fieldInfoArray = pks == null ? allFieldInfoArray :
                    (pks == Boolean.TRUE ? pkFieldInfoArray : nonPkFieldInfoArray);
            // use integer iterator, saves quite a bit of time, improves time for this method by about 20% with this alone
            int size = fieldInfoArray.length;
            for (int i = 0; i < size; i++) {
                FieldInfo fi = fieldInfoArray[i];
                String fieldName = fi.name;
                String sourceFieldName;
                if (hasNamePrefix) {
                    sourceFieldName = namePrefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                } else {
                    sourceFieldName = fieldName;
                }

                Object value = srcIsEntityValueBase? evb.getValueMap().get(sourceFieldName) : src.get(sourceFieldName);
                if (value != null || (srcIsEntityValueBase ? evb.isFieldSet(sourceFieldName) : src.containsKey(sourceFieldName))) {
                    boolean isCharSequence = false;
                    boolean isEmpty = false;
                    if (value == null) {
                        isEmpty = true;
                    } else if (value instanceof CharSequence) {
                        isCharSequence = true;
                        if (((CharSequence) value).length() == 0) isEmpty = true;
                    }

                    if (!isEmpty) {
                        if (isCharSequence) {
                            try {
                                Object converted = fi.convertFromString(value.toString(), eci.l10nFacade);
                                if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, converted);
                                else dest.put(fieldName, converted);
                            } catch (BaseException be) {
                                eci.messageFacade.addValidationError(null, fieldName, null, be.getMessage(), be);
                            }
                        } else {
                            if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value);
                            else dest.put(fieldName, value);
                        }
                    } else if (setIfEmpty && src.containsKey(sourceFieldName)) {
                        // treat empty String as null, otherwise set as whatever null or empty type it is
                        if (value != null && isCharSequence) {
                            if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, null);
                            else dest.put(fieldName, null);
                        } else {
                            if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value);
                            else dest.put(fieldName, value);
                        }
                    }
                }
            }
        }

        void setFieldsEv(Map<String, Object> src, EntityValueBase dest, Boolean pks) {
            // like above with setIfEmpty=true, namePrefix=null, pks=null
            if (src == null || dest == null) return;

            ExecutionContextImpl eci = efi.ecfi.getEci();
            boolean srcIsEntityValueBase = src instanceof EntityValueBase;
            EntityValueBase evb = srcIsEntityValueBase ? (EntityValueBase) src : null;
            FieldInfo[] fieldInfoArray = pks == null ? allFieldInfoArray :
                    (pks == Boolean.TRUE ? pkFieldInfoArray : nonPkFieldInfoArray);
            // use integer iterator, saves quite a bit of time, improves time for this method by about 20% with this alone
            int size = fieldInfoArray.length;
            for (int i = 0; i < size; i++) {
                FieldInfo fi = fieldInfoArray[i];
                String fieldName = fi.name;

                Object value = srcIsEntityValueBase? evb.getValueMap().get(fieldName) : src.get(fieldName);
                if (value != null || (srcIsEntityValueBase ? evb.isFieldSet(fieldName) : src.containsKey(fieldName))) {
                    boolean isCharSequence = false;
                    boolean isEmpty = false;
                    if (value == null) {
                        isEmpty = true;
                    } else if (value instanceof CharSequence) {
                        isCharSequence = true;
                        if (((CharSequence) value).length() == 0) isEmpty = true;
                    }

                    if (!isEmpty) {
                        if (isCharSequence) {
                            try {
                                Object converted = fi.convertFromString(value.toString(), eci.l10nFacade);
                                dest.putNoCheck(fieldName, converted);
                            } catch (BaseException be) {
                                eci.messageFacade.addValidationError(null, fieldName, null, be.getMessage(), be);
                            }
                        } else {
                            dest.putNoCheck(fieldName, value);
                        }
                    } else if (src.containsKey(fieldName)) {
                        // treat empty String as null, otherwise set as whatever null or empty type it is
                        dest.putNoCheck(fieldName, null);
                    }
                }
            }
        }

        public Map<String, Object> cloneMapRemoveFields(Map<String, Object> theMap, Boolean pks) {
            Map<String, Object> newMap = new HashMap<>(theMap);
            //ArrayList<String> fieldNameList = (pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames())
            FieldInfo[] fieldInfoArray = pks == null ? allFieldInfoArray :
                    (pks == Boolean.TRUE ? pkFieldInfoArray : nonPkFieldInfoArray);
            int size = fieldInfoArray.length;
            for (int i = 0; i < size; i++) {
                FieldInfo fi = fieldInfoArray[i];
                newMap.remove(fi.name);
            }
            return newMap;
        }
    }

    public static class RelationshipInfo {
        public final String type;
        public final boolean isTypeOne;
        public final String title;
        public final String relatedEntityName;
        final EntityDefinition fromEd;
        public final EntityDefinition relatedEd;
        public final MNode relNode;

        public final String relationshipName;
        public final String shortAlias;
        public final String prettyName;
        public final Map<String, String> keyMap;
        public final boolean dependent;
        public final boolean mutable;

        RelationshipInfo(MNode relNode, EntityDefinition fromEd, EntityFacadeImpl efi) {
            this.relNode = relNode;
            this.fromEd = fromEd;
            type = relNode.attribute("type");
            isTypeOne = type.startsWith("one");
            String titleAttr = relNode.attribute("title");
            title = titleAttr != null && !titleAttr.isEmpty() ? titleAttr : null;
            String relatedAttr = relNode.attribute("related");
            if (relatedAttr == null || relatedAttr.isEmpty()) relatedAttr = relNode.attribute("related-entity-name");
            relatedEd = efi.getEntityDefinition(relatedAttr);
            if (relatedEd == null) throw new EntityNotFoundException("Invalid entity relationship, " + relatedAttr + " not found in definition for entity " + fromEd.getFullEntityName());
            relatedEntityName = relatedEd.getFullEntityName();

            relationshipName = (title != null ? title + '#' : "") + relatedEntityName;
            String shortAliasAttr = relNode.attribute("short-alias");
            shortAlias =  shortAliasAttr != null && !shortAliasAttr.isEmpty() ? shortAliasAttr : null;
            prettyName = relatedEd.getPrettyName(title, fromEd.entityInfo.internalEntityName);
            keyMap = EntityDefinition.getRelationshipExpandedKeyMapInternal(relNode, relatedEd);
            dependent = hasReverse();
            String mutableAttr = relNode.attribute("mutable");
            if (mutableAttr != null && !mutableAttr.isEmpty()) {
                mutable = "true".equals(relNode.attribute("mutable"));
            } else {
                // by default type one not mutable, type many are mutable
                mutable = !isTypeOne;
            }
        }

        // some methods for FTL templates that don't access member fields, just call getters; don't follow getter pattern so groovy code won't pick them up
        public String riPrettyName() { return prettyName; }
        public String riRelatedEntityName() { return relatedEntityName; }

        private boolean hasReverse() {
            ArrayList<MNode> relatedRelList = relatedEd.internalEntityNode.children("relationship");
            int relatedRelListSize = relatedRelList.size();
            for (int i = 0; i < relatedRelListSize; i++) {
                MNode reverseRelNode = relatedRelList.get(i);
                String relatedAttr = reverseRelNode.attribute("related");
                if (relatedAttr == null || relatedAttr.isEmpty()) relatedAttr = reverseRelNode.attribute("related-entity-name");
                String typeAttr = reverseRelNode.attribute("type");
                String titleAttr = reverseRelNode.attribute("title");
                if ((fromEd.entityInfo.fullEntityName.equals(relatedAttr) || fromEd.entityInfo.internalEntityName.equals(relatedAttr)) &&
                        ("one".equals(typeAttr) || "one-nofk".equals(typeAttr)) &&
                        (title == null ? titleAttr == null || titleAttr.isEmpty() : title.equals(titleAttr))) {
                    return true;
                }
            }
            return false;
        }
        public Map<String, Object> getTargetParameterMap(Map valueSource) {
            if (valueSource == null || valueSource.isEmpty()) return new LinkedHashMap<>();
            Map<String, Object> targetParameterMap = new HashMap<>();
            for (Map.Entry<String, String> keyEntry: keyMap.entrySet()) {
                Object value = valueSource.get(keyEntry.getKey());
                if (!ObjectUtilities.isEmpty(value)) targetParameterMap.put(keyEntry.getValue(), value);
            }
            return targetParameterMap;
        }
        public String toString() { return relationshipName + (shortAlias != null ? " (" + shortAlias + ")" : "") +
                ", type " + type + ", one? " + isTypeOne + ", dependent? " + dependent; }
    }

    private static Map<String, String> camelToUnderscoreMap = new HashMap<>();
    static String camelCaseToUnderscored(String camelCase) {
        if (camelCase == null || camelCase.length() == 0) return "";
        String usv = camelToUnderscoreMap.get(camelCase);
        if (usv != null) return usv;

        StringBuilder underscored = new StringBuilder();
        underscored.append(Character.toUpperCase(camelCase.charAt(0)));
        int inPos = 1;
        while (inPos < camelCase.length()) {
            char curChar = camelCase.charAt(inPos);
            if (Character.isUpperCase(curChar)) underscored.append('_');
            underscored.append(Character.toUpperCase(curChar));
            inPos++;
        }

        usv = underscored.toString();
        camelToUnderscoreMap.put(camelCase, usv);
        return usv;
    }

    public static class EntityConditionParameter {
        protected FieldInfo fieldInfo;
        protected Object value;
        protected EntityQueryBuilder eqb;

        public EntityConditionParameter(FieldInfo fieldInfo, Object value, EntityQueryBuilder eqb) {
            this.fieldInfo = fieldInfo;
            this.value = value;
            this.eqb = eqb;
        }

        public FieldInfo getFieldInfo() { return fieldInfo; }

        public Object getValue() { return value; }

        void setPreparedStatementValue(int index) throws EntityException {
            eqb.setPreparedStatementValue(index, value, fieldInfo);
        }

        @Override
        public String toString() { return fieldInfo.name + ':' + value; }
    }

    public static class QueryStatsInfo {
        private String entityName;
        private String sql;
        private long hitCount = 0, errorCount = 0;
        private long minTimeNanos = Long.MAX_VALUE, maxTimeNanos = 0, totalTimeNanos = 0, totalSquaredTime = 0;
        private Map<String, Integer> artifactCounts = new HashMap<>();
        public QueryStatsInfo(String entityName, String sql) {
            this.entityName = entityName;
            this.sql = sql;
        }
        public void countHit(EntityFacadeImpl efi, long runTimeNanos, boolean isError) {
            hitCount++;
            if (isError) errorCount++;
            if (runTimeNanos < minTimeNanos) minTimeNanos = runTimeNanos;
            if (runTimeNanos > maxTimeNanos) maxTimeNanos = runTimeNanos;
            totalTimeNanos += runTimeNanos;
            totalSquaredTime += runTimeNanos * runTimeNanos;
            // this gets much more expensive, consider commenting in the future
            ArtifactExecutionInfo aei = efi.ecfi.getEci().artifactExecutionFacade.peek();
            if (aei != null) aei = aei.getParent();
            if (aei != null) {
                String artifactName = aei.getName();
                Integer artifactCount = artifactCounts.get(artifactName);
                artifactCounts.put(artifactName, artifactCount != null ? artifactCount + 1 : 1);
            }
        }
        public String getEntityName() { return entityName; }
        public String getSql() { return sql; }
        // public long getHitCount() { return hitCount; }
        // public long getErrorCount() { return errorCount; }
        // public long getMinTimeNanos() { return minTimeNanos; }
        // public long getMaxTimeNanos() { return maxTimeNanos; }
        // public long getTotalTimeNanos() { return totalTimeNanos; }
        // public long getTotalSquaredTime() { return totalSquaredTime; }
        double getAverage() { return hitCount > 0 ? totalTimeNanos / hitCount : 0; }
        double getStdDev() {
            if (hitCount < 2) return 0;
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeNanos * totalTimeNanos) / hitCount)) / (hitCount - 1L));
        }
        final static long nanosDivisor = 1000;
        public Map<String, Object> makeDisplayMap() {
            Map<String, Object> dm = new HashMap<>();
            dm.put("entityName", entityName); dm.put("sql", sql);
            dm.put("hitCount", hitCount); dm.put("errorCount", errorCount);
            dm.put("minTime", new BigDecimal(minTimeNanos/nanosDivisor)); dm.put("maxTime", new BigDecimal(maxTimeNanos/nanosDivisor));
            dm.put("totalTime", new BigDecimal(totalTimeNanos/nanosDivisor)); dm.put("totalSquaredTime", new BigDecimal(totalSquaredTime/nanosDivisor));
            dm.put("average", new BigDecimal(getAverage()/nanosDivisor)); dm.put("stdDev", new BigDecimal(getStdDev()/nanosDivisor));
            dm.put("artifactCounts", new HashMap<>(artifactCounts));
            return dm;
        }
    }

    public enum WriteMode { CREATE, UPDATE, DELETE }
    public static class EntityWriteInfo {
        public WriteMode writeMode;
        public EntityValueBase evb;
        Map<String, Object> pkMap;
        public EntityWriteInfo(EntityValueBase evb, WriteMode writeMode) {
            // clone value so that create/update/delete stays the same no matter what happens after
            this.evb = (EntityValueBase) evb.cloneValue();
            this.writeMode = writeMode;
            this.pkMap = evb.getPrimaryKeys();
        }
    }
}
