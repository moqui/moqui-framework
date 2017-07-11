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
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(EntityEcaRule.class)

    protected ExecutionContextFactoryImpl ecfi
    protected MNode eecaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null

    EntityEcaRule(ExecutionContextFactoryImpl ecfi, MNode eecaNode, String location) {
        this.ecfi = ecfi
        this.eecaNode = eecaNode
        this.location = location

        // prep condition
        if (eecaNode.hasChild("condition") && eecaNode.first("condition").children) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, eecaNode.first("condition").children.get(0), location + ".condition")
        }
        // prep actions
        if (eecaNode.hasChild("actions")) {
            actions = new XmlAction(ecfi, eecaNode.first("actions"), null) // was location + ".actions" but not unique!
        }
    }

    String getEntityName() { return eecaNode.attribute("entity") }
    MNode getEecaNode() { return eecaNode }

    void runIfMatches(String entityName, Map fieldValues, String operation, boolean before, ExecutionContextImpl ec) {
        // see if we match this event and should run

        // check this first since it is the most common disqualifier
        String attrName = "on-".concat(operation)
        if (!"true".equals(eecaNode.attribute(attrName))) return

        if (!entityName.equals(eecaNode.attribute("entity"))) return
        if (ec.messageFacade.hasError() && !"true".equals(eecaNode.attribute("run-on-error"))) return

        EntityValue curValue = null

        boolean isDelete = "delete".equals(operation)
        boolean isUpdate = !isDelete && "update".equals(operation)

        // grab DB values before a delete so they are available after; this modifies fieldValues used by EntityValueBase
        if (before && isDelete && eecaNode.attribute("get-entire-entity") == "true") {
            // fill in any missing (unset) values from the DB
            if (curValue == null) curValue = getDbValue(fieldValues)
            if (curValue != null) {
                // only add fields that fieldValues does not contain
                for (Map.Entry entry in curValue.entrySet())
                    if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue())
            }
        }

        // do this before even if EECA rule runs after to get the original value from the DB and put in the entity's dbValue Map
        EntityValue originalValue = null
        if (before && (isUpdate || isDelete) && "true".equals(eecaNode.attribute("get-original-value"))) {
            if (curValue == null) curValue = getDbValue(fieldValues)
            if (curValue != null) {
                originalValue = curValue
                // also put DB values in the fieldValues EntityValue if it isn't from DB (to have for future reference)
                if (fieldValues instanceof EntityValueBase && !((EntityValueBase) fieldValues).getIsFromDb()) {
                    // NOTE: fresh from the DB the valueMap will have clean values and the dbValueMap will be null
                    ((EntityValueBase) fieldValues).setDbValueMap(((EntityValueBase) originalValue).getValueMap())
                }
            }
        }

        if (before && !"true".equals(eecaNode.attribute("run-before"))) return
        if (!before && "true".equals(eecaNode.attribute("run-before"))) return

        // now if we're running after the entity operation, pull the original value from the
        if (!before && fieldValues instanceof EntityValueBase && ((EntityValueBase) fieldValues).getIsFromDb() &&
                (isUpdate || isDelete) && eecaNode.attribute("get-original-value") == "true") {
            originalValue = ((EntityValueBase) fieldValues).cloneDbValue(true)
        }

        if ((isUpdate || isDelete) && eecaNode.attribute("get-entire-entity") == "true") {
            // fill in any missing (unset) values from the DB
            if (curValue == null) curValue = getDbValue(fieldValues)
            if (curValue != null) {
                // only add fields that fieldValues does not contain
                for (Map.Entry entry in curValue.entrySet())
                    if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue())
            }
        }

        try {
            ec.contextStack.push()
            ec.contextStack.putAll(fieldValues)
            ec.contextStack.put("entityValue", fieldValues)
            ec.contextStack.put("originalValue", originalValue)
            ec.contextStack.put("eecaOperation", operation)

            // run the condition and if passes run the actions
            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            if (conditionPassed) {
                if (actions) actions.run(ec)
            }
        } finally {
            ec.contextStack.pop()
        }
    }

    EntityValue getDbValue(Map fieldValues) {
        EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
        EntityFind ef = ecfi.entity.find(entityName)
        for (String pkFieldName in ed.getPkFieldNames()) ef.condition(pkFieldName, fieldValues.get(pkFieldName))
        return ef.one()
    }
}
