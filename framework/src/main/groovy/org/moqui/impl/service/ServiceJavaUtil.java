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
package org.moqui.impl.service;

import org.moqui.impl.StupidClassLoader;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.StupidUtilities;
import org.moqui.impl.StupidWebUtilities;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.MNode;
import org.owasp.html.HtmlChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.*;

public class ServiceJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceJavaUtil.class);

    public enum ParameterAllowHtml { ANY, SAFE, NONE }
    public enum ParameterType { STRING, INTEGER, LONG, FLOAT, DOUBLE, BIG_DECIMAL, BIG_INTEGER, TIME, DATE, TIMESTAMP, LIST, SET }
    public static Map<String, ParameterType> typeEnumByString = new HashMap<>();
    static {
        typeEnumByString.put("String", ParameterType.STRING); typeEnumByString.put("java.lang.String", ParameterType.STRING);
        typeEnumByString.put("Integer", ParameterType.INTEGER); typeEnumByString.put("java.lang.Integer", ParameterType.INTEGER);
        typeEnumByString.put("Long", ParameterType.LONG); typeEnumByString.put("java.lang.Long", ParameterType.LONG);
        typeEnumByString.put("Float", ParameterType.FLOAT); typeEnumByString.put("java.lang.Float", ParameterType.FLOAT);
        typeEnumByString.put("Double", ParameterType.DOUBLE); typeEnumByString.put("java.lang.Double", ParameterType.DOUBLE);
        typeEnumByString.put("BigDecimal", ParameterType.BIG_DECIMAL); typeEnumByString.put("java.math.BigDecimal", ParameterType.BIG_DECIMAL);
        typeEnumByString.put("BigInteger", ParameterType.BIG_INTEGER); typeEnumByString.put("java.math.BigInteger", ParameterType.BIG_INTEGER);

        typeEnumByString.put("Time", ParameterType.TIME); typeEnumByString.put("java.sql.Time", ParameterType.TIME);
        typeEnumByString.put("Date", ParameterType.DATE); typeEnumByString.put("java.sql.Date", ParameterType.DATE);
        typeEnumByString.put("Timestamp", ParameterType.TIMESTAMP); typeEnumByString.put("java.sql.Timestamp", ParameterType.TIMESTAMP);
        typeEnumByString.put("Collection", ParameterType.LIST); typeEnumByString.put("java.util.Collection", ParameterType.LIST);
        typeEnumByString.put("List", ParameterType.LIST); typeEnumByString.put("java.util.List", ParameterType.LIST);
        typeEnumByString.put("Set", ParameterType.SET); typeEnumByString.put("java.util.Set", ParameterType.SET);
    }

    /** This is a dumb data holder class for framework internal use only; in Java for efficiency as it is used a LOT */
    public static class ParameterInfo {
        public final ServiceDefinition sd;
        public final String serviceName;
        public final MNode parameterNode;
        public final String name, type, format;
        public final ParameterType parmType;
        public final Class parmClass;

        public final String entityName, fieldName;
        public final String defaultStr, defaultValue;
        public final boolean defaultValueNeedsExpand;
        public final boolean hasDefault;
        public final boolean thisOrChildHasDefault;
        public final boolean required;
        public final boolean disabled;
        public final ParameterAllowHtml allowHtml;
        public final boolean allowSafe;

        public final ParameterInfo[] childParameterInfoArray;

        public final ArrayList<MNode> validationNodeList;

        public ParameterInfo(ServiceDefinition sd, MNode parameterNode) {
            this.sd = sd;
            this.parameterNode = parameterNode;
            serviceName = sd.serviceName;

            name = parameterNode.attribute("name");
            String typeAttr = parameterNode.attribute("type");
            type = typeAttr == null || typeAttr.isEmpty() ? "String" : typeAttr;
            parmType = typeEnumByString.get(type);
            parmClass = StupidClassLoader.commonJavaClassesMap.get(type);

            format = parameterNode.attribute("format");
            entityName = parameterNode.attribute("entity-name");
            fieldName = parameterNode.attribute("field-name");

            String defaultTmp = parameterNode.attribute("default");
            if (defaultTmp != null && defaultTmp.isEmpty()) defaultTmp = null;
            defaultStr = defaultTmp;
            String defaultValTmp = parameterNode.attribute("default-value");
            if (defaultValTmp != null && defaultValTmp.isEmpty()) defaultValTmp = null;
            defaultValue = defaultValTmp;
            hasDefault = defaultStr != null || defaultValue != null;
            defaultValueNeedsExpand = defaultValue != null && defaultValue.contains("${");

            required = "true".equals(parameterNode.attribute("required"));
            disabled = "disabled".equals(parameterNode.attribute("required"));

            String allowHtmlStr = parameterNode.attribute("allow-html");
            if ("any".equals(allowHtmlStr)) allowHtml = ParameterAllowHtml.ANY;
            else if ("safe".equals(allowHtmlStr)) allowHtml = ParameterAllowHtml.SAFE;
            else allowHtml = ParameterAllowHtml.NONE;
            allowSafe = ParameterAllowHtml.SAFE == allowHtml;

            Map<String, ParameterInfo> childParameterInfoMap = new HashMap<>();
            ArrayList<String> parmNameList = new ArrayList<>();
            for (MNode childParmNode: parameterNode.children("parameter")) {
                String name = childParmNode.attribute("name");
                childParameterInfoMap.put(name, new ParameterInfo(sd, childParmNode));
                parmNameList.add(name);
            }
            int parmNameListSize = parmNameList.size();
            childParameterInfoArray = new ParameterInfo[parmNameListSize];
            boolean childHasDefault = false;
            for (int i = 0; i < parmNameListSize; i++) {
                String parmName = parmNameList.get(i);
                ParameterInfo pi = childParameterInfoMap.get(parmName);
                childParameterInfoArray[i] = pi;
                if (pi.thisOrChildHasDefault) childHasDefault = true;
            }
            thisOrChildHasDefault = hasDefault || childHasDefault;

            ArrayList<MNode> tempValidationNodeList = new ArrayList<>();
            for (MNode child: parameterNode.getChildren()) {
                if ("description".equals(child.getName()) || "parameter".equals(child.getName())) continue;
                tempValidationNodeList.add(child);
            }
            if (tempValidationNodeList.size() > 0) {
                validationNodeList = tempValidationNodeList;
            } else {
                validationNodeList = null;
            }
        }

        /** Currently used only in ServiceDefinition.checkParameterMap() */
        public Object convertType(String namePrefix, Object parameterValue, boolean isString, ExecutionContextImpl eci) {
            // no need to check for null, only called with parameterValue not empty
            // if (parameterValue == null) return null;
            // no need to check for type match, only called when types don't match
            // if (StupidJavaUtilities.isInstanceOf(parameterValue, type)) {

            // do type conversion if possible
            Object converted = null;
            boolean isEmptyString = isString && ((CharSequence) parameterValue).length() == 0;
            if (parmType != null && isString && !isEmptyString) {
                String valueStr = parameterValue.toString();
                // try some String to XYZ specific conversions for parsing with format, locale, etc
                switch (parmType) {
                    case INTEGER:
                    case LONG:
                    case FLOAT:
                    case DOUBLE:
                    case BIG_DECIMAL:
                    case BIG_INTEGER:
                        BigDecimal bdVal = eci.l10nFacade.parseNumber(valueStr, format);
                        if (bdVal == null) {
                            eci.messageFacade.addValidationError(null, namePrefix + name, serviceName,
                                    MessageFormat.format(eci.getL10n().localize("Value entered ({0}) could not be converted to a {1}{2,choice,0#|1# using format [}{3}{2,choice,0#|1#]}"),valueStr,type,(format != null ? 1 : 0),(format == null ? "" : format)), null);
                        } else {
                            switch (parmType) {
                                case INTEGER: converted = bdVal.intValue(); break;
                                case LONG: converted = bdVal.longValue(); break;
                                case FLOAT: converted = bdVal.floatValue(); break;
                                case DOUBLE: converted = bdVal.doubleValue(); break;
                                case BIG_INTEGER: converted = bdVal.toBigInteger(); break;
                                default: converted = bdVal;
                            }
                        }
                        break;
                    case TIME:
                        converted = eci.l10nFacade.parseTime(valueStr, format);
                        if (converted == null) eci.messageFacade.addValidationError(null, namePrefix + name,
                                serviceName, MessageFormat.format(eci.getL10n().localize("Value entered ({0}) could not be converted to a {1}{2,choice,0#|1# using format [}{3}{2,choice,0#|1#]}"),valueStr,type,(format != null ? 1 : 0),(format == null ? "" : format)), null);
                        break;
                    case DATE:
                        converted = eci.l10nFacade.parseDate(valueStr, format);
                        if (converted == null) eci.messageFacade.addValidationError(null, namePrefix + name,
                                serviceName, MessageFormat.format(eci.getL10n().localize("Value entered ({0}) could not be converted to a {1}{2,choice,0#|1# using format [}{3}{2,choice,0#|1#]}"),valueStr,type,(format != null ? 1 : 0),(format == null ? "" : format)), null);
                        break;
                    case TIMESTAMP:
                        converted = eci.l10nFacade.parseTimestamp(valueStr, format);
                        if (converted == null) eci.messageFacade.addValidationError(null, namePrefix + name,
                                serviceName, MessageFormat.format(eci.getL10n().localize("Value entered ({0}) could not be converted to a {1}{2,choice,0#|1# using format [}{3}{2,choice,0#|1#]}"),valueStr,type,(format != null ? 1 : 0),(format == null ? "" : format)), null);
                        break;
                    case LIST:
                        // strip off square braces
                        if (valueStr.charAt(0) == '[' && valueStr.charAt(valueStr.length()-1) == ']')
                            valueStr = valueStr.substring(1, valueStr.length()-1);
                        // split by comma or just create a list with the single string
                        if (valueStr.contains(",")) {
                            converted = Arrays.asList(valueStr.split(","));
                        } else {
                            List<String> newList = new ArrayList<>();
                            newList.add(valueStr);
                            converted = newList;
                        }
                        break;
                    case SET:
                        // strip off square braces
                        if (valueStr.charAt(0) == '[' && valueStr.charAt(valueStr.length()-1) == ']')
                            valueStr = valueStr.substring(1, valueStr.length()-1);
                        // split by comma or just create a list with the single string
                        if (valueStr.contains(",")) {
                            converted = new HashSet<>(Arrays.asList(valueStr.split(",")));
                        } else {
                            Set<String> newSet = new HashSet<>();
                            newSet.add(valueStr);
                            converted = newSet;
                        }
                        break;
                }
            }

            // fallback to a really simple type conversion
            // TODO: how to detect conversion failed to add validation error?
            if (converted == null && !isEmptyString) converted = StupidUtilities.basicConvert(parameterValue, type);

            return converted;
        }

        @SuppressWarnings("unchecked")
        Object validateParameterHtml(String namePrefix, Object parameterValue, boolean isString, ExecutionContextImpl eci) {
            // check for none/safe/any HTML

            if (isString) {
                return canonicalizeAndCheckHtml(sd, namePrefix, (String) parameterValue, eci);
            } else {
                Collection lst = (Collection) parameterValue;
                ArrayList<Object> lstClone = new ArrayList<>(lst);
                int lstSize = lstClone.size();
                for (int i = 0; i < lstSize; i++) {
                    Object obj = lstClone.get(i);
                    if (obj instanceof CharSequence) {
                        String htmlChecked = canonicalizeAndCheckHtml(sd, namePrefix, obj.toString(), eci);
                        lstClone.set(i, htmlChecked != null ? htmlChecked : obj);
                    } else {
                        lstClone.set(i, obj);
                    }
                }
                return lstClone;
            }
        }

        private String canonicalizeAndCheckHtml(ServiceDefinition sd, String namePrefix, String parameterValue, ExecutionContextImpl eci) {
            int indexOfEscape = -1;
            int indexOfLessThan = -1;
            char[] valueCharArray = parameterValue.toCharArray();
            int valueLength = valueCharArray.length;
            for (int i = 0; i < valueLength; i++) {
                char curChar = valueCharArray[i];
                if (curChar == '%' || curChar == '&') {
                    indexOfEscape = i;
                    if (indexOfLessThan >= 0) break;
                } else if (curChar == '<') {
                    indexOfLessThan = i;
                    if (indexOfEscape >= 0) break;
                }
            }
            if (indexOfEscape < 0 && indexOfLessThan < 0) return null;

            String canValue = parameterValue;
            if (indexOfEscape >= 0) {
                // don't want to unescape HTML, escaped chars should be preserved or we mess up the HTML: canValue = StringEscapeUtils.unescapeHtml(parameterValue);
                // don't want to do this either, should be done before this: canValue = new URLCodec().decode(parameterValue);
            }

            if (allowSafe) {
                SafeHtmlChangeListener changes = new SafeHtmlChangeListener(eci, sd);
                String cleanHtml = StupidWebUtilities.getSafeHtmlPolicy().sanitize(canValue, changes, namePrefix.concat(name));
                List<String> cleanChanges = changes.getMessages();
                // use message instead of error, accept cleaned up HTML
                if (cleanChanges.size() > 0) {
                    for (String cleanChange: cleanChanges) eci.getMessage().addMessage(cleanChange);
                    logger.info("Service parameter safe HTML messages for " + sd.serviceName + "." + name + ": " + cleanChanges);
                    return cleanHtml;
                } else {
                    // nothing changed, return null
                    return null;
                }
            } else {
                // check for "<"; this will protect against HTML/JavaScript injection
                if (indexOfLessThan >= 0) {
                    eci.getMessage().addValidationError(null, namePrefix + name, sd.serviceName, eci.getL10n().localize("HTML not allowed (less-than (<), greater-than (>), etc symbols)"), null);
                }
                // nothing changed, return null
                return null;
            }
        }
    }

    private static class SafeHtmlChangeListener implements HtmlChangeListener<String> {
        private ExecutionContextImpl eci;
        private ServiceDefinition sd;
        private List<String> messages = new LinkedList<>();
        SafeHtmlChangeListener(ExecutionContextImpl eci, ServiceDefinition sd) { this.eci = eci; this.sd = sd; }
        List<String> getMessages() { return messages; }
        @SuppressWarnings("NullableProblems")
        @Override
        public void discardedTag(@Nullable String context, String elementName) {
            messages.add(MessageFormat.format(eci.getL10n().localize("Removed HTML element {0} from field {1} in service {2}"),
                    elementName, context, sd.serviceName));
        }
        @SuppressWarnings("NullableProblems")
        @Override
        public void discardedAttributes(@Nullable String context, String tagName, String... attributeNames) {
            for (String attrName: attributeNames)
                messages.add(MessageFormat.format(eci.getL10n().localize("Removed attribute {0} from HTML element {1} from field {2} in service {3}"),
                        attrName, tagName, context, sd.serviceName));
        }
    }
}
