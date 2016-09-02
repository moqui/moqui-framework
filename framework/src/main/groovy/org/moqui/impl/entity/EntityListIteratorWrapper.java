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

import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityListIterator;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.TransactionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.List;

class EntityListIteratorWrapper implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(EntityListIteratorWrapper.class);
    protected EntityFacadeImpl efi;
    private final TransactionCache txCache;
    protected List<EntityValue> valueList;
    private int internalIndex = -1;
    private EntityDefinition entityDefinition;
    // private ArrayList<String> fieldsSelected;
    private EntityCondition queryCondition = null;
    protected List<String> orderByFields = null;
    /**
     * This is needed to determine if the ResultSet is empty as cheaply as possible.
     */
    private boolean haveMadeValue = false;
    protected boolean closed = false;

    EntityListIteratorWrapper(List<EntityValue> valueList, EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.efi = efi;
        this.valueList = valueList;
        this.entityDefinition = entityDefinition;
        // this.fieldsSelected = fieldsSelected;
        this.txCache = efi.getEcfi().getTransactionFacade().getTransactionCache();
    }

    public void setQueryCondition(EntityCondition ec) { this.queryCondition = ec; }

    public void setOrderByFields(List<String> obf) { this.orderByFields = obf; }

    @Override
    public void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity [" + this.entityDefinition.getEntityName() + "] is already closed, not closing again");
        } else {
            this.closed = true;
        }
    }

    @Override
    public void afterLast() { this.internalIndex = valueList.size(); }
    @Override
    public void beforeFirst() { internalIndex = -1; }
    @Override
    public boolean last() {
        internalIndex = (valueList.size() - 1);
        return true;
    }
    @Override
    public boolean first() {
        internalIndex = 0;
        return true;
    }

    @Override
    public EntityValue currentEntityValue() {
        this.haveMadeValue = true;
        return valueList.get(internalIndex);
    }
    @Override
    public int currentIndex() { return internalIndex; }

    @Override
    public boolean absolute(int rowNum) {
        internalIndex = rowNum;
        return !(internalIndex < 0 || internalIndex >= valueList.size());
    }
    @Override
    public boolean relative(int rows) {
        internalIndex += rows;
        return !(internalIndex < 0 || internalIndex >= valueList.size());
    }

    @Override
    public boolean hasNext() { return internalIndex < (valueList.size() - 1); }
    @Override
    public boolean hasPrevious() { return internalIndex > 0; }

    @Override
    public EntityValue next() {
        internalIndex++;
        if (internalIndex >= valueList.size()) return null;
        return currentEntityValue();
    }

    @Override
    public int nextIndex() { return internalIndex + 1; }

    @Override
    public EntityValue previous() {
        internalIndex--;
        if (internalIndex < 0) return null;
        return currentEntityValue();
    }

    @Override
    public int previousIndex() { return internalIndex - 1; }

    @Override
    public void setFetchSize(int rows) {/* do nothing, just ignore */}

    @Override
    public EntityList getCompleteList(boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = this.next()) != null) list.add(value);

            if (txCache != null && queryCondition != null) {
                // add all created values (updated and deleted values will be handled by the next() method
                List<EntityValueBase> cvList = txCache.getCreatedValueList(entityDefinition.getFullEntityName(), queryCondition);
                list.addAll(cvList);
                // update the order if we know the order by field list
                if (orderByFields != null && cvList.size() > 0) list.orderByFields(orderByFields);
            }

            return list;
        } finally {
            if (closeAfter) close();
        }
    }

    @Override
    public EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(this.efi);
            if (limit == 0) return list;

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
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlText(writer, prefix, dependentLevels);
        return recordsWritten;
    }

    @Override
    public int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0;
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName);
        return recordsWritten;
    }

    @Override
    public void remove() {
        throw new IllegalArgumentException("EntityListIteratorWrapper.remove() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void set(EntityValue e) {
        throw new IllegalArgumentException("EntityListIteratorWrapper.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void add(EntityValue e) {
        throw new IllegalArgumentException("EntityListIteratorWrapper.add() not currently supported");
        // TODO implement this
    }

    @Override
    protected void finalize() throws Throwable {
        if (!closed) {
            this.close();
            logger.error("EntityListIteratorWrapper not closed for entity [" + entityDefinition.getEntityName() + "], caught in finalize()");
        }

        super.finalize();
    }
}
