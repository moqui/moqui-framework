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

import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.moqui.BaseArtifactException;
import org.moqui.entity.EntityValue;
import org.moqui.impl.actions.XmlAction;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.ContextStack;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class AggregationUtil {
    protected final static Logger logger = LoggerFactory.getLogger(AggregationUtil.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    public enum AggregateFunction { MIN, MAX, SUM, AVG, COUNT, FIRST, LAST }
    private static final BigDecimal BIG_DECIMAL_TWO = new BigDecimal(2);

    public static class AggregateField {
        public final String fieldName;
        public final AggregateFunction function;
        public final AggregateFunction showTotal;
        public final boolean groupBy, subList;
        public final Class fromExpr;
        public AggregateField(String fn, AggregateFunction func, boolean gb, boolean sl, String st, Class from) {
            if ("false".equals(st)) st = null;
            fieldName = fn; function = func; groupBy = gb; subList = sl; fromExpr = from;
            showTotal = st != null ? AggregateFunction.valueOf(st.toUpperCase()) : null;
        }
    }

    private String listName, listEntryName;
    private AggregateField[] aggregateFields;
    private boolean hasFromExpr = false;
    private boolean hasSubListTotals = false;
    private String[] groupFields;
    private XmlAction rowActions;

    public AggregationUtil(String listName, String listEntryName, AggregateField[] aggregateFields, String[] groupFields, XmlAction rowActions) {
        this.listName = listName;
        this.listEntryName = listEntryName;
        if (this.listEntryName != null && this.listEntryName.isEmpty()) this.listEntryName = null;
        this.aggregateFields = aggregateFields;
        this.groupFields = groupFields;
        this.rowActions = rowActions;
        for (int i = 0; i < aggregateFields.length; i++) {
            AggregateField aggField = aggregateFields[i];
            if (aggField.fromExpr != null) hasFromExpr = true;
            if (aggField.subList && aggField.showTotal != null) hasSubListTotals = true;
        }
    }


    @SuppressWarnings("unchecked")
    public ArrayList<Map<String, Object>> aggregateList(Object listObj, Set<String> includeFields, boolean makeSubList, ExecutionContextImpl eci) {
        if (groupFields == null || groupFields.length == 0) makeSubList = false;
        ArrayList<Map<String, Object>> resultList = new ArrayList<>();
        if (listObj == null) return resultList;

        // for (Object result : (Iterable) listObj) logger.warn("Aggregate Input: " + result.toString());

        long startTime = System.currentTimeMillis();
        Map<Map<String, Object>, Map<String, Object>> groupRows = new HashMap<>();
        Map<String, Object> totalsMap = new HashMap<>();
        int originalCount = 0;
        if (listObj instanceof List) {
            List listList = (List) listObj;
            int listSize = listList.size();
            if (listObj instanceof RandomAccess) {
                for (int i = 0; i < listSize; i++) {
                    Object curObject = listList.get(i);
                    processAggregateOriginal(curObject, resultList, includeFields, groupRows, totalsMap, i, (i < (listSize - 1)), makeSubList, eci);
                    originalCount++;
                }
            } else {
                int i = 0;
                for (Object curObject : listList) {
                    processAggregateOriginal(curObject, resultList, includeFields, groupRows, totalsMap, i, (i < (listSize - 1)), makeSubList, eci);
                    i++;
                    originalCount++;
                }
            }
        } else if (listObj instanceof Iterator) {
            Iterator listIter = (Iterator) listObj;
            int i = 0;
            while (listIter.hasNext()) {
                Object curObject = listIter.next();
                processAggregateOriginal(curObject, resultList, includeFields, groupRows, totalsMap, i, listIter.hasNext(), makeSubList, eci);
                i++;
                originalCount++;
            }
        } else if (listObj.getClass().isArray()) {
            Object[] listArray = (Object[]) listObj;
            int listSize = listArray.length;
            for (int i = 0; i < listSize; i++) {
                Object curObject = listArray[i];
                processAggregateOriginal(curObject, resultList, includeFields, groupRows, totalsMap, i, (i < (listSize - 1)), makeSubList, eci);
                originalCount++;
            }
        } else {
            throw new BaseArtifactException("form-list list " + listName + " is a type we don't know how to iterate: " + listObj.getClass().getName());
        }

        if (hasSubListTotals) {
            int resultSize = resultList.size();
            for (int i = 0; i < resultSize; i++) {
                Map<String, Object> resultMap = resultList.get(i);
                ArrayList aggregateSubList = (ArrayList) resultMap.get("aggregateSubList");
                if (aggregateSubList != null) {
                    Map aggregateSubListTotals = (Map) resultMap.get("aggregateSubListTotals");
                    if (aggregateSubListTotals != null) aggregateSubList.add(aggregateSubListTotals);
                }
            }
        }
        if (totalsMap.size() > 0) resultList.add(new HashMap<>(totalsMap));

        if (logger.isTraceEnabled()) logger.trace("Processed list " + listName + ", from " + originalCount + " items to " + resultList.size() + " items, in " + (System.currentTimeMillis() - startTime) + "ms");
        // for (Map<String, Object> result : resultList) logger.warn("Aggregate Result: " + result.toString());

        return resultList;
    }

    @SuppressWarnings("unchecked")
    private void processAggregateOriginal(Object curObject, ArrayList<Map<String, Object>> resultList, Set<String> includeFields,
                                          Map<Map<String, Object>, Map<String, Object>> groupRows, Map<String, Object> totalsMap,
                                          int index, boolean hasNext, boolean makeSubList, ExecutionContextImpl eci) {
        Map curMap = null;
        if (curObject instanceof EntityValue) {
            curMap = ((EntityValue) curObject).getMap();
        } else if (curObject instanceof Map) {
            curMap = (Map) curObject;
        }
        boolean curIsMap = curMap != null;

        ContextStack context = eci.contextStack;
        Map<String, Object> contextTopMap;
        if (curMap != null) { contextTopMap = new HashMap<>(curMap); } else { contextTopMap = new HashMap<>(); }
        context.push(contextTopMap);

        if (listEntryName != null) {
            context.put(listEntryName, curObject);
            context.put(listEntryName + "_index", index);
            context.put(listEntryName + "_has_next", hasNext);
        } else {
            context.put(listName + "_index", index);
            context.put(listName + "_has_next", hasNext);
            context.put(listName + "_entry", curObject);
        }

        // if there are row actions run them
        if (rowActions != null || hasFromExpr) {
            if (rowActions != null) rowActions.run(eci);

            // if any fields have a fromExpr get the value from that
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                if (aggField.fromExpr != null) {
                    Script script = InvokerHelper.createScript(aggField.fromExpr, eci.contextBindingInternal);
                    Object newValue = script.run();
                    context.put(aggField.fieldName, newValue);
                }
            }
        }

        Map<String, Object> resultMap = null;
        Map<String, Object> groupByMap = null;
        if (makeSubList) {
            groupByMap = new HashMap<>();
            for (int i = 0; i < groupFields.length; i++) {
                String groupBy = groupFields[i];
                if (!includeFields.contains(groupBy)) continue;
                groupByMap.put(groupBy, getField(groupBy, context, curObject, curIsMap));
            }
            resultMap = groupRows.get(groupByMap);
        }

        if (resultMap == null) {
            resultMap = contextTopMap;
            Map<String, Object> subListMap = null;
            Map<String, Object> subListTotalsMap = null;
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;
                Object fieldValue = getField(fieldName, context, curObject, curIsMap);
                // don't want to put null values, a waste of time/space; if count aggregate continue so it isn't counted
                if (fieldValue == null) continue;

                // handle subList
                if (makeSubList && aggField.subList) {
                    // NOTE: may have an issue here not using contextTopMap as starting point for sub-list entry, ie row-actions values lost if not referenced in a field name/from
                    // NOTE2: if we start with contextTopMap should clone and perhaps remove aggregateFields that are not sub-list
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, fieldValue);
                    resultMap.remove(fieldName);
                } else if (aggField.function == AggregateFunction.COUNT) {
                    resultMap.put(fieldName, 1);
                } else {
                    resultMap.put(fieldName, fieldValue);
                }
                // handle showTotal
                if (aggField.showTotal != null) {
                    if (aggField.subList) {
                        if (subListTotalsMap == null) subListTotalsMap = new HashMap<>();
                        doFunction(aggField.showTotal, subListTotalsMap, fieldName, fieldValue);
                    } else {
                        doFunction(aggField.showTotal, totalsMap, fieldName, fieldValue);
                    }
                }
            }

            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = new ArrayList<>();
                subList.add(subListMap);
                resultMap.put("aggregateSubList", subList);
            }
            if (subListTotalsMap != null) resultMap.put("aggregateSubListTotals", subListTotalsMap);

            resultList.add(resultMap);
            if (makeSubList) groupRows.put(groupByMap, resultMap);
        } else {
            // NOTE: if makeSubList == false this will never run
            Map<String, Object> subListMap = null;
            Map<String, Object> subListTotalsMap = (Map<String, Object>) resultMap.get("aggregateSubListTotals");
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;
                Object fieldValue = getField(fieldName, context, curObject, curIsMap);
                // don't want to put null values, a waste of time/space; if count aggregate continue so it isn't counted
                if (fieldValue == null) continue;

                if (aggField.subList) {
                    // NOTE: may have an issue here not using contextTopMap as starting point for sub-list entry, ie row-actions values lost if not referenced in a field name/from
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, fieldValue);
                } else if (aggField.function != null) {
                    doFunction(aggField.function, resultMap, fieldName, fieldValue);
                }
                // handle showTotal
                if (aggField.showTotal != null) {
                    if (aggField.subList) {
                        if (subListTotalsMap == null) {
                            subListTotalsMap = new HashMap<>();
                            resultMap.put("aggregateSubListTotals", subListTotalsMap);
                        }
                        doFunction(aggField.showTotal, subListTotalsMap, fieldName, fieldValue);
                    } else {
                        doFunction(aggField.showTotal, totalsMap, fieldName, fieldValue);
                    }
                }
            }
            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = (ArrayList<Map<String, Object>>) resultMap.get("aggregateSubList");
                if (subList != null) subList.add(subListMap);
            }
        }

        // all done, pop the row context to clean up
        context.pop();
    }

    private Object getField(String fieldName, ContextStack context, Object curObject, boolean curIsMap) {
        Object value = context.getByString(fieldName);
        if (curObject != null && !curIsMap && ObjectUtilities.isEmpty(value)) {
            // try Groovy getAt for property access
            try {
                value = DefaultGroovyMethods.getAt(curObject, fieldName);
            } catch (MissingPropertyException e) {
                // ignore exception, we know this may not be a real property of the object
                if (isTraceEnabled) logger.trace("Field " + fieldName + " is not a property of list-entry " + listEntryName + " in list " + listName + ": " + e.toString());
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void doFunction(AggregateFunction function, Map<String, Object> resultMap, String fieldName, Object fieldValue) {
        switch (function) {
            case MIN:
            case MAX:
                Comparable existingComp = (Comparable) resultMap.get(fieldName);
                Comparable newComp = (Comparable) fieldValue;
                if (existingComp == null) {
                    if (newComp != null) resultMap.put(fieldName, newComp);
                } else {
                    int compResult = existingComp.compareTo(newComp);
                    if ((function == AggregateFunction.MIN && compResult > 0) || (function == AggregateFunction.MAX && compResult < 0))
                        resultMap.put(fieldName, newComp);
                }
                break;
            case SUM:
                Number sumNum = ObjectUtilities.addNumbers((Number) resultMap.get(fieldName), (Number) fieldValue);
                if (sumNum != null) resultMap.put(fieldName, sumNum);
                break;
            case AVG:
                Number newNum = (Number) fieldValue;
                if (newNum != null) {
                    BigDecimal newNumBd = (newNum instanceof BigDecimal) ? (BigDecimal) newNum : new BigDecimal(newNum.toString());
                    String fieldCountName = fieldName.concat("Count");
                    String fieldTotalName = fieldName.concat("Total");
                    Number existingNum = (Number) resultMap.get(fieldName);
                    if (existingNum == null) {
                        resultMap.put(fieldName, newNumBd);
                        resultMap.put(fieldCountName, BigDecimal.ONE);
                        resultMap.put(fieldTotalName, newNumBd);
                    } else {
                        BigDecimal count = (BigDecimal) resultMap.get(fieldCountName);
                        BigDecimal total = (BigDecimal) resultMap.get(fieldTotalName);
                        BigDecimal avgTotal = total.add(newNumBd);
                        BigDecimal countPlusOne = count.add(BigDecimal.ONE);
                        resultMap.put(fieldName, avgTotal.divide(countPlusOne, RoundingMode.HALF_EVEN));
                        resultMap.put(fieldCountName, countPlusOne);
                        resultMap.put(fieldTotalName, avgTotal);
                    }
                }
                break;
            case COUNT:
                Integer existingCount = (Integer) resultMap.get(fieldName);
                if (existingCount == null) existingCount = 0;
                resultMap.put(fieldName, existingCount + 1);
                break;
            case FIRST:
                if (!resultMap.containsKey(fieldName)) resultMap.put(fieldName, fieldValue);
                break;
            case LAST:
                resultMap.put(fieldName, fieldValue);
                break;
        }
    }
}
