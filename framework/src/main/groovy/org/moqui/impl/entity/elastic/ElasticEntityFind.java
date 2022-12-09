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
package org.moqui.impl.entity.elastic;

import org.moqui.context.ElasticFacade;
import org.moqui.entity.EntityDynamicView;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityListIterator;
import org.moqui.impl.entity.*;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.util.CollectionUtilities;
import org.moqui.util.LiteStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ElasticEntityFind extends EntityFindBase {
    protected static final Logger logger = LoggerFactory.getLogger(ElasticEntityValue.class);
    private final ElasticDatasourceFactory edf;

    public ElasticEntityFind(EntityFacadeImpl efip, String entityName, ElasticDatasourceFactory edf) {
        super(efip, entityName);
        this.edf = edf;
    }

    @Override
    public EntityDynamicView makeEntityDynamicView() {
        throw new UnsupportedOperationException("EntityDynamicView is not yet supported for Orient DB");
    }

    Map<String, Object> makeQueryMap(EntityConditionImplBase whereCondition) {
        List<Map<String, Object>> filterList = new ArrayList<>();
        whereCondition.makeSearchFilter(filterList);
        Map<String, Object> boolMap = new HashMap<>();
        boolMap.put("filter", filterList);
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("bool", boolMap);
        return queryMap;
    }
    List<Object> makeSortList(ArrayList<String> orderByExpanded, EntityDefinition ed) {
        int orderByExpandedSize = orderByExpanded != null ? orderByExpanded.size() : 0;
        if (orderByExpandedSize > 0) {
            List<Object> sortList = new ArrayList<>(orderByExpandedSize);
            for (int i = 0; i < orderByExpandedSize; i++) {
                String sortField = orderByExpanded.get(i);
                EntityJavaUtil.FieldOrderOptions foo = new EntityJavaUtil.FieldOrderOptions(sortField);
                // to make this more fun, need to look for fields which have: keyword child field, text with no keyword (can't sort)
                String fieldName = foo.getFieldName();
                FieldInfo fi = ed.getFieldInfo(fieldName);
                if (ElasticDatasourceFactory.getEsEntityAddKeywordSet().contains(fi.type))
                    fieldName += ".keyword";
                else if ("text".equals(ElasticDatasourceFactory.getEsEntityTypeMap().get(fi.type)))
                    throw new IllegalArgumentException("Cannot sort by field " + fi.name + " with type " + fi.type);
                if (foo.getDescending()) {
                    sortList.add(CollectionUtilities.toHashMap(fieldName, "desc"));
                } else {
                    sortList.add(fieldName);
                }
            }
            // logger.warn("new sortList " + sortList + " from orderBy " + orderByExpanded);
            return sortList;
        }
        return null;
    }

    @Override
    public EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
            EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("Multi-entity view entities are not supported, Elastic/OpenSearch does not support joins; single-entity view entities for aggregations are not yet supported (future feature)");

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated

        // optimization if we have full PK: use ElasticClient.get()
        if (tempHasFullPk) {
            // we may have a singleCondField/Value OR simpleAndMap with the PK
            String combinedId;
            if (singleCondField != null) {
                combinedId = singleCondValue.toString();
            } else {
                combinedId = ed.getPrimaryKeysString(simpleAndMap);
            }
            Map getResponse = elasticClient.get(edf.getIndexName(ed), combinedId);
            if (getResponse == null) return null;
            Map dbValue = (Map) getResponse.get("_source");
            if (dbValue == null) return null;

            ElasticEntityValue newValue = new ElasticEntityValue(ed, efi, edf);
            LiteStringMap<Object> valueMap = newValue.getValueMap();

            FieldInfo[] allFieldArray = ed.entityInfo.allFieldInfoArray;
            for (int j = 0; j < allFieldArray.length; j++) {
                FieldInfo fi = allFieldArray[j];
                Object fValue = ElasticDatasourceFactory.convertFieldValue(fi, dbValue.get(fi.name));
                valueMap.putByIString(fi.name, fValue, fi.index);
            }

            newValue.setSyncedWithDb();
            return newValue;
        } else {
            Map<String, Object> searchMap = new LinkedHashMap<>();
            // query
            if (whereCondition != null) searchMap.put("query", makeQueryMap(whereCondition));
            // _source or fields
            // TODO: use _source or fields to get partial documents, some possible oddness to it: https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-source-field.html
            // size
            searchMap.put("size", 1);

            logger.warn("find one elastic searchMap " + searchMap);
            Map resultMap = elasticClient.search(edf.getIndexName(ed), searchMap);
            Map hitsMap = (Map) resultMap.get("hits");
            List hitsList = (List) hitsMap.get("hits");

            if (hitsList != null && hitsList.size() > 0) {
                Map firstHit = (Map) hitsList.get(0);
                if (firstHit != null) {
                    Map hitSource = (Map) firstHit.get("_source");
                    ElasticEntityValue newValue = new ElasticEntityValue(ed, efi, edf);
                    LiteStringMap<Object> valueMap = newValue.getValueMap();
                    int size = fieldInfoArray.length;
                    for (int i = 0; i < size; i++) {
                        FieldInfo fi = fieldInfoArray[i];
                        if (fi == null) break;
                        valueMap.putByIString(fi.name, hitSource.get(fi.name), fi.index);
                    }
                    newValue.setSyncedWithDb();
                    return newValue;
                }
            }
            return null;
        }
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray)
            throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("Multi-entity view entities are not supported, Elastic/OpenSearch does not support joins; single-entity view entities for aggregations are not yet supported (future feature)");
        if (havingCondition != null) throw new EntityException("Having condition not supported, no view-entity support yet (future feature along with single-entity view entities for aggregations)");
        // also not supported: if (this.getDistinct()) efb.makeDistinct()

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated

        Map<String, Object> searchMap = new LinkedHashMap<>();
        // query
        Map queryMap = whereCondition != null ? makeQueryMap(whereCondition) : null;
        if (queryMap == null || queryMap.isEmpty())
            queryMap = CollectionUtilities.toHashMap("match_all", Collections.EMPTY_MAP);
        searchMap.put("query", queryMap);
        // _source or fields
        // TODO: use _source or fields to get partial documents, some possible oddness to it: https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-source-field.html
        // sort with fieldOptionsArray
        List<Object> sortList = makeSortList(orderByExpanded, ed);
        if (sortList == null) {
            // if no sort, sort by PK fields by default (for pagination over large queries a sort order is always required)
            sortList = new LinkedList<>();
            FieldInfo[] pkFieldInfos = ed.entityInfo.pkFieldInfoArray;
            for (int i = 0; i < pkFieldInfos.length; i++) {
                FieldInfo fi = pkFieldInfos[i];
                sortList.add(fi.name);
            }
        }
        searchMap.put("sort", sortList);

        // from & size
        if (this.offset != null) searchMap.put("from", this.offset);
        if (this.limit != null) searchMap.put("size", this.limit);

        edf.checkCreateDocumentIndex(ed);

        return new ElasticEntityListIterator(searchMap, ed, fieldInfoArray, fieldOptionsArray, edf, txCache,
                whereCondition, orderByExpanded);
    }

    @Override
    public long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("Multi-entity view entities are not supported, Elastic/OpenSearch does not support joins; single-entity view entities for aggregations are not yet supported (future feature)");
        if (havingCondition != null) throw new EntityException("Having condition not supported, no view-entity support yet (future feature along with single-entity view entities for aggregations)");
        // also not supported: if (this.getDistinct()) efb.makeDistinct()

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated

        Map<String, Object> countMap = new LinkedHashMap<>();
        // query
        if (whereCondition != null) countMap.put("query", makeQueryMap(whereCondition));
        // NOTE: if no where condition don't need to add default all query map, ElasticClient.countResponse() does this (used by count())

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();
        return elasticClient.count(edf.getIndexName(ed), countMap);
    }
}
