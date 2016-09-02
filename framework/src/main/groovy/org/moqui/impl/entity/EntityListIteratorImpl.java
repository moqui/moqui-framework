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

import org.moqui.entity.*;
import org.moqui.impl.context.TransactionCache;
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

public class EntityListIteratorImpl implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(EntityListIteratorImpl.class);
    protected final EntityFacadeImpl efi;
    private final TransactionCache txCache;
    protected final Connection con;
    private final ResultSet rs;
    private final EntityDefinition entityDefinition;
    protected final FieldInfo[] fieldInfoArray;
    private final int fieldInfoListSize;
    private EntityCondition queryCondition = null;
    protected List<String> orderByFields = null;
    /** This is needed to determine if the ResultSet is empty as cheaply as possible. */
    private boolean haveMadeValue = false;
    protected boolean closed = false;

    public EntityListIteratorImpl(Connection con, ResultSet rs, EntityDefinition entityDefinition, FieldInfo[] fieldInfoArray,
                                  EntityFacadeImpl efi, TransactionCache txCache) {
        this.efi = efi;
        this.con = con;
        this.rs = rs;
        this.entityDefinition = entityDefinition;
        fieldInfoListSize = fieldInfoArray.length;
        this.fieldInfoArray = fieldInfoArray;
        this.txCache = txCache;
    }

    public void setQueryCondition(EntityCondition ec) {
        this.queryCondition = ec;
    }

    public void setOrderByFields(List<String> obf) {
        this.orderByFields = obf;
    }

    @Override
    public void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity [" + this.entityDefinition.getFullEntityName() + "] is already closed, not closing again");
        } else {
            if (rs != null) {
                try { rs.close(); }
                catch (SQLException e) { throw new EntityException("Could not close ResultSet in EntityListIterator", e); }
            }

            if (con != null) {
                try { con.close(); }
                catch (SQLException e) { throw new EntityException("Could not close Connection in EntityListIterator", e); }
            }

            /* leaving commented as might be useful for future con pool debugging:
            try {
                def dataSource = efi.getDatasourceFactory(entityDefinition.getEntityGroupName()).getDataSource()
                logger.warn("=========== elii after close pool available size: ${dataSource.poolAvailableSize()}/${dataSource.poolTotalSize()}; ${dataSource.getMinPoolSize()}-${dataSource.getMaxPoolSize()}")
            } catch (Throwable t) {
                logger.warn("========= pool size error ${t.toString()}")
            }
            */
            this.closed = true;
        }

    }

    @Override
    public void afterLast() {
        try { rs.afterLast(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to afterLast", e); }
    }

    @Override
    public void beforeFirst() {
        try { rs.beforeFirst(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to beforeFirst", e); }
    }

    @Override
    public boolean last() {
        try { return rs.last(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to last", e); }
    }

    @Override
    public boolean first() {
        try { return rs.first(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to first", e); }
    }

    @Override
    public EntityValue currentEntityValue() { return currentEntityValueBase(); }

    public EntityValueBase currentEntityValueBase() {
        EntityValueImpl newEntityValue = new EntityValueImpl(entityDefinition, efi);
        HashMap<String, Object> valueMap = newEntityValue.getValueMap();
        for (int i = 0; i < fieldInfoListSize; i++) {
            FieldInfo fi = fieldInfoArray[i];
            if (fi == null) break;
            EntityJavaUtil.getResultSetValue(rs, i + 1, fi, valueMap, efi);
        }
        haveMadeValue = true;

        // if txCache in place always put in cache for future reference (onePut handles any stale from DB issues too)
        if (txCache != null) txCache.onePut(newEntityValue);

        return newEntityValue;
    }

    @Override
    public int currentIndex() {
        try { return rs.getRow(); }
        catch (SQLException e) { throw new EntityException("Error getting current index", e); }
    }

    @Override
    public boolean absolute(final int rowNum) {
        try { return rs.absolute(rowNum); }
        catch (SQLException e) { throw new EntityException("Error going to absolute row number " + rowNum, e); }
    }

    @Override
    public boolean relative(final int rows) {
        try { return rs.relative(rows); }
        catch (SQLException e) { throw new EntityException("Error moving relative rows " + rows, e); }
    }

    @Override
    public boolean hasNext() {
        try {
            if (rs.isLast() || rs.isAfterLast()) {
                return false;
            } else {
                // if not in the first or beforeFirst positions and haven't made any values yet, the result set is empty
                return !(!haveMadeValue && !rs.isBeforeFirst() && !rs.isFirst());
            }
        } catch (SQLException e) {
            throw new EntityException("Error while checking to see if there is a next result", e);
        }
    }

    @Override
    public boolean hasPrevious() {
        try {
            if (rs.isFirst() || rs.isBeforeFirst()) {
                return false;
            } else {
                // if not in the last or afterLast positions and we haven't made any values yet, the result set is empty
                return !(!haveMadeValue && !rs.isAfterLast() && !rs.isLast());
            }
        } catch (SQLException e) {
            throw new EntityException("Error while checking to see if there is a previous result", e);
        }
    }

    @Override
    public EntityValue next() {
        try {
            if (rs.next()) {
                EntityValueBase evb = currentEntityValueBase();
                if (txCache != null) {
                    EntityJavaUtil.WriteMode writeMode = txCache.checkUpdateValue(evb);
                    // if deleted skip this value
                    if (writeMode == EntityJavaUtil.WriteMode.DELETE) return next();
                }

                return evb;
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new EntityException("Error getting next result", e);
        }
    }

    @Override
    public int nextIndex() { return currentIndex() + 1; }

    @Override
    public EntityValue previous() {
        try {
            if (rs.previous()) {
                EntityValueBase evb = (EntityValueBase) currentEntityValue();
                if (txCache != null) {
                    EntityJavaUtil.WriteMode writeMode = txCache.checkUpdateValue(evb);
                    // if deleted skip this value
                    if (writeMode == EntityJavaUtil.WriteMode.DELETE) return this.previous();
                }
                return evb;
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new EntityException("Error getting previous result", e);
        }
    }

    @Override
    public int previousIndex() { return currentIndex() - 1; }

    @Override
    public void setFetchSize(int rows) {
        try { rs.setFetchSize(rows); }
        catch (SQLException e) { throw new EntityException("Error setting fetch size", e); }
    }

    @Override
    public EntityList getCompleteList(boolean closeAfter) {
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst();

            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = next()) != null)  list.add(value);

            if (txCache != null && queryCondition != null) {
                // add all created values (updated and deleted values will be handled by the next() method
                List<EntityValueBase> cvList = txCache.getCreatedValueList(entityDefinition.getFullEntityName(), queryCondition);
                list.addAll(cvList);
                // update the order if we know the order by field list
                if (orderByFields != null && cvList.size() > 0) list.orderByFields(orderByFields);
            }

            return list;
        } catch (SQLException e) {
            throw new EntityException("Error getting all results", e);
        } finally {
            if (closeAfter) close();
        }
    }

    @Override
    public EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(this.efi);
            if (limit == 0) return list;

            // list is 1 based
            if (offset == 0) offset = 1;

            // jump to start index, or just get the first result
            if (!this.absolute(offset)) {
                // not that many results, get empty list
                return list;
            }

            // get the first as the current one
            list.add(this.currentEntityValue());

            int numberSoFar = 1;
            EntityValue nextValue;
            while (limit > numberSoFar && (nextValue = this.next()) != null) {
                list.add(nextValue);
                numberSoFar++;
            }

            return list;
        } finally {
            if (closeAfter) close();
        }
    }

    @Override
    public int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0;
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst();
            EntityValue value;
            while ((value = this.next()) != null) recordsWritten += value.writeXmlText(writer, prefix, dependentLevels);
        } catch (SQLException e) {
            throw new EntityException("Error writing XML for all results", e);
        }

        return recordsWritten;
    }
    @Override
    public int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0;
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst();
            EntityValue value;
            while ((value = this.next()) != null)
                recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName);
        } catch (SQLException e) {
            throw new EntityException("Error writing XML for all results", e);
        }

        return recordsWritten;
    }

    @Override
    public void remove() {
        // TODO: call EECAs
        try {
            efi.getEntityCache().clearCacheForValue((EntityValueBase) currentEntityValue(), false);
            rs.deleteRow();
        } catch (SQLException e) {
            throw new EntityException("Error removing row", e);
        }
    }

    @Override
    public void set(EntityValue e) {
        throw new IllegalArgumentException("EntityListIterator.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void add(EntityValue e) {
        throw new IllegalArgumentException("EntityListIterator.add() not currently supported");
        // TODO implement this
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!closed) {
                this.close();
                logger.error("EntityListIterator not closed for entity [" + entityDefinition.getFullEntityName() + "], caught in finalize()");
            }
        } catch (Exception e) {
            logger.error("Error closing the ResultSet or Connection in finalize EntityListIterator", e);
        }

        super.finalize();
    }
}
