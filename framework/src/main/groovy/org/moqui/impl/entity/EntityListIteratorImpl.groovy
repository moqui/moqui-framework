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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.impl.context.TransactionCache
import org.moqui.impl.entity.EntityJavaUtil.WriteMode

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.entity.EntityException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityListIteratorImpl implements EntityListIterator {
    protected final static Logger logger = LoggerFactory.getLogger(EntityListIteratorImpl.class)

    protected final EntityFacadeImpl efi
    protected final TransactionCache txCache

    protected final Connection con
    protected final ResultSet rs

    protected final EntityDefinition entityDefinition
    protected final ArrayList<EntityJavaUtil.FieldInfo> fieldInfoList
    protected final int fieldInfoListSize
    protected EntityCondition queryCondition = (EntityCondition) null
    protected List<String> orderByFields = (List<String>) null

    /** This is needed to determine if the ResultSet is empty as cheaply as possible. */
    protected boolean haveMadeValue = false

    protected boolean closed = false

    EntityListIteratorImpl(Connection con, ResultSet rs, EntityDefinition entityDefinition,
                           ArrayList<EntityJavaUtil.FieldInfo> fieldInfoList, EntityFacadeImpl efi) {
        this.efi = efi
        this.con = con
        this.rs = rs
        this.entityDefinition = entityDefinition
        this.fieldInfoList = fieldInfoList
        fieldInfoListSize = fieldInfoList.size()
        this.txCache = efi.getEcfi().getTransactionFacade().getTransactionCache()
    }

    void setQueryCondition(EntityCondition ec) { this.queryCondition = ec }
    void setOrderByFields(List<String> obf) { this.orderByFields = obf }

    @Override
    void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity [${this.entityDefinition.getFullEntityName()}] is already closed, not closing again")
        } else {
            if (rs != null) {
                try { rs.close() }
                catch (SQLException e) { throw new EntityException("Could not close ResultSet in EntityListIterator", e) }
            }
            if (con != null) {
                try { con.close() }
                catch (SQLException e) { throw new EntityException("Could not close Connection in EntityListIterator", e) }
            }

            /* leaving commented as might be useful for future con pool debugging:
            try {
                def dataSource = efi.getDatasourceFactory(entityDefinition.getEntityGroupName()).getDataSource()
                logger.warn("=========== elii after close pool available size: ${dataSource.poolAvailableSize()}/${dataSource.poolTotalSize()}; ${dataSource.getMinPoolSize()}-${dataSource.getMaxPoolSize()}")
            } catch (Throwable t) {
                logger.warn("========= pool size error ${t.toString()}")
            }
            */
            this.closed = true
        }
    }

    @Override
    void afterLast() {
        try {
            rs.afterLast()
        } catch (SQLException e) {
            throw new EntityException("Error moving EntityListIterator to afterLast", e)
        }
    }

    @Override
    void beforeFirst() {
        try {
            rs.beforeFirst()
        } catch (SQLException e) {
            throw new EntityException("Error moving EntityListIterator to beforeFirst", e)
        }
    }

    @Override
    boolean last() {
        try {
            return rs.last()
        } catch (SQLException e) {
            throw new EntityException("Error moving EntityListIterator to last", e)
        }
    }

    @Override
    boolean first() {
        try {
            return rs.first()
        } catch (SQLException e) {
            throw new EntityException("Error moving EntityListIterator to first", e)
        }
    }

    @Override
    EntityValue currentEntityValue() { return currentEntityValueBase() }
    EntityValueBase currentEntityValueBase() {
        EntityValueImpl newEntityValue = new EntityValueImpl(entityDefinition, efi)
        Map<String, Object> valueMap = newEntityValue.getValueMap()
        String entityName = entityDefinition.getFullEntityName()
        boolean checkUserFields = entityDefinition.allowUserField
        for (int i = 0; i < fieldInfoListSize; i++) {
            EntityJavaUtil.FieldInfo fi = (EntityJavaUtil.FieldInfo) fieldInfoList.get(i)
            if (fi.isUserField && !checkUserFields) continue
            EntityQueryBuilder.getResultSetValue(rs, i+1, fi, valueMap, entityName, efi)
        }

        this.haveMadeValue = true

        // if txCache in place always put in cache for future reference (onePut handles any stale from DB issues too)
        if (txCache != null) txCache.onePut(newEntityValue)

        return newEntityValue
    }

    @Override
    int currentIndex() {
        try {
            return rs.getRow()
        } catch (SQLException e) {
            throw new EntityException("Error getting current index", e)
        }
    }

    @Override
    boolean absolute(int rowNum) {
        try {
            return rs.absolute(rowNum)
        } catch (SQLException e) {
            throw new EntityException("Error going to absolute row number [${rowNum}]", e)
        }
    }

    @Override
    boolean relative(int rows) {
        try {
            return rs.relative(rows)
        } catch (SQLException e) {
            throw new EntityException("Error moving relative rows [${rows}]", e)
        }
    }

    @Override
    boolean hasNext() {
        try {
            if (rs.isLast() || rs.isAfterLast()) {
                return false
            } else {
                // if not in the first or beforeFirst positions and haven't made any values yet, the result set is empty
                return !(!haveMadeValue && !rs.isBeforeFirst() && !rs.isFirst())
            }
        } catch (SQLException e) {
            throw new EntityException("Error while checking to see if there is a next result", e)
        }
    }

    @Override
    boolean hasPrevious() {
        try {
            if (rs.isFirst() || rs.isBeforeFirst()) {
                return false
            } else {
                // if not in the last or afterLast positions and we haven't made any values yet, the result set is empty
                return !(!haveMadeValue && !rs.isAfterLast() && !rs.isLast())
            }
        } catch (SQLException e) {
            throw new EntityException("Error while checking to see if there is a previous result", e)
        }
    }

    @Override
    EntityValue next() {
        try {
            if (rs.next()) {
                EntityValueBase evb = currentEntityValueBase()
                if (txCache != null) {
                    WriteMode writeMode = txCache.checkUpdateValue(evb)
                    // if deleted skip this value
                    if (writeMode == WriteMode.DELETE) return next()
                }
                return evb
            } else {
                return (EntityValue) null
            }
        } catch (SQLException e) {
            throw new EntityException("Error getting next result", e)
        }
    }

    @Override
    int nextIndex() {
        return currentIndex() + 1
    }

    @Override
    EntityValue previous() {
        try {
            if (rs.previous()) {
                EntityValueBase evb = (EntityValueBase) currentEntityValue()
                if (txCache != null) {
                    WriteMode writeMode = txCache.checkUpdateValue(evb)
                    // if deleted skip this value
                    if (writeMode == WriteMode.DELETE) return this.previous()
                }
                return evb
            } else {
                return (EntityValue) null
            }
        } catch (SQLException e) {
            throw new EntityException("Error getting previous result", e)
        }
    }

    @Override
    int previousIndex() {
        return currentIndex() - 1
    }

    @Override
    void setFetchSize(int rows) {
        try {
            rs.setFetchSize(rows)
        } catch (SQLException e) {
            throw new EntityException("Error setting fetch size", e)
        }
    }

    @Override
    EntityList getCompleteList(boolean closeAfter) {
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) {
                rs.beforeFirst()
            }
            EntityList list = new EntityListImpl(efi)
            EntityValue value
            while ((value = next()) != null) {
                list.add(value)
            }

            if (txCache != null && queryCondition != null) {
                // add all created values (updated and deleted values will be handled by the next() method
                List<EntityValueBase> cvList = txCache.getCreatedValueList(entityDefinition.getFullEntityName(), queryCondition)
                list.addAll(cvList)
                // update the order if we know the order by field list
                if (orderByFields != null && cvList) list.orderByFields(orderByFields)
            }

            return list
        } catch (SQLException e) {
            throw new EntityException("Error getting all results", e)
        } finally {
            if (closeAfter) close()
        }
    }

    @Override
    EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(this.efi)
            if (limit == 0) return list

            // list is 1 based
            if (offset == 0) offset = 1

            // jump to start index, or just get the first result
            if (!this.absolute(offset)) {
                // not that many results, get empty list
                return list
            }

            // get the first as the current one
            list.add(this.currentEntityValue())

            int numberSoFar = 1
            EntityValue nextValue = (EntityValue) null
            while (limit > numberSoFar && (nextValue = this.next()) != null) {
                list.add(nextValue)
                numberSoFar++
            }
            return list
        } catch (SQLException e) {
            throw new EntityException("Error getting partial results", e)
        } finally {
            if (closeAfter) close()
        }
    }

    @Override
    int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst()
            EntityValue value
            while ((value = this.next()) != null)
                recordsWritten += value.writeXmlText(writer, prefix, dependentLevels)
        } catch (SQLException e) { throw new EntityException("Error writing XML for all results", e) }
        return recordsWritten
    }
    @Override
    int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst()
            EntityValue value
            while ((value = this.next()) != null)
                recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName)
        } catch (SQLException e) { throw new EntityException("Error writing XML for all results", e) }
        return recordsWritten
    }

    @Override
    void remove() {
        // TODO: call EECAs
        try {
            efi.getEntityCache().clearCacheForValue((EntityValueBase) currentEntityValue(), false)
            rs.deleteRow()
        } catch (SQLException e) {
            throw new EntityException("Error removing row", e)
        }
    }

    @Override
    void set(EntityValue e) {
        throw new IllegalArgumentException("EntityListIterator.set() not currently supported")
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    void add(EntityValue e) {
        throw new IllegalArgumentException("EntityListIterator.add() not currently supported")
        // TODO implement this
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!closed) {
                this.close()
                logger.error("EntityListIterator not closed for entity [${entityDefinition.getFullEntityName()}], caught in finalize()")
            }
        } catch (Exception e) {
            logger.error("Error closing the ResultSet or Connection in finalize EntityListIterator", e);
        }
        super.finalize()
    }
}
