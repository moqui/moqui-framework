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

import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityListIterator;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.TransactionCache;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.entity.EntityJavaUtil;
import org.moqui.impl.entity.EntityListImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Map;

public class ElasticEntityListIterator implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(ElasticEntityListIterator.class);
    protected EntityFacadeImpl efi;
    protected final TransactionCache txCache;
    protected ElasticDatasourceFactory edf;
    protected ArrayList<Map<String, Object>> documentList;
    protected int internalIndex = -1;
    protected EntityDefinition entityDefinition;
    protected EntityCondition queryCondition = null;
    protected ArrayList<String> orderByFields = null;
    // haveMadeValue is needed to determine if the ResultSet is empty as cheaply as possible
    protected boolean haveMadeValue = false;
    protected boolean closed = false;

    public ElasticEntityListIterator(ArrayList<Map<String, Object>> documentList, ElasticDatasourceFactory edf,
            EntityDefinition entityDefinition, EntityFacadeImpl efi, TransactionCache txCache,
            EntityCondition queryCondition, ArrayList<String> obf) {
        this.efi = efi;
        this.edf = edf;
        this.documentList = documentList;
        this.entityDefinition = entityDefinition;
        this.queryCondition = queryCondition;
        this.orderByFields = obf;
        this.txCache = txCache != null ? txCache : efi.ecfi.transactionFacade.getTransactionCache();
    }

    @Override
    public void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity [" + this.entityDefinition.getEntityName() + "] is already closed, not closing again");
        } else {
            // TODO: different things might be needed here if we can stream results from ES somehow, perhaps paged queries but with no cursor (separate calls)
            this.closed = true;
        }

    }

    @Override
    public void afterLast() {
        this.internalIndex = documentList.size();
    }

    @Override
    public void beforeFirst() {
        internalIndex = -1;
    }

    @Override
    public boolean last() {
        internalIndex = (documentList.size() - 1);
        return true;
    }

    @Override
    public boolean first() {
        internalIndex = 0;
        return true;
    }

    @Override
    public EntityValue currentEntityValue() {
        ElasticEntityValue newEntityValue = new ElasticEntityValue(entityDefinition, efi, edf);
        newEntityValue.putAll(documentList.get(internalIndex));
        this.haveMadeValue = true;
        return newEntityValue;
    }

    @Override
    public int currentIndex() {
        return internalIndex;
    }

    @Override
    public boolean absolute(int rowNum) {
        internalIndex = rowNum;
        return !(internalIndex < 0 || internalIndex >= documentList.size());
    }

    @Override
    public boolean relative(int rows) {
        internalIndex += rows;
        return !(internalIndex < 0 || internalIndex >= documentList.size());
    }

    @Override
    public boolean hasNext() {
        return internalIndex < (documentList.size() - 1);
    }

    @Override
    public boolean hasPrevious() {
        return internalIndex > 0;
    }

    @Override
    public EntityValue next() {
        internalIndex = internalIndex++;
        if (internalIndex >= documentList.size()) return null;
        return currentEntityValue();
    }

    @Override
    public int nextIndex() {
        return internalIndex + 1;
    }

    @Override
    public EntityValue previous() {
        internalIndex = internalIndex--;
        if (internalIndex < 0) return null;
        return currentEntityValue();
    }

    @Override
    public int previousIndex() {
        return internalIndex - 1;
    }

    @Override
    public void setFetchSize(int rows) {/* do nothing, just ignore */}

    @Override
    public EntityList getCompleteList(boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = this.next()) != null) {
                list.add(value);
            }


            if (txCache != null && queryCondition != null) {
                // add all created values (updated and deleted values will be handled by the next() method
                EntityJavaUtil.FindAugmentInfo tempFai = txCache.getFindAugmentInfo(entityDefinition.getFullEntityName(), queryCondition);
                if (tempFai.valueListSize > 0) {
                    // remove update values already in list
                    if (tempFai.foundUpdated.size() > 0) {
                        int valueListSize = list.size();
                        for (int i = 0; i < valueListSize ;){
                            EntityValue ev = list.get(i);
                            if (tempFai.foundUpdated.contains(ev.getPrimaryKeys())) {
                                list.remove(i);
                            } else {
                                i++;
                            }

                        }

                    }

                    list.addAll(tempFai.valueList);
                    // update the order if we know the order by field list
                    if (orderByFields != null && orderByFields.size() > 0) list.orderByFields(orderByFields);
                }

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
            EntityValue nextValue = null;
            while (limit > numberSoFar && (nextValue = this.next()) != null) {
                list.add(nextValue);
                numberSoFar = numberSoFar++;
            }

            return list;
        } finally {
            if (closeAfter) close();
        }

    }

    @Override
    public int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0;
        // move back to before first if we need to
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlText(writer, prefix, dependentLevels);
        return recordsWritten;
    }

    @Override
    public int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0;
        // move back to before first if we need to
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName);
        return recordsWritten;
    }

    @Override
    public void remove() {
        throw new IllegalArgumentException("ElasticEntityListIterator.remove() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void set(EntityValue e) {
        throw new IllegalArgumentException("ElasticEntityListIterator.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void add(EntityValue e) {
        throw new IllegalArgumentException("ElasticEntityListIterator.add() not currently supported");
        // TODO implement this
    }

    @Override
    public void finalize() throws Throwable {
        if (!closed) {
            this.close();
            logger.error("ElasticEntityListIterator not closed for entity [" + entityDefinition.getEntityName() + "], caught in finalize()");
        }

        super.finalize();
    }
}
