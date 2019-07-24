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

import org.moqui.BaseArtifactException;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.entity.*;
import org.moqui.impl.context.TransactionCache;
import org.moqui.impl.entity.EntityJavaUtil.FindAugmentInfo;
import org.moqui.util.CollectionUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

public class EntityListIteratorImpl implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(EntityListIteratorImpl.class);
    protected final EntityFacadeImpl efi;
    private final TransactionCache txCache;
    protected final Connection con;
    private final ResultSet rs;
    private final FindAugmentInfo findAugmentInfo;
    private final int txcListSize;
    private int txcListIndex = -1;
    private final EntityDefinition entityDefinition;
    protected final FieldInfo[] fieldInfoArray;
    private final int fieldInfoListSize;
    private final EntityCondition queryCondition;
    private final CollectionUtilities.MapOrderByComparator orderByComparator;
    /** This is needed to determine if the ResultSet is empty as cheaply as possible. */
    private boolean haveMadeValue = false;
    protected boolean closed = false;
    private StackTraceElement[] constructStack = null;
    private final ArrayList<ArtifactExecutionInfo> artifactStack;

    public EntityListIteratorImpl(Connection con, ResultSet rs, EntityDefinition entityDefinition, FieldInfo[] fieldInfoArray,
                                  EntityFacadeImpl efi, TransactionCache txCache, EntityCondition queryCondition, ArrayList<String> obf) {
        this.efi = efi;
        this.con = con;
        this.rs = rs;
        this.entityDefinition = entityDefinition;
        fieldInfoListSize = fieldInfoArray.length;
        this.fieldInfoArray = fieldInfoArray;
        this.queryCondition = queryCondition;
        this.txCache = txCache;
        if (txCache != null && queryCondition != null) {
            orderByComparator = obf != null && obf.size() > 0 ? new CollectionUtilities.MapOrderByComparator(obf) : null;
            // add all created values (updated and deleted values will be handled by the next() method
            findAugmentInfo = txCache.getFindAugmentInfo(entityDefinition.getFullEntityName(), queryCondition);
            if (findAugmentInfo.valueListSize > 0) {
                // update the order if we know the order by field list
                if (orderByComparator != null) findAugmentInfo.valueList.sort(orderByComparator);
                txcListSize = findAugmentInfo.valueListSize;
            } else {
                txcListSize = 0;
            }
        } else {
            findAugmentInfo = null;
            txcListSize = 0;
            orderByComparator = null;
        }

        // capture the current artifact stack for finalize not closed debugging, has minimal performance impact (still ~0.0038ms per call compared to numbers below)
        artifactStack = new ArrayList<>(efi.ecfi.getEci().artifactExecutionFacade.getStack());

        /* uncomment only if needed temporarily: huge performance impact, ~0.036ms per call with, ~0.0037ms without (~10x difference!)
        StackTraceElement[] tempStack = Thread.currentThread().getStackTrace();
        if (tempStack.length > 20) tempStack = java.util.Arrays.copyOfRange(tempStack, 0, 20);
        constructStack = tempStack;
         */
    }

    @Override public void close() {
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

    @Override public void afterLast() {
        try { rs.afterLast();  }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to afterLast", e); }
        txcListIndex = txcListSize;
    }
    @Override public void beforeFirst() {
        txcListIndex = -1;
        try { rs.beforeFirst(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to beforeFirst", e); }
    }

    @Override public boolean last() {
        if (txcListSize > 0) {
            try { rs.afterLast(); }
            catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to last", e); }
            txcListIndex = txcListSize - 1;
            return true;
        } else {
            try { return rs.last(); }
            catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to last", e); }
        }
    }
    @Override public boolean first() {
        txcListIndex = -1;
        try { return rs.first(); }
        catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to first", e); }
    }

    @Override public EntityValue currentEntityValue() { return currentEntityValueBase(); }
    public EntityValueBase currentEntityValueBase() {
        EntityValueImpl newEntityValue = new EntityValueImpl(entityDefinition, efi);
        HashMap<String, Object> valueMap = newEntityValue.getValueMap();
        if (txcListIndex >= 0) {
            return findAugmentInfo.valueList.get(txcListIndex);
        } else {
            for (int i = 0; i < fieldInfoListSize; i++) {
                FieldInfo fi = fieldInfoArray[i];
                if (fi == null) break;
                fi.getResultSetValue(rs, i + 1, valueMap, efi);
            }
            // if txCache in place always put in cache for future reference (onePut handles any stale from DB issues too)
            if (txCache != null) txCache.onePut(newEntityValue, false);
        }
        haveMadeValue = true;

        return newEntityValue;
    }

    @Override public int currentIndex() {
        try { return rs.getRow() + txcListIndex + 1; }
        catch (SQLException e) { throw new EntityException("Error getting current index", e); }
    }
    @Override public boolean absolute(final int rowNum) {
        // TODO: somehow implement this for txcList? would need to know how many rows after last we tried to go
        if (txcListSize > 0) throw new EntityException("Cannot go to absolute row number when transaction cache is in place and there are augmenting creates; disable the tx cache before this operation");
        try { return rs.absolute(rowNum); }
        catch (SQLException e) { throw new EntityException("Error going to absolute row number " + rowNum, e); }
    }
    @Override public boolean relative(final int rows) {
        // TODO: somehow implement this for txcList? would need to know how many rows after last we tried to go
        if (txcListSize > 0) throw new EntityException("Cannot go to relative row number when transaction cache is in place and there are augmenting creates; disable the tx cache before this operation");
        try { return rs.relative(rows); }
        catch (SQLException e) { throw new EntityException("Error moving relative rows " + rows, e); }
    }

    @Override public boolean hasNext() {
        try {
            if (rs.isLast() || rs.isAfterLast()) {
                return txcListIndex < (txcListSize - 1);
            } else {
                // if not in the first or beforeFirst positions and haven't made any values yet, the result set is empty
                return !(!haveMadeValue && !rs.isBeforeFirst() && !rs.isFirst());
            }
        } catch (SQLException e) {
            throw new EntityException("Error while checking to see if there is a next result", e);
        }
    }
    @Override public boolean hasPrevious() {
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

    @Override public EntityValue next() {
        // first try the txcList if we are in it
        if (txcListIndex >= 0) {
            if (txcListIndex >= txcListSize) return null;
            txcListIndex++;
            if (txcListIndex >= txcListSize) return null;
            return currentEntityValue();
        }
        // not in txcList, try the DB
        try {
            if (rs.next()) {
                EntityValueBase evb = currentEntityValueBase();
                if (txCache != null) {
                    EntityJavaUtil.WriteMode writeMode = txCache.checkUpdateValue(evb, findAugmentInfo);
                    // if deleted skip this value
                    if (writeMode == EntityJavaUtil.WriteMode.DELETE) return next();
                }
                return evb;
            } else {
                if (txcListSize > 0) {
                    // txcListIndex should be -1, but instead of incrementing set to 0 just to make sure
                    txcListIndex = 0;
                    return currentEntityValue();
                } else {
                    return null;
                }
            }
        } catch (SQLException e) {
            throw new EntityException("Error getting next result", e);
        }
    }
    @Override public int nextIndex() { return currentIndex() + 1; }

    @Override public EntityValue previous() {
        // first try the txcList if we are in it
        if (txcListIndex >= 0) {
            txcListIndex--;
            if (txcListIndex >= 0) return currentEntityValue();
        }
        try {
            if (rs.previous()) {
                EntityValueBase evb = (EntityValueBase) currentEntityValue();
                if (txCache != null) {
                    EntityJavaUtil.WriteMode writeMode = txCache.checkUpdateValue(evb, findAugmentInfo);
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
    @Override public int previousIndex() { return currentIndex() - 1; }

    @Override public void setFetchSize(int rows) {
        try { rs.setFetchSize(rows); }
        catch (SQLException e) { throw new EntityException("Error setting fetch size", e); }
    }

    @Override public EntityList getCompleteList(boolean closeAfter) {
        try {
            // move back to before first if we need to
            if (haveMadeValue && !rs.isBeforeFirst()) rs.beforeFirst();

            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = next()) != null) list.add(value);

            if (findAugmentInfo != null) {
                // all created, updated, and deleted values will be handled by the next() method
                // update the order if we know the order by field list
                if (orderByComparator != null) list.sort(orderByComparator);
            }

            return list;
        } catch (SQLException e) {
            throw new EntityException("Error getting all results", e);
        } finally {
            if (closeAfter) close();
        }
    }

    @Override public EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        // TODO: somehow handle txcList after DB list? same issue as absolute() and relative() methods
        if (txcListSize > 0) throw new EntityException("Cannot get partial list when transaction cache is in place and there are augmenting creates; disable the tx cache before this operation");
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
        throw new BaseArtifactException("EntityListIterator.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void add(EntityValue e) {
        throw new BaseArtifactException("EntityListIterator.add() not currently supported");
        // TODO implement this
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!closed) {
                StringBuilder errorSb = new StringBuilder(1000);
                errorSb.append("EntityListIterator not closed for entity [").append(entityDefinition.getFullEntityName())
                        .append("], caught in finalize()");
                if (constructStack != null) for (int i = 0; i < constructStack.length; i++)
                    errorSb.append("\n").append(constructStack[i].toString());
                if (artifactStack != null) for (int i = 0; i < artifactStack.size(); i++)
                    errorSb.append("\n").append(artifactStack.get(i).toBasicString());
                logger.error(errorSb.toString());

                this.close();
            }
        } catch (Exception e) {
            logger.error("Error closing the ResultSet or Connection in finalize EntityListIterator", e);
        }

        super.finalize();
    }
}
