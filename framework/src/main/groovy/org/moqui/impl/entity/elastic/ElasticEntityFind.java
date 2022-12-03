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

    @Override
    public EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
            EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated

        Map<String, Object> searchMap = new LinkedHashMap<>();
        // query
        if (whereCondition != null) searchMap.put("query", makeQueryMap(whereCondition));

        // TODO: use _source to get partial documents, some possible oddness to it: https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-source-field.html
        // TODO order by with fieldOptionsArray

        Map response = elasticClient.search(edf.getIndexName(ed), searchMap);

        // TODO
        return null;
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray)
            throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated


        // TODO: use _source to get partial documents, some possible oddness to it: https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-source-field.html

        /* FOR REFERENCE:
        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray)
        if (this.getDistinct()) efb.makeDistinct()

        // select fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, false)
        // FROM Clause
        efb.makeSqlFromClause()
        // WHERE clause
        efb.makeWhereClause()
        // GROUP BY clause
        efb.makeGroupByClause()
        // HAVING clause
        efb.makeHavingClause(havingCondition)

        // ORDER BY clause
        efb.makeOrderByClause(orderByExpanded, false)
        // LIMIT/OFFSET clause => from, size paramters: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-search.html
        // efb.addLimitOffset(this.limit, this.offset)
        if (offset) efb.sqlTopLevel.append(" SKIP ").append(offset)
        if (limit) efb.sqlTopLevel.append(" LIMIT ").append(limit)
        // FOR UPDATE (TODO: supported in ODB?)
        // if (this.forUpdate) efb.makeForUpdate()
         */

        EntityListIterator eli = null;

        try {
            edf.checkCreateDocumentIndex(ed);

            // TODO
            ArrayList<Map<String, Object>> documentList = null;
            // logger.warn("TOREMOVE: got OrientDb query results: ${documentList}")

            eli = new ElasticEntityListIterator(documentList, edf, ed, this.efi, txCache, whereCondition, orderByExpanded);
        } catch (EntityException e) {
            throw e;
        } catch (Throwable t) {
            throw new EntityException("Error in find", t);
        } finally {
            // TODO anything needed?
        }


        return eli;
    }

    @Override
    public long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");

        // TODO FUTURE: consider building a JSON string instead of Map/List structure with lots of objects,
        //     will perform better and have way less memory overhead, but code will be a lot more complicated


        /* FOR REFERENCE
        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray)

        // count function instead of select fields
        efb.sqlTopLevel.append("COUNT(1) ")
        // efb.makeCountFunction(fieldInfoArray, fieldOptionsArray, isDistinct, isGroupBy)
        // FROM Clause
        efb.makeSqlFromClause()
        // WHERE clause
        efb.makeWhereClause()
        // GROUP BY clause
        efb.makeGroupByClause()
        // HAVING clause
        efb.makeHavingClause(havingCondition)
         */
        long count = 0;

        try {
            edf.checkCreateDocumentIndex(ed);

            // TODO
        } catch (Exception e) {
            throw new EntityException("Error finding value", e);
        } finally {
            // TODO anything needed?
        }


        return count;
    }
}
