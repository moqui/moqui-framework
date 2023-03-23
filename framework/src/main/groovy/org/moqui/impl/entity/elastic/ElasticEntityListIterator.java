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

import groovy.json.JsonOutput;
import org.moqui.BaseArtifactException;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ElasticFacade;
import org.moqui.entity.*;
import org.moqui.impl.context.TransactionCache;
import org.moqui.impl.entity.*;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.util.CollectionUtilities;
import org.moqui.util.LiteStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.*;

public class ElasticEntityListIterator implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(ElasticEntityListIterator.class);

    static final int MAX_FETCH_SIZE = 100;
    static final int CUR_LIST_MAX_SIZE = MAX_FETCH_SIZE * 3;
    private int fetchSize = 50;

    protected final ElasticDatasourceFactory edf;
    protected final EntityFacadeImpl efi;
    protected final Map<String, Object> originalSearchMap;
    private final EntityDefinition entityDefinition;
    protected final FieldInfo[] fieldInfoArray;
    private final int fieldInfoListSize;

    private ArrayList<Map> currentDocList = new ArrayList<>(CUR_LIST_MAX_SIZE);
    private int overallIndex = -1, currentListStartIndex = -1;
    private Integer resultCount = null;
    private final Integer maxResultCount, originalFromInt;
    private String esPitId = null, esKeepAlive;
    private List<Object> esSearchAfter = null;

    private final TransactionCache txCache;
    private final EntityJavaUtil.FindAugmentInfo findAugmentInfo;
    private final int txcListSize;
    private int txcListIndex = -1;
    private final EntityConditionImplBase whereCondition;
    private final CollectionUtilities.MapOrderByComparator orderByComparator;

    private boolean haveMadeValue = false;
    protected boolean closed = false;
    private StackTraceElement[] constructStack = null;
    private final ArrayList<ArtifactExecutionInfo> artifactStack;

    public ElasticEntityListIterator(Map<String, Object> searchMap, EntityDefinition entityDefinition,
            FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray,
            ElasticDatasourceFactory edf, TransactionCache txCache, EntityConditionImplBase whereCondition,
            ArrayList<String> obf) {
        this.edf = edf;
        this.efi = edf.efi;
        this.originalSearchMap = searchMap;
        this.entityDefinition = entityDefinition;
        fieldInfoListSize = fieldInfoArray.length;
        this.fieldInfoArray = fieldInfoArray;

        this.whereCondition = whereCondition;
        this.txCache = txCache;
        if (txCache != null && whereCondition != null) {
            orderByComparator = obf != null && obf.size() > 0 ? new CollectionUtilities.MapOrderByComparator(obf) : null;
            // add all created values (updated and deleted values will be handled by the next() method
            findAugmentInfo = txCache.getFindAugmentInfo(entityDefinition.getFullEntityName(), whereCondition);
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

        // if there is a limit (size) then set that as the maxResultCount
        maxResultCount = (Integer) searchMap.get("size");
        originalFromInt = (Integer) originalSearchMap.get("from");
        esKeepAlive = efi.ecfi.transactionFacade.getTransactionTimeout() + "s";

        // capture the current artifact stack for finalize not closed debugging, has minimal performance impact (still ~0.0038ms per call compared to numbers below)
        artifactStack = efi.ecfi.getEci().artifactExecutionFacade.getStackArray();

        /* uncomment only if needed temporarily: huge performance impact, ~0.036ms per call with, ~0.0037ms without (~10x difference!)
        StackTraceElement[] tempStack = Thread.currentThread().getStackTrace();
        if (tempStack.length > 20) tempStack = java.util.Arrays.copyOfRange(tempStack, 0, 20);
        constructStack = tempStack;
         */
    }

    boolean isFirst() { return overallIndex == 0; }
    boolean isBeforeFirst() { return overallIndex < 0; }
    boolean isLast() { if (resultCount != null) { return overallIndex == resultCount - 1; } else { return false; } }
    boolean isAfterLast() { if (resultCount != null) { return overallIndex >= resultCount; } else { return false; } }

    boolean nextResult() {
        if (resultCount != null && overallIndex >= resultCount) return false;
        overallIndex++;
        if (overallIndex >= (currentListStartIndex + currentDocList.size())) {
            // make sure we aren't at the end
            if (resultCount != null && overallIndex >= resultCount) return false;
            // fetch next results
            fetchNext();
        }

        // logger.warn("nextResult end resultCount " + resultCount + " overallIndex " + overallIndex + " currentListStartIndex " + currentListStartIndex + " currentDocList.size() " + currentDocList.size());
        return hasCurrentValue();
    }
    @SuppressWarnings("unchecked")
    void fetchNext() {
        if (this.closed) throw new IllegalStateException("EntityListIterator is closed, cannot fetch next results");

        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();
        Map<String, Object> searchMap = new LinkedHashMap<>(originalSearchMap);

        // where to start (from)?
        int curFrom = currentListStartIndex + currentDocList.size();
        if (curFrom < 0) curFrom = 0;

        // how many to get (size)?
        int curSize = fetchSize;
        if (resultCount != null && curFrom + curSize > resultCount) {
            // no more to get, return
            if (curFrom >= resultCount) return;
            curSize = resultCount - curFrom;
        }
        if (maxResultCount != null && curFrom + curSize > maxResultCount) {
            // no more to get, return
            if (curFrom >= maxResultCount) return;
            curSize = maxResultCount - curFrom;
        }

        // before doing the search, see if we need a PIT ID: if we can't get all in one fetch
        if (esPitId == null && (maxResultCount == null || maxResultCount > fetchSize)) {
            esPitId = elasticClient.getPitId(edf.getIndexName(entityDefinition), esKeepAlive);
        }

        // add PIT ID (pit.id, pit.keep_alive:1m (use tx length)), search_after
        if (esPitId != null) searchMap.put("pit", CollectionUtilities.toHashMap("id", esPitId, "keep_alive", esKeepAlive));
        if (esSearchAfter != null) {
            // with search_after the from field should always be zero
            searchMap.put("search_after", esSearchAfter);
            searchMap.put("from", 0);
        } else {
            // if origFromInt has a value always add it just before the query, basically a starting offset for the whole query
            searchMap.put("from", originalFromInt != null ? curFrom + originalFromInt : curFrom);
        }
        searchMap.put("size", curSize);

        // if no resultCount yet then track_total_hits (also set to false for better performance on subsequent requests)
        searchMap.put("track_total_hits", resultCount == null);

        // logger.info("fetchNext request: " + JsonOutput.prettyPrint(JsonOutput.toJson(searchMap)));

        // do the query
        Map resultMap = elasticClient.search(esPitId != null ? null : edf.getIndexName(entityDefinition), searchMap);
        Map hitsMap = (Map) resultMap.get("hits");
        List<?> hitsList = (List<?>) hitsMap.get("hits");

        // log response without hits
        /*
        Map resultNoHits = new LinkedHashMap(resultMap);
        Map hitsNoHits = new LinkedHashMap(hitsMap);
        hitsNoHits.remove("hits");
        resultNoHits.put("hits", hitsNoHits);
        logger.info("fetchNext response: " + JsonOutput.prettyPrint(JsonOutput.toJson(resultNoHits)));
        */

        // set resultCount if we have one
        Map totalMap = (Map) hitsMap.get("total");
        if (totalMap != null) {
            Integer hitsTotal = (Integer) totalMap.get("value");
            String relation = (String) totalMap.get("relation");

            // TODO remove this log message, only for testing behavior
            if (!"eq".equals(relation)) logger.warn("Got non eq total relation " + relation + " with value " + hitsTotal + " for entity " + entityDefinition.fullEntityName);

            if (hitsTotal != null && "eq".equals(relation))
                resultCount = originalFromInt != null ? hitsTotal - originalFromInt : hitsTotal;
        }

        // process hits
        if (hitsList != null && hitsList.size() > 0) {
            int hitCount = hitsList.size();
            if (hitCount > fetchSize) logger.warn("In ElasticEntityListIterator got back " + hitCount + " hits with fetchSize " + fetchSize);

            if (hitCount < curSize) {
                // we found the end
                int calcTotal = curFrom + hitCount;
                if (resultCount != calcTotal)
                    logger.warn("In ElasticEntityListIterator reached end of results at " + calcTotal + " but server claimed " + resultCount + " total hits");
                resultCount = calcTotal;
            }

            // do we need to make room in currentDocList?
            if (currentListStartIndex == -1) {
                currentListStartIndex = 0;
            } else {
                int avail = CUR_LIST_MAX_SIZE - currentDocList.size();
                if (avail < hitCount) {
                    // how many can we retain?
                    int retain = hitCount - CUR_LIST_MAX_SIZE;
                    if (retain < 0) retain = 0;
                    int remove = currentDocList.size() - retain;
                    if (retain == 0) {
                        currentDocList.clear();
                    } else {
                        // this is two array copies instead of potential one, but better than iterating manually to move elements or something
                        currentDocList = new ArrayList<>(currentDocList.subList(remove, currentDocList.size()));
                        currentDocList.ensureCapacity(CUR_LIST_MAX_SIZE);
                    }
                    // update start index
                    currentListStartIndex += remove;
                }
            }

            // does the Jackson parser (used in ElasticFacade) use an ArrayList? probably not... Iterator overhead not too bad here anyway
            Iterator<?> hitsIterator = hitsList.iterator();
            while (hitsIterator.hasNext()) {
                Map hit = (Map) hitsIterator.next();
                Map hitSource = (Map) hit.get("_source");
                currentDocList.add(hitSource);

                if (!hitsIterator.hasNext()) {
                    // get search_after from sort on last result
                    List<Object> hitSort = (List<Object>) hit.get("sort");
                    if (hitSort != null) esSearchAfter = hitSort;
                }
            }
        }

        // logger.warn("fetchNext resultCount " + resultCount + " currentListStartIndex " + currentListStartIndex + " currentDocList size " + currentDocList.size());
    }

    boolean previousResult() {
        if (overallIndex < 0) return false;
        overallIndex--;
        if (overallIndex < currentListStartIndex) {
            // make sure we aren't at the beginning
            if (overallIndex < 0) return false;
            // fetch previous results
            fetchPrevious();
        }

        return hasCurrentValue();
    }
    void fetchPrevious() {
        if (this.closed) throw new IllegalStateException("EntityListIterator is closed, cannot fetch previous results");

        // TODO
        throw new BaseArtifactException("ElasticEntityListIterator.fetchPrevious() TODO");
    }

    boolean hasCurrentValue() {
        // if the numbers are such that we have a result (after a fetchPrevious() if needed) then return true
        return overallIndex >= currentListStartIndex && overallIndex < (currentListStartIndex + currentDocList.size());
    }
    void resetCurrentList() {
        // TODO: given multi-fetch space in the current list this could be optimized to avoid future fetch if currentListStartIndex < CUR_LIST_MAX_SIZE
        if (currentListStartIndex > 0) {
            currentListStartIndex = -1;
            currentDocList.clear();
            esSearchAfter = null;
        }
    }

    @Override public void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity " + this.entityDefinition.getFullEntityName() + " is already closed, not closing again");
        } else {
            if (esPitId != null) {
                ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();
                elasticClient.deletePit(esPitId);
                esPitId = null;
            }

            this.closed = true;
        }

    }

    @Override public void afterLast() {
        throw new BaseArtifactException("ElasticEntityListIterator.afterLast() not currently supported");
        // rs.afterLast();
        // txcListIndex = txcListSize;
    }
    @Override public void beforeFirst() {
        txcListIndex = -1;
        overallIndex = -1;
        resetCurrentList();
    }

    @Override public boolean last() {
        throw new BaseArtifactException("ElasticEntityListIterator.last() not currently supported");
        /*
        if (txcListSize > 0) {
            try { rs.afterLast(); }
            catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to last", e); }
            txcListIndex = txcListSize - 1;
            return true;
        } else {
            try { return rs.last(); }
            catch (SQLException e) { throw new EntityException("Error moving EntityListIterator to last", e); }
        }
         */
    }
    @Override public boolean first() {
        txcListIndex = -1;
        overallIndex = 0;
        if (currentListStartIndex > 0) {
            resetCurrentList();
            if (currentListStartIndex < 0) fetchNext();
        }
        return hasCurrentValue();
    }

    @Override public EntityValue currentEntityValue() { return currentEntityValueBase(); }
    public EntityValueBase currentEntityValueBase() {
        if (txcListIndex >= 0) return findAugmentInfo.valueList.get(txcListIndex);
        if (overallIndex == -1) return null;

        int curIndex = overallIndex - currentListStartIndex;
        Map docMap = currentDocList.get(curIndex);

        EntityValueImpl newEntityValue = new EntityValueImpl(entityDefinition, efi);
        LiteStringMap<Object> valueMap = newEntityValue.getValueMap();
        for (int i = 0; i < fieldInfoListSize; i++) {
            FieldInfo fi = fieldInfoArray[i];
            if (fi == null) break;
            Object fValue = ElasticDatasourceFactory.convertFieldValue(fi, docMap.get(fi.name));
            valueMap.putByIString(fi.name, fValue, fi.index);
        }
        newEntityValue.setSyncedWithDb();

        // if txCache in place always put in cache for future reference (onePut handles any stale from DB issues too)
        // NOTE: because of this don't use txCache for very large result sets
        if (txCache != null) txCache.onePut(newEntityValue, false);
        haveMadeValue = true;

        return newEntityValue;
    }

    @Override public int currentIndex() {
        // NOTE: add one because this is based on the JDBC ResultSet object which is 1 based
        return overallIndex + txcListIndex + 1;
    }
    @Override public boolean absolute(final int rowNum) {
        // TODO: somehow implement this for txcList? would need to know how many rows after last we tried to go
        if (txcListSize > 0) throw new EntityException("Cannot go to absolute row number when transaction cache is in place and there are augmenting creates; disable the tx cache before this operation");

        // subtract 1 to convet to zero based index
        int internalIndex = rowNum - 1;
        if (internalIndex >= currentListStartIndex && internalIndex < (currentListStartIndex + currentDocList.size())) {
            overallIndex = internalIndex;
        } else {
            txcListIndex = -1;
            overallIndex = internalIndex;
            resetCurrentList();
            fetchNext();
        }

        return hasCurrentValue();
    }
    @Override public boolean relative(final int rows) {
        throw new BaseArtifactException("ElasticEntityListIterator.relative() not currently supported");
        // TODO: somehow implement this for txcList? would need to know how many rows after last we tried to go
        // if (txcListSize > 0) throw new EntityException("Cannot go to relative row number when transaction cache is in place and there are augmenting creates; disable the tx cache before this operation");
        // return rs.relative(rows);
    }

    @Override public boolean hasNext() {
        if (isLast() || isAfterLast()) {
            return txcListIndex < (txcListSize - 1);
        } else {
            // if not in the first or beforeFirst positions and haven't made any values yet, the result set is empty
            return !(!haveMadeValue && !isBeforeFirst() && !isFirst());
        }
    }
    @Override public boolean hasPrevious() {
        if (isFirst() || isBeforeFirst()) {
            return false;
        } else {
            // if not in the last or afterLast positions and we haven't made any values yet, the result set is empty
            return !(!haveMadeValue && !isAfterLast() && !isLast());
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
        if (nextResult()) {
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
    }
    @Override public int nextIndex() { return currentIndex() + 1; }

    @Override public EntityValue previous() {
        // first try the txcList if we are in it
        if (txcListIndex >= 0) {
            txcListIndex--;
            if (txcListIndex >= 0) return currentEntityValue();
        }
        if (previousResult()) {
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
    }
    @Override public int previousIndex() { return currentIndex() - 1; }

    @Override public void setFetchSize(int rows) {
        if (rows > MAX_FETCH_SIZE) rows = MAX_FETCH_SIZE;
        this.fetchSize = rows;
    }

    @Override public EntityList getCompleteList(boolean closeAfter) {
        try {
            // move back to before first if we need to
            if (haveMadeValue && !isBeforeFirst()) beforeFirst();

            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = next()) != null) list.add(value);

            if (findAugmentInfo != null) {
                // all created, updated, and deleted values will be handled by the next() method
                // update the order if we know the order by field list
                if (orderByComparator != null) list.sort(orderByComparator);
            }

            return list;
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

        // move back to before first if we need to
        if (haveMadeValue && !isBeforeFirst()) beforeFirst();
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlText(writer, prefix, dependentLevels);

        return recordsWritten;
    }
    @Override
    public int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0;
        // move back to before first if we need to
        if (haveMadeValue && !isBeforeFirst()) beforeFirst();
        EntityValue value;
        while ((value = this.next()) != null)
            recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName);

        return recordsWritten;
    }

    @Override
    public void remove() {
        throw new BaseArtifactException("ElasticEntityListIterator.remove() not currently supported");
        // TODO: call EECAs
        // efi.getEntityCache().clearCacheForValue((EntityValueBase) currentEntityValue(), false);
        // rs.deleteRow();
    }

    @Override
    public void set(EntityValue e) {
        throw new BaseArtifactException("ElasticEntityListIterator.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override
    public void add(EntityValue e) {
        throw new BaseArtifactException("ElasticEntityListIterator.add() not currently supported");
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
