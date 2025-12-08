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
package org.moqui.impl.screen

import groovy.transform.CompileStatic
import org.moqui.BaseArtifactException
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode

/**
 * FormValidator - Handles form field validation logic extracted from ScreenForm.FormInstance.
 *
 * This class generates client-side validation rules (CSS classes, JS expressions, regex patterns)
 * from service parameters and entity field definitions.
 *
 * Part of ARCH-002: Extract FormRenderer from ScreenForm
 */
@CompileStatic
class FormValidator {

    protected final ExecutionContextFactoryImpl ecfi
    protected final String formLocation

    // Validation message constants
    static final String MSG_REQUIRED = "Please enter a value"
    static final String MSG_NUMBER = "Please enter a valid number"
    static final String MSG_NUMBER_INT = "Please enter a valid whole number"
    static final String MSG_DIGITS = "Please enter only numbers (digits)"
    static final String MSG_LETTERS = "Please enter only letters"
    static final String MSG_EMAIL = "Please enter a valid email address"
    static final String MSG_URL = "Please enter a valid URL"

    // JavaScript validation expressions
    static final String VALIDATE_NUMBER = '!value||$root.moqui.isStringNumber(value)'
    static final String VALIDATE_NUMBER_INT = '!value||$root.moqui.isStringInteger(value)'

    FormValidator(ExecutionContextFactoryImpl ecfi, String formLocation) {
        this.ecfi = ecfi
        this.formLocation = formLocation
    }

    /**
     * Get the validation source node (service parameter or entity field) for a sub-field.
     * @param subFieldNode The sub-field node (default-field, conditional-field, etc.)
     * @return The validation source MNode or null if not specified
     */
    MNode getFieldValidateNode(MNode subFieldNode) {
        MNode fieldNode = subFieldNode.getParent()
        String fieldName = fieldNode.attribute("name")
        String validateService = subFieldNode.attribute('validate-service')
        String validateEntity = subFieldNode.attribute('validate-entity')

        if (validateService) {
            ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(validateService)
            if (sd == null) throw new BaseArtifactException("Invalid validate-service name [${validateService}] in field [${fieldName}] of form [${formLocation}]")
            MNode parameterNode = sd.getInParameter((String) subFieldNode.attribute('validate-parameter') ?: fieldName)
            return parameterNode
        } else if (validateEntity) {
            EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(validateEntity)
            if (ed == null) throw new BaseArtifactException("Invalid validate-entity name [${validateEntity}] in field [${fieldName}] of form [${formLocation}]")
            MNode efNode = ed.getFieldNode((String) subFieldNode.attribute('validate-field') ?: fieldName)
            return efNode
        }
        return null
    }

    /**
     * Get CSS validation classes for a field based on its validation rules.
     * @param subFieldNode The sub-field node
     * @return Space-separated CSS class string (e.g., "required number email")
     */
    String getFieldValidationClasses(MNode subFieldNode) {
        MNode validateNode = getFieldValidateNode(subFieldNode)
        if (validateNode == null) return ""

        Set<String> vcs = new HashSet()
        if (validateNode.name == "parameter") {
            MNode parameterNode = validateNode
            if (parameterNode.attribute('required') == "true") vcs.add("required")
            if (parameterNode.hasChild("number-integer")) vcs.add("number")
            if (parameterNode.hasChild("number-decimal")) vcs.add("number")
            if (parameterNode.hasChild("text-email")) vcs.add("email")
            if (parameterNode.hasChild("text-url")) vcs.add("url")
            if (parameterNode.hasChild("text-digits")) vcs.add("digits")
            if (parameterNode.hasChild("credit-card")) vcs.add("creditcard")

            String type = parameterNode.attribute('type')
            if (type != null && (type.endsWith("BigDecimal") || type.endsWith("BigInteger") || type.endsWith("Long") ||
                    type.endsWith("Integer") || type.endsWith("Double") || type.endsWith("Float") ||
                    type.endsWith("Number"))) vcs.add("number")
        } else if (validateNode.name == "field") {
            MNode fieldNode = validateNode
            String type = fieldNode.attribute('type')
            if (type != null && (type.startsWith("number-") || type.startsWith("currency-"))) vcs.add("number")
        }

        StringBuilder sb = new StringBuilder()
        for (String vc in vcs) { if (sb) sb.append(" "); sb.append(vc); }
        return sb.toString()
    }

    /**
     * Get regex validation info for a field if it has a matches constraint.
     * @param subFieldNode The sub-field node
     * @return Map with 'regexp' and 'message' keys, or null if no matches constraint
     */
    Map getFieldValidationRegexpInfo(MNode subFieldNode) {
        MNode validateNode = getFieldValidateNode(subFieldNode)
        if (validateNode?.hasChild("matches")) {
            MNode matchesNode = validateNode.first("matches")
            return [regexp:matchesNode.attribute('regexp'), message:matchesNode.attribute('message')]
        }
        return null
    }

    /**
     * Get JavaScript validation rules for a field.
     * @param subFieldNode The sub-field node
     * @return List of maps with 'expr' (JS expression) and 'message' keys, or null if no rules
     */
    ArrayList<Map<String, String>> getFieldValidationJsRules(MNode subFieldNode) {
        MNode validateNode = getFieldValidateNode(subFieldNode)
        if (validateNode == null) return null

        ExecutionContextImpl eci = ecfi.getEci()
        ArrayList<Map<String, String>> ruleList = new ArrayList<>(5)

        if (validateNode.name == "parameter") {
            if ("true".equals(validateNode.attribute('required')))
                ruleList.add([expr:"!!value", message:eci.l10nFacade.localize(MSG_REQUIRED)])

            boolean foundNumber = false
            ArrayList<MNode> children = validateNode.getChildren()
            int childrenSize = children.size()
            for (int i = 0; i < childrenSize; i++) {
                MNode child = (MNode) children.get(i)
                if ("number-integer".equals(child.getName())) {
                    if (!foundNumber) {
                        ruleList.add([expr:VALIDATE_NUMBER_INT, message:eci.l10nFacade.localize(MSG_NUMBER_INT)])
                        foundNumber = true
                    }
                } else if ("number-decimal".equals(child.getName())) {
                    if (!foundNumber) {
                        ruleList.add([expr:VALIDATE_NUMBER, message:eci.l10nFacade.localize(MSG_NUMBER)])
                        foundNumber = true
                    }
                } else if ("text-digits".equals(child.getName())) {
                    if (!foundNumber) {
                        ruleList.add([expr:'!value || /^\\d*$/.test(value)', message:eci.l10nFacade.localize(MSG_DIGITS)])
                        foundNumber = true
                    }
                } else if ("text-letters".equals(child.getName())) {
                    ruleList.add([expr:'!value || /^[a-zA-Z]*$/.test(value)', message:eci.l10nFacade.localize(MSG_LETTERS)])
                } else if ("text-email".equals(child.getName())) {
                    ruleList.add([expr:'!value || /^(([^<>()\\[\\]\\\\.,;:\\s@"]+(\\.[^<>()\\[\\]\\\\.,;:\\s@"]+)*)|(".+"))@((\\[[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}])|(([a-zA-Z\\-0-9]+\\.)+[a-zA-Z]{2,}))$/.test(value)',
                            message:eci.l10nFacade.localize(MSG_EMAIL)])
                } else if ("text-url".equals(child.getName())) {
                    ruleList.add([expr:'!value || /((([A-Za-z]{3,9}:(?:\\/\\/)?)(?:[\\-;:&=\\+\\$,\\w]+@)?[A-Za-z0-9\\.\\-]+|(?:www\\.|[\\-;:&=\\+\\$,\\w]+@)[A-Za-z0-9\\.\\-]+)((?:\\/[\\+~%\\/\\.\\w\\-_]*)?\\??(?:[\\-\\+=&;%@\\.\\w_]*)#?(?:[\\.\\!\\/\\\\\\w]*))?)/.test(value)',
                            message:eci.l10nFacade.localize(MSG_URL)])
                } else if ("matches".equals(child.getName())) {
                    ruleList.add([expr:'!value || /' + child.attribute("regexp") + '/.test(value)',
                            message:eci.l10nFacade.localize(child.attribute("message"))])
                } else if ("number-range".equals(child.getName())) {
                    String minStr = child.attribute("min")
                    String maxStr = child.attribute("max")
                    boolean minEquals = !"false".equals(child.attribute("min-include-equals"))
                    boolean maxEquals = "true".equals(child.attribute("max-include-equals"))
                    String message = child.attribute("message")
                    if (message == null || message.isEmpty()) {
                        if (minStr && maxStr) message = "Enter a number between ${minStr} and ${maxStr}"
                        else if (minStr) message = "Enter a number greater than ${minStr}"
                        else if (maxStr) message = "Enter a number less than ${maxStr}"
                    }
                    String compareStr = "";
                    if (minStr) compareStr += ' && $root.moqui.parseNumber(value) ' + (minEquals ? '>= ' : '> ') + minStr
                    if (maxStr) compareStr += ' && $root.moqui.parseNumber(value) ' + (maxEquals ? '<= ' : '< ') + maxStr
                    ruleList.add([expr:'!value || (!Number.isNaN($root.moqui.parseNumber(value))' + compareStr + ')', message:message])
                }
            }

            // Fallback to type attribute for numbers
            String type = validateNode.attribute('type')
            if (!foundNumber && type != null) {
                if (type.endsWith("BigInteger") || type.endsWith("Long") || type.endsWith("Integer")) {
                    ruleList.add([expr:VALIDATE_NUMBER_INT, message:eci.l10nFacade.localize(MSG_NUMBER_INT)])
                } else if (type.endsWith("BigDecimal") || type.endsWith("Double") || type.endsWith("Float") || type.endsWith("Number")) {
                    ruleList.add([expr:VALIDATE_NUMBER, message:eci.l10nFacade.localize(MSG_NUMBER)])
                }
            }
        } else if (validateNode.name == "field") {
            String type = validateNode.attribute('type')
            if (type != null && (type.startsWith("number-") || type.startsWith("currency-"))) {
                if (type.endsWith("integer")) {
                    ruleList.add([expr:VALIDATE_NUMBER_INT, message:eci.l10nFacade.localize(MSG_NUMBER_INT)])
                } else {
                    ruleList.add([expr:VALIDATE_NUMBER, message:eci.l10nFacade.localize(MSG_NUMBER)])
                }
            }
        }
        return ruleList.size() > 0 ? ruleList : null
    }
}
