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

import org.moqui.entity.EntityValue;
import org.moqui.impl.StupidJavaUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;

public class AggregationUtil {
    protected final static Logger logger = LoggerFactory.getLogger(AggregationUtil.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    public enum AggregateFunction { MIN, MAX, SUM, AVG, COUNT }
    private static final BigDecimal BIG_DECIMAL_TWO = new BigDecimal(2);

    public static class AggregateField {
        final String fieldName;
        final AggregateFunction function;
        final boolean subList, showTotal;
        public AggregateField(String fn, AggregateFunction func, boolean sl, boolean st) {
            fieldName = fn; function = func; subList = sl; showTotal = st;
        }
    }

    @SuppressWarnings("unchecked")
    public static ArrayList<Map<String, Object>> aggregateList(Object listObj, AggregateField[] aggregateFields, String[] groupFields) {
        long startTime = System.currentTimeMillis();
        ArrayList<Map<String, Object>> resultList = new ArrayList<>();
        Map<Map<String, Object>, Map<String, Object>> groupRows = new HashMap<>();
        int originalCount = 0;
        if (listObj instanceof List) {
            List<Map<String, Object>> listList = (List<Map<String, Object>>) listObj;
            if (listObj instanceof RandomAccess) {
                int listSize = listList.size();
                for (int i = 0; i < listSize; i++) {
                    Map<String, Object> origMap = listList.get(i);
                    processAggregateOriginal(origMap, aggregateFields, groupFields, resultList, groupRows);
                    originalCount++;
                }
            } else {
                for (Map<String, Object> origMap : listList) {
                    processAggregateOriginal(origMap, aggregateFields, groupFields, resultList, groupRows);
                    originalCount++;
                }
            }
        } else if (listObj instanceof Iterator) {
            Iterator<Map<String, Object>> listIter = (Iterator<Map<String, Object>>) listObj;
            while (listIter.hasNext()) {
                Map<String, Object> origMap = listIter.next();
                processAggregateOriginal(origMap, aggregateFields, groupFields, resultList, groupRows);
                originalCount++;
            }
        }

        if (logger.isInfoEnabled()) logger.info("Aggregated list from " + originalCount + " items to " + resultList.size() + " items in " + (System.currentTimeMillis() - startTime) + "ms");
        for (Map<String, Object> result : resultList) logger.warn("Aggregate Result: " + result.toString());

        return resultList;
    }

    @SuppressWarnings("unchecked")
    private static void processAggregateOriginal(Map<String, Object> origMap, AggregateField[] aggregateFields, String[] groupFields,
                ArrayList<Map<String, Object>> resultList, Map<Map<String, Object>, Map<String, Object>> groupRows) {
        Map<String, Object> groupByMap = new HashMap<>();
        for (int i = 0; i < groupFields.length; i++) {
            String groupBy = groupFields[i];
            groupByMap.put(groupBy, origMap.get(groupBy));
        }

        EntityValue origEv = null;
        if (origMap instanceof EntityValue) origEv = (EntityValue) origMap;

        Map<String, Object> resultMap = groupRows.get(groupByMap);
        Map<String, Object> subListMap = null;
        if (resultMap == null) {
            resultMap = new HashMap<>();
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;
                if (origEv != null && !origEv.isField(fieldName)) continue;

                if (aggField.subList) {
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, origMap.get(fieldName));
                } else if (aggField.function == AggregateFunction.COUNT) {
                    resultMap.put(fieldName, 1);
                } else {
                    resultMap.put(fieldName, origMap.get(fieldName));
                }
                // TODO: handle showTotal
            }
            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = new ArrayList<>();
                subList.add(subListMap);
                resultMap.put("aggregateSubList", subList);
            }
            resultList.add(resultMap);
            groupRows.put(groupByMap, resultMap);
        } else {
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;
                if (origEv != null && !origEv.isField(fieldName)) continue;

                if (aggField.subList) {
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, origMap.get(fieldName));
                } else if (aggField.function != null) {
                    switch (aggField.function) {
                        case MIN:
                        case MAX:
                            Comparable existingComp = (Comparable) resultMap.get(fieldName);
                            Comparable newComp = (Comparable) origMap.get(fieldName);
                            if (existingComp == null) {
                                if (newComp != null) resultMap.put(fieldName, newComp);
                            } else {
                                int compResult = existingComp.compareTo(newComp);
                                if ((aggField.function == AggregateFunction.MIN && compResult < 0) ||
                                        (aggField.function == AggregateFunction.MAX && compResult > 0))
                                    resultMap.put(fieldName, newComp);
                            }
                            break;
                        case SUM:
                            Number sumNum = StupidJavaUtilities.addNumbers((Number) resultMap.get(fieldName), (Number) origMap.get(fieldName));
                            if (sumNum != null) resultMap.put(fieldName, sumNum);
                            break;
                        case AVG:
                            Number newNum = (Number) origMap.get(fieldName);
                            if (newNum != null) {
                                Number existingNum = (Number) resultMap.get(fieldName);
                                if (existingNum == null) {
                                    resultMap.put(fieldName, newNum);
                                } else {
                                    String fieldCountName = fieldName.concat("Count");
                                    BigDecimal count = (BigDecimal) resultMap.get(fieldCountName);
                                    BigDecimal bd1 = (existingNum instanceof BigDecimal) ? (BigDecimal) existingNum : new BigDecimal(existingNum.toString());
                                    BigDecimal bd2 = (newNum instanceof BigDecimal) ? (BigDecimal) newNum : new BigDecimal(newNum.toString());
                                    if (count == null) {
                                        resultMap.put(fieldName, bd1.add(bd2).divide(BIG_DECIMAL_TWO, BigDecimal.ROUND_HALF_EVEN));
                                        resultMap.put(fieldCountName, BIG_DECIMAL_TWO);
                                    } else {
                                        BigDecimal avgTotal = bd1.multiply(count).add(bd2);
                                        BigDecimal countPlusOne = count.add(BigDecimal.ONE);
                                        resultMap.put(fieldName, avgTotal.divide(countPlusOne, BigDecimal.ROUND_HALF_EVEN));
                                        resultMap.put(fieldCountName, countPlusOne);
                                    }
                                }
                            }
                            break;
                        case COUNT:
                            Integer existingCount = (Integer) resultMap.get(fieldName);
                            resultMap.put(fieldName, existingCount + 1);
                            break;
                    }
                }
                // TODO: handle showTotal
            }
            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = (ArrayList<Map<String, Object>>) resultMap.get("aggregateSubList");
                subList.add(subListMap);
            }
        }
    }
}
