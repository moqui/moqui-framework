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

import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.StupidUtilities;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
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
        public ServiceDefinition sd;
        public String serviceName;
        public MNode parameterNode;
        public String name, type, format;
        public ParameterType parmType;

        public String entityName, fieldName;
        public String defaultStr, defaultValue;
        public boolean required;
        public boolean disabled;
        public ParameterAllowHtml allowHtml;

        public Map<String, ParameterInfo> childParameterInfoMap = new HashMap<>();
        public List<MNode> subtypeNodeList = null;
        public List<MNode> validationNodeList = new ArrayList<>();

        public ParameterInfo(ServiceDefinition sd, MNode parameterNode) {
            this.sd = sd;
            this.parameterNode = parameterNode;
            serviceName = sd.getServiceName();

            name = parameterNode.attribute("name");
            type = parameterNode.attribute("type");
            if (type == null || type.length() == 0) type = "String";
            parmType = typeEnumByString.get(type);

            format = parameterNode.attribute("format");
            entityName = parameterNode.attribute("entity-name");
            fieldName = parameterNode.attribute("field-name");
            defaultStr = parameterNode.attribute("default");
            defaultValue = parameterNode.attribute("default-value");
            required = "true".equals(parameterNode.attribute("required"));
            disabled = "disabled".equals(parameterNode.attribute("required"));

            String allowHtmlStr = parameterNode.attribute("allow-html");
            if ("any".equals(allowHtmlStr)) allowHtml = ParameterAllowHtml.ANY;
            else if ("safe".equals(allowHtmlStr)) allowHtml = ParameterAllowHtml.SAFE;
            else allowHtml = ParameterAllowHtml.NONE;

            for (MNode childParmNode: parameterNode.children("parameter")) {
                String name = childParmNode.attribute("name");
                childParameterInfoMap.put(name, new ParameterInfo(sd, childParmNode));
            }
            if (parameterNode.hasChild("subtype")) subtypeNodeList = parameterNode.children("subtype");

            for (MNode child: parameterNode.getChildren()) {
                if ("description".equals(child.getName()) || "subtype".equals(child.getName()) || "parameter".equals(child.getName())) continue;
                validationNodeList.add(child);
            }
        }
    }

    /** Currently used only in ServiceDefinition.checkParameterMap() */
    public static Object convertType(ParameterInfo pi, String namePrefix, String parameterName, Object parameterValue,
                                          ExecutionContextImpl eci) {
        // no need to check for null, only called with parameterValue not empty
        // if (parameterValue == null) return null;
        // no need to check for type match, only called when types don't match
        // if (StupidJavaUtilities.isInstanceOf(parameterValue, type)) {

        String type = pi.type;
        // do type conversion if possible
        String format = pi.format;
        Object converted = null;
        boolean isString = parameterValue instanceof CharSequence;
        boolean isEmptyString = isString && ((CharSequence) parameterValue).length() == 0;
        if (pi.parmType != null && isString && !isEmptyString) {
            String valueStr = parameterValue.toString();
            // try some String to XYZ specific conversions for parsing with format, locale, etc
            switch (pi.parmType) {
                case INTEGER:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case BIG_DECIMAL:
                case BIG_INTEGER:
                    BigDecimal bdVal = eci.getL10n().parseNumber(valueStr, format);
                    if (bdVal == null) {
                        eci.getMessage().addValidationError(null, namePrefix + parameterName, pi.serviceName, "Value entered (" + valueStr + ") could not be converted to a " + type + (format != null ? " using format [" + format + "]" : ""), null);
                    } else {
                        switch (pi.parmType) {
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
                    converted = eci.getL10n().parseTime(valueStr, format);
                    if (converted == null) eci.getMessage().addValidationError(null, namePrefix + parameterName, pi.serviceName, "Value entered (" + valueStr + ") could not be converted to a " + type + (format != null ? " using format [" + format + "]" : ""), null);
                    break;
                case DATE:
                    converted = eci.getL10n().parseDate(valueStr, format);
                    if (converted == null) eci.getMessage().addValidationError(null, namePrefix + parameterName, pi.serviceName, "Value entered (" + valueStr + ") could not be converted to a " + type + (format != null ? " using format [" + format + "]" : ""), null);
                    break;
                case TIMESTAMP:
                    converted = eci.getL10n().parseTimestamp(valueStr, format);
                    if (converted == null) eci.getMessage().addValidationError(null, namePrefix + parameterName, pi.serviceName, "Value entered (" + valueStr + ") could not be converted to a " + type + (format != null ? " using format [" + format + "]" : ""), null);
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
}
