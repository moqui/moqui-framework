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

import org.moqui.context.ExecutionContext
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class EntityEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(EntityEcaRule.class)

    protected Node eecaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null

    EntityEcaRule(ExecutionContextFactoryImpl ecfi, Node eecaNode, String location) {
        this.eecaNode = eecaNode
        this.location = location

        // prep condition
        if (eecaNode.condition && eecaNode.condition[0].children()) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, (Node) eecaNode.condition[0].children()[0], location + ".condition")
        }
        // prep actions
        if (eecaNode.actions) {
            actions = new XmlAction(ecfi, (Node) eecaNode.actions[0], location + ".actions")
        }
    }

    String getEntityName() { return eecaNode."@entity" }
    Node getEecaNode() { return eecaNode }

    void runIfMatches(String entityName, Map fieldValues, String operation, boolean before, ExecutionContext ec) {
        // see if we match this event and should run

        // check this first since it is the most common disqualifier
        String attrName = "on-${operation}"
        if (eecaNode.attribute(attrName) != "true") return

        if (entityName != eecaNode."@entity") return
        if (ec.getMessage().hasError() && eecaNode."@run-on-error" != "true") return

        EntityValue curValue = null

        // grab DB values before a delete so they are available after; this modifies fieldValues used by EntityValueBase
        if (before && operation == "delete" && eecaNode."@get-entire-entity" == "true") {
            // fill in any missing (unset) values from the DB
            if (curValue == null) curValue = getDbValue(ec, fieldValues)
            if (curValue != null) {
                // only add fields that fieldValues does not contain
                for (Map.Entry entry in curValue.entrySet())
                    if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue())
            }
        }

        // do this before even if EECA rule runs after to get the original value from the DB and put in the entity's dbValue Map
        EntityValue originalValue = null
        if (before && (operation == "update" || operation == "delete") && eecaNode."@get-original-value" == "true") {
            if (curValue == null) curValue = getDbValue(ec, fieldValues)
            originalValue = curValue
            // also put DB values in the fieldValues EntityValue if it isn't from DB (to have for future reference)
            if (fieldValues instanceof EntityValueBase && !fieldValues.getIsFromDb()) {
                // NOTE: fresh from the DB the valueMap will have clean values and the dbValueMap will be null
                fieldValues.setDbValueMap(((EntityValueBase) originalValue).getValueMap())
            }
        }

        if (before && eecaNode."@run-before" != "true") return
        if (!before && eecaNode."@run-before" == "true") return

        // now if we're running after the entity operation, pull the original value from the
        if (!before && fieldValues instanceof EntityValueBase && fieldValues.getIsFromDb() &&
                (operation == "update" || operation == "delete") && eecaNode."@get-original-value" == "true") {
            originalValue = fieldValues.cloneDbValue(true)
        }

        if ((operation == "update" || operation == "delete") && eecaNode."@get-entire-entity" == "true") {
            // fill in any missing (unset) values from the DB
            if (curValue == null) curValue = getDbValue(ec, fieldValues)
            if (curValue != null) {
                // only add fields that fieldValues does not contain
                for (Map.Entry entry in curValue.entrySet())
                    if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue())
            }
        }

        try {
            ec.context.push()
            ec.context.putAll(fieldValues)
            ec.context.put("entityValue", fieldValues)
            ec.context.put("originalValue", originalValue)
            ec.context.put("eecaOperation", operation)

            // run the condition and if passes run the actions
            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            if (conditionPassed) {
                if (actions) actions.run(ec)
            }
        } finally {
            ec.context.pop()
        }
    }

    EntityValue getDbValue(ExecutionContext ec, Map fieldValues) {
        EntityDefinition ed = ec.getEntity().getEntityDefinition(entityName)
        EntityFind ef = ec.entity.find(entityName)
        for (String pkFieldName in ed.getPkFieldNames()) ef.condition(pkFieldName, fieldValues.get(pkFieldName))
        return ef.one()
    }
}
