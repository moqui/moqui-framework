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

import org.moqui.entity.EntityDynamicView;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityListIterator;
import org.moqui.impl.entity.*;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;

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

    @Override
    public EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();

        edf.checkCreateDocumentIndex(ed);

        // TODO
        return null;
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray)
            throws EntityException {
        EntityDefinition ed = this.getEntityDef();

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
        // LIMIT/OFFSET clause
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
