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
package org.moqui.impl.entity.orientdb

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.record.impl.ODocument
import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.context.TransactionCache
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityListImpl
import org.moqui.impl.entity.EntityValueBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.SQLException

@CompileStatic
class OrientEntityListIterator implements EntityListIterator {
    protected final static Logger logger = LoggerFactory.getLogger(OrientEntityListIterator.class)

    protected EntityFacadeImpl efi
    protected final TransactionCache txCache

    protected OrientDatasourceFactory odf
    protected ODatabaseDocumentTx oddt
    protected List<ODocument> documentList
    // start out before first
    protected int internalIndex = -1

    protected EntityDefinition entityDefinition
    protected ArrayList<String> fieldsSelected
    protected EntityCondition queryCondition = null
    protected List<String> orderByFields = null

    /** This is needed to determine if the ResultSet is empty as cheaply as possible. */
    protected boolean haveMadeValue = false

    protected boolean closed = false

    OrientEntityListIterator(OrientDatasourceFactory odf, ODatabaseDocumentTx oddt, List<ODocument> documentList,
                             EntityDefinition entityDefinition, ArrayList<String> fieldsSelected, EntityFacadeImpl efi) {
        this.efi = efi
        this.odf = odf
        this.oddt = oddt
        this.documentList = documentList
        this.entityDefinition = entityDefinition
        this.fieldsSelected = fieldsSelected
        this.txCache = efi.getEcfi().getTransactionFacade().getTransactionCache()
    }

    void setQueryCondition(EntityCondition ec) { this.queryCondition = ec }
    void setOrderByFields(List<String> obf) { this.orderByFields = obf }

    @Override
    void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity [${this.entityDefinition.getEntityName()}] is already closed, not closing again")
        } else {
            if (oddt != null) {
                try {
                    oddt.close()
                } catch (SQLException e) {
                    throw new EntityException("Could not close ODatabaseDocumentTx in OrientEntityListIterator", e)
                }
            }
            this.closed = true
        }
    }

    @Override
    void afterLast() { this.internalIndex = documentList.size() }

    @Override
    void beforeFirst() { internalIndex = -1 }

    @Override
    boolean last() {
        internalIndex = (documentList.size() - 1)
        return true
    }

    @Override
    boolean first() {
        internalIndex = 0
        return true
    }

    @Override
    EntityValue currentEntityValue() {
        OrientEntityValue newEntityValue = new OrientEntityValue(entityDefinition, efi, odf,
                documentList.get(internalIndex))
        this.haveMadeValue = true
        return newEntityValue
    }

    @Override
    int currentIndex() { return internalIndex }

    @Override
    boolean absolute(int rowNum) {
        internalIndex = rowNum
        return !(internalIndex < 0 || internalIndex >= documentList.size())
    }

    @Override
    boolean relative(int rows) {
        internalIndex += rows
        return !(internalIndex < 0 || internalIndex >= documentList.size())
    }

    @Override
    boolean hasNext() { return internalIndex < (documentList.size() - 1) }

    @Override
    boolean hasPrevious() { return internalIndex > 0 }

    @Override
    EntityValue next() {
        internalIndex++
        if (internalIndex >= documentList.size()) return null
        return currentEntityValue()
    }

    @Override
    int nextIndex() { return internalIndex + 1 }

    @Override
    EntityValue previous() {
        internalIndex--
        if (internalIndex < 0) return null
        return currentEntityValue()
    }

    @Override
    int previousIndex() { return internalIndex - 1 }

    @Override
    void setFetchSize(int rows) { /* do nothing, just ignore */ }

    @Override
    EntityList getCompleteList(boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(efi)
            EntityValue value
            while ((value = this.next()) != null) {
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
        } finally {
            if (closeAfter) close()
        }
    }

    @Override
    EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(this.efi)
            if (limit == 0) return list

            // jump to start index, or just get the first result
            if (!this.absolute(offset)) {
                // not that many results, get empty list
                return list
            }

            // get the first as the current one
            list.add(this.currentEntityValue())

            int numberSoFar = 1
            EntityValue nextValue = null
            while (limit > numberSoFar && (nextValue = this.next()) != null) {
                list.add(nextValue)
                numberSoFar++
            }
            return list
        } finally {
            if (closeAfter) close()
        }
    }

    @Override
    int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0
        // move back to before first if we need to
        if (haveMadeValue && internalIndex != -1) internalIndex = -1
        EntityValue value
        while ((value = this.next()) != null) {
            recordsWritten += value.writeXmlText(writer, prefix, dependentLevels)
        }
        return recordsWritten
    }

    @Override
    Iterator<EntityValue> iterator() { return this }

    @Override
    void remove() {
        throw new IllegalArgumentException("OrientEntityListIterator.remove() not currently supported")
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    void set(EntityValue e) {
        throw new IllegalArgumentException("OrientEntityListIterator.set() not currently supported")
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    void add(EntityValue e) {
        throw new IllegalArgumentException("OrientEntityListIterator.add() not currently supported")
        // TODO implement this
    }

    @Override
    protected void finalize() throws Throwable {
        if (!closed) {
            this.close()
            logger.error("OrientEntityListIterator not closed for entity [${entityDefinition.getEntityName()}], caught in finalize()")
        }
        super.finalize()
    }
}
