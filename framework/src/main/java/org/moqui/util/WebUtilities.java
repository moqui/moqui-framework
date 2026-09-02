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
package org.moqui.util;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.commons.fileupload2.core.FileItem;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import org.moqui.BaseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebUtilities {
    private static final Logger logger = LoggerFactory.getLogger(WebUtilities.class);

    public static Map<String, Object> getPathInfoParameterMap(String pathInfoStr) {
        if (pathInfoStr == null || pathInfoStr.length() == 0) return null;
        Map<String, Object> paramMap = null;
        // add in all path info parameters /~name1=value1/~name2=value2/
        String[] pathElements = pathInfoStr.split("/");
        for (int i = 0; i < pathElements.length; i++) {
            String element = pathElements[i];
            int equalsIndex = element.indexOf("=");
            if (element.length() > 0 && element.charAt(0) == '~' && equalsIndex > 0) {
                try {
                    String name = URLDecoder.decode(element.substring(1, equalsIndex), "UTF-8");
                    String value = URLDecoder.decode(element.substring(equalsIndex + 1), "UTF-8");
                    // NOTE: currently ignoring existing values, likely won't be any: Object curValue = paramMap.get(name)
                    if (paramMap == null) paramMap = new HashMap<>();
                    paramMap.put(name, value);
                } catch (UnsupportedEncodingException e) {
                    logger.error("Error decoding path parameter", e);
                }
            }
        }
        return paramMap;
    }

    public static Object canonicalizeValue(Object orig) {
        Object canVal = orig;
        List lst = null;
        if (orig instanceof List) { lst = (List) orig; }
        else if (orig instanceof String[]) { lst = Arrays.asList((String[]) orig); }
        else if (orig instanceof Object[]) { lst = Arrays.asList((Object[]) orig); }
        if (lst != null) {
            if (lst.size() == 1) {
                canVal = lst.get(0);
            } else if (lst.size() > 1) {
                List<Object> newList = new ArrayList<>(lst.size());
                canVal = newList;
                for (Object obj : lst) {
                    if (obj instanceof CharSequence) {
                        try {
                            newList.add(URLDecoder.decode(obj.toString(), "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            logger.warn("Error decoding string " + obj, e);
                        }
                    } else {
                        newList.add(obj);
                    }
                }
            }
        }
        // catch strings or lists with a single string in them unwrapped above
        try {
            if (canVal instanceof CharSequence) canVal = URLDecoder.decode(canVal.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.warn("Error decoding string " + canVal, e);
        }
        return canVal;
    }

    public static Map<String, Object> simplifyRequestParameters(HttpServletRequest request, boolean bodyOnly) {
        Set<String> urlParms = null;
        if (bodyOnly) {
            urlParms = new HashSet<>();
            String query = request.getQueryString();
            if (query != null && !query.isEmpty()) {
                for (String nameValuePair : query.split("&")) {
                    int eqIdx = nameValuePair.indexOf("=");
                    if (eqIdx < 0) urlParms.add(nameValuePair);
                    else urlParms.add(nameValuePair.substring(0, eqIdx));
                }
            }
        }
        Map<String, String[]> reqParmOrigMap = request.getParameterMap();
        Map<String, Object> reqParmMap = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : reqParmOrigMap.entrySet()) {
            String key = entry.getKey();
            if (bodyOnly && urlParms.contains(key)) continue;
            String[] valArray = entry.getValue();
            if (valArray == null) {
                reqParmMap.put(key, null);
            } else {
                int valLength = valArray.length;
                if (valLength == 0) {
                    reqParmMap.put(key, null);
                } else if (valLength == 1) {
                    String singleVal = valArray[0];
                    // change &nbsp; (\u00a0) to null, used as a placeholder when empty string doesn't work
                    if ("\u00a0".equals(singleVal)) {
                        reqParmMap.put(key, null);
                    } else {
                        reqParmMap.put(key, singleVal);
                    }
                } else {
                    reqParmMap.put(key, Arrays.asList(valArray));
                }
            }
        }
        return reqParmMap;
    }

    /** Sort of like JSON but output in JS syntax for HTML attributes like in a Vue Template */
    public static String encodeHtmlJsSafe(CharSequence original) {
        if (original == null) return "";
        StringBuilder newValue = new StringBuilder(original);
        for (int i = 0; i < newValue.length(); i++) {
            char curChar = newValue.charAt(i);
            switch (curChar) {
                case '\'': newValue.replace(i, i + 1, "\\'"); i += 1; break;
                case '"': newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case '&': newValue.replace(i, i + 1, "&amp;"); i += 4; break;
                case '<': newValue.replace(i, i + 1, "&lt;"); i += 3; break;
                case '>': newValue.replace(i, i + 1, "&gt;"); i += 3; break;
                case '\n': newValue.replace(i, i + 1, "\\n"); i += 1; break;
                case '\r': newValue.replace(i, i + 1, "\\r"); i += 1; break;
                case 0x5: newValue.replace(i, i + 1, "..."); i += 2; break;
                case 0x12: newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case 0x13: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x14: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x16: newValue.replace(i, i + 1, "-"); break;
                case 0x17: newValue.replace(i, i + 1, "-"); break;
                case 0x19: newValue.replace(i, i + 1, "tm"); i++; break;
            }
        }
        return newValue.toString();
    }

    public static String encodeHtmlJsSafeObject(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof Collection) {
            return encodeHtmlJsSafeCollection((Collection) value);
        } else if (value instanceof Map) {
            return encodeHtmlJsSafeMap((Map) value);
        } else if (value instanceof Number) {
            if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
            else return value.toString();
        } else if (value instanceof Boolean) {
            Boolean boolVal = (Boolean) value;
            return boolVal ? "true" : "false";
        } else {
            return "'" + encodeHtmlJsSafe(value.toString()) + "'";
        }
    }

    public static String encodeHtmlJsSafeMap(Map fieldValues) {
        if (fieldValues == null) return "null";
        StringBuilder out = new StringBuilder().append("{");
        boolean isFirst = true;
        for (Object entryObj : fieldValues.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            Object key = entry.getKey();
            if (key == null) continue;
            if (isFirst) { isFirst = false; } else { out.append(","); }
            out.append("'").append(encodeHtmlJsSafe(key.toString())).append("':");
            Object value = entry.getValue();
            out.append(encodeHtmlJsSafeObject(value));
        }
        out.append("}");
        return out.toString();
    }

    public static String encodeHtmlJsSafeCollection(Collection value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder();
        out.append("[");
        if (value instanceof RandomAccess) {
            List curList = (List) value;
            int curListSize = curList.size();
            for (int vi = 0; vi < curListSize; vi++) {
                Object listVal = curList.get(vi);
                out.append(encodeHtmlJsSafeObject(listVal));
                if ((vi + 1) < curListSize) out.append(",");
            }
        } else {
            Iterator colIter = value.iterator();
            while (colIter.hasNext()) {
                Object colVal = colIter.next();
                out.append(encodeHtmlJsSafeObject(colVal));
                if (colIter.hasNext()) out.append(",");
            }
        }
        out.append("]");
        return out.toString();
    }

    // for backward compatibility:
    public static String fieldValuesEncodeHtmlJsSafe(Map fieldValues) { return encodeHtmlJsSafeMap(fieldValues); }

    public static String encodeHtml(String original) {
        if (original == null) return "";
        StringBuilder newValue = new StringBuilder(original);
        for (int i = 0; i < newValue.length(); i++) {
            char curChar = newValue.charAt(i);
            switch (curChar) {
                case '\'': newValue.replace(i, i + 1, "&#39;"); i += 4; break;
                case '"': newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case '&': newValue.replace(i, i + 1, "&amp;"); i += 4; break;
                case '<': newValue.replace(i, i + 1, "&lt;"); i += 3; break;
                case '>': newValue.replace(i, i + 1, "&gt;"); i += 3; break;
                case 0x5: newValue.replace(i, i + 1, "..."); i += 2; break;
                case 0x12: newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case 0x13: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x14: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x16: newValue.replace(i, i + 1, "-"); break;
                case 0x17: newValue.replace(i, i + 1, "-"); break;
                case 0x19: newValue.replace(i, i + 1, "tm"); i++; break;
            }
        }
        return newValue.toString();
    }

    /** Pattern may have a plain number, '*' for wildcard, or a '-' separated number range for each dot separated segment;
     * may also have multiple comma-separated patterns */
    public static boolean ip4Matches(String patternString, String address) {
        if (patternString == null || patternString.isEmpty()) return true;
        if (address == null || address.isEmpty()) return false;
        String[] patterns = patternString.split(",");
        boolean anyMatches = false;
        for (int pi = 0; pi < patterns.length; pi++) {
            String pattern = patterns[pi].trim();
            if (pattern.isEmpty()) continue;
            if (pattern.equals("*.*.*.*") || pattern.equals("*")) {
                anyMatches = true;
                break;
            }
            String[] patternArray = pattern.split("\\.");
            String[] addressArray = address.split("\\.");
            boolean allMatch = true;
            for (int i = 0; i < patternArray.length; i++) {
                String curPattern = patternArray[i];
                if (i >= addressArray.length) { allMatch = false; break; }
                String curAddress = addressArray[i];
                if (curPattern.equals("*") || curPattern.equals(curAddress)) continue;
                if (curPattern.contains("-")) {
                    byte min = Byte.parseByte(curPattern.split("-")[0]);
                    byte max = Byte.parseByte(curPattern.split("-")[1]);
                    byte ip = Byte.parseByte(curAddress);
                    if (ip < min || ip > max) { allMatch = false; break; }
                } else {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) { anyMatches = true; break; }
        }
        return anyMatches;
    }

    /**
     * Strip wrapping brackets and :port from a client IP. IPv4:port (CloudFront-Viewer-Address) drops the port;
     * [IPv6]:port keeps the IPv6 literal. Unbracketed IPv6 is not treated as host:port.
     */
    public static String canonicalizeClientIp(String clientIp) {
        if (clientIp == null) return null;
        String ip = clientIp.trim();
        if (ip.isEmpty()) return ip;

        if (ip.charAt(0) == '[') {
            int close = ip.indexOf(']');
            if (close > 1) {
                return normalizeIpLiteral(ip.substring(1, close));
            }
        }

        int firstColon = ip.indexOf(':');
        int lastColon = ip.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String left = ip.substring(0, firstColon);
            if (isDottedIpv4(left)) return left;
            return ip;
        }
        if (firstColon >= 0) {
            int pct = ip.indexOf('%');
            if (pct > 0) ip = ip.substring(0, pct);
            return normalizeIpLiteral(ip);
        }
        return ip;
    }

    private static boolean isDottedIpv4(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            try {
                int n = Integer.parseInt(parts[i]);
                if (n < 0 || n > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeIpLiteral(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            String host = addr.getHostAddress();
            int pct = host.indexOf('%');
            if (pct > 0) host = host.substring(0, pct);
            return host;
        } catch (Exception e) {
            return ip;
        }
    }

    /** IPv4 patterns use {@link #ip4Matches}; IPv6 is an exact match after {@link #canonicalizeClientIp}. */
    public static boolean ipMatches(String patternString, String address) {
        if (patternString == null || patternString.isEmpty()) return true;
        String canonAddr = canonicalizeClientIp(address);
        if (canonAddr == null || canonAddr.isEmpty()) return false;
        String[] patterns = patternString.split(",");
        for (int pi = 0; pi < patterns.length; pi++) {
            String raw = patterns[pi].trim();
            if (raw.isEmpty()) continue;
            if (raw.equals("*") || raw.equals("*.*.*.*")) return true;
            String pattern = canonicalizeClientIp(raw);
            if (pattern == null || pattern.isEmpty()) continue;
            boolean patternV6 = pattern.indexOf(':') >= 0;
            boolean addrV6 = canonAddr.indexOf(':') >= 0;
            if (patternV6 || addrV6) {
                if (ipv6Equal(pattern, canonAddr)) return true;
            } else if (ip4Matches(pattern, canonAddr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ipv6Equal(String a, String b) {
        if (a.equals(b)) return true;
        try {
            byte[] ba = InetAddress.getByName(a).getAddress();
            byte[] bb = InetAddress.getByName(b).getAddress();
            return Arrays.equals(ba, bb);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True if url is a same-origin path or absolute URL safe to use as a post-login redirect.
     * Relative paths must start with a single slash (not //). Absolute http(s) URLs must match configuredHost
     * (webapp http-host / https-host). If configuredHost is empty, absolute URLs are rejected.
     */
    public static boolean isSameOriginRedirect(String url, HttpServletRequest request) {
        String configured = null;
        if (request != null) {
            Object attr = request.getAttribute("moqui.webapp.httpHost");
            if (attr instanceof CharSequence) configured = attr.toString();
        }
        return isSameOriginRedirect(url, request, configured);
    }

    public static boolean isSameOriginRedirect(String url, HttpServletRequest request, String configuredHost) {
        if (url == null || request == null) return false;
        String p = url.trim();
        if (p.isEmpty()) return false;
        String lower = p.toLowerCase(Locale.ROOT);
        if (p.indexOf('\\') >= 0) return false;
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) return false;
        if (p.startsWith("//")) return false;
        if (p.startsWith("/")) return true;
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (configuredHost == null || configuredHost.isEmpty()) return false;
        try {
            URI uri = URI.create(p);
            if (uri.getUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            return host.equalsIgnoreCase(configuredHost);
        } catch (Exception e) {
            return false;
        }
    }

    /** UserAccount fields that are password material. Omitted from REST JSON and not accepted on generic entity writes. */
    public static final Set<String> USER_ACCOUNT_SECRET_FIELDS = new HashSet<>(Arrays.asList(
            "currentPassword", "resetPassword", "passwordSalt", "passwordHashType", "passwordBase64"));

    /** UserAccount fields that control authentication and identity. Generic REST drops these unless the caller is ADMIN. */
    public static final Set<String> USER_ACCOUNT_IDENTITY_FIELDS = new HashSet<>(Arrays.asList(
            "disabled", "username", "emailAddress", "ipAllowed", "externalAuthId", "requirePasswordChange",
            "hasLoggedOut", "disabledDateTime", "successiveFailedLogins"));

    private static final Set<String> CREDENTIAL_PARAMETER_NAMES_LC = new HashSet<>(Arrays.asList(
            "password", "oldpassword", "newpassword", "newpasswordverify", "currentpassword",
            "resetpassword", "authpassword", "api_key", "login_key", "passwordsalt",
            "passwordhashtype", "passwordbase64", "passwordverify"));

    /** Simple names and short aliases for identity-admin entities (not UserAccount). */
    private static final Set<String> IDENTITY_ADMIN_ENTITY_KEYS = new HashSet<>(Arrays.asList(
            "userloginkey", "usergroupmember", "usergrouppermission", "userpermission",
            "artifactauthz", "artifactgroup", "artifactgroupmember", "artifacttarpit",
            "usergrouppermissions", "userpermissions", "artifactgroups"));

    private static final Set<String> SECRET_CONFIG_ENTITY_KEYS = new HashSet<>(Arrays.asList(
            "emailserver", "systemmessageremote"));

    private static String simpleEntityKey(String entityName) {
        if (entityName == null || entityName.isEmpty()) return "";
        String n = entityName.trim();
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) n = n.substring(dot + 1);
        return n.toLowerCase(Locale.ROOT);
    }

    public static boolean isUserAccountEntity(String entityName) {
        if (entityName == null || entityName.isEmpty()) return false;
        return "moqui.security.UserAccount".equals(entityName) || "users".equals(entityName)
                || entityName.endsWith(".UserAccount");
    }

    /** UserLoginKey, group membership/permissions, and artifact authz. Not UserAccount. */
    public static boolean isIdentityAdminEntity(String entityName) {
        return IDENTITY_ADMIN_ENTITY_KEYS.contains(simpleEntityKey(entityName));
    }

    /** EmailServer and SystemMessageRemote (host/password and HMAC secret). */
    public static boolean isSecretConfigEntity(String entityName) {
        return SECRET_CONFIG_ENTITY_KEYS.contains(simpleEntityKey(entityName));
    }

    /**
     * Entities the Auto Screen / Data Edit / Data Import generic engines must not take from a request entity name.
     * UserAccount stays available there (Tools Data Edit is an existing admin path); identity-admin and
     * secret-config entities are not. System/Security screens use literal entity names and are unchanged.
     */
    public static boolean isRestrictedGenericEntity(String entityName) {
        return isIdentityAdminEntity(entityName) || isSecretConfigEntity(entityName);
    }

    /** Comma-separated IDs; empty/null yields an empty set. */
    public static Set<String> csvIdSet(String list) {
        Set<String> out = new HashSet<>();
        if (list == null || list.isEmpty()) return out;
        String[] parts = list.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * Connect-host allow-list. Empty/null allowedList permits any host.
     * A list entry matches an exact hostname/IP (case-insensitive) or a DNS subdomain
     * ({@code smtp.example.com} matches {@code example.com}; {@code notexample.com} does not).
     */
    public static boolean hostAllowedByConf(String host, String allowedList) {
        if (allowedList == null || allowedList.trim().isEmpty()) return true;
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return false;
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        String[] parts = allowedList.split(",");
        for (int i = 0; i < parts.length; i++) {
            String d = parts[i].trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty()) continue;
            if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    /** Drop password-hash fields from a parameter map in place (generic entity create/update/store). */
    public static void removeUserAccountSecretsFromMap(Map<?, ?> map) {
        if (map == null) return;
        for (String field : USER_ACCOUNT_SECRET_FIELDS) map.remove(field);
    }

    /** Drop identity-control fields from a parameter map in place. */
    public static void removeUserAccountIdentityFromMap(Map<?, ?> map) {
        if (map == null) return;
        for (String field : USER_ACCOUNT_IDENTITY_FIELDS) map.remove(field);
    }

    /**
     * Copy of obj for JSON output with UserAccount password-hash fields removed at every map level.
     * Maps and collections are copied; other values are returned as-is.
     */
    public static Object stripUserAccountSecrets(Object obj) {
        if (obj instanceof Map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                Object key = entry.getKey();
                if (key instanceof String && USER_ACCOUNT_SECRET_FIELDS.contains(key)) continue;
                out.put(key, stripUserAccountSecrets(entry.getValue()));
            }
            return out;
        } else if (obj instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object value : (Collection<?>) obj) out.add(stripUserAccountSecrets(value));
            return out;
        }
        return obj;
    }

    /** Copy of request parameters with credential field names omitted (case-insensitive). */
    public static Map<String, Object> stripCredentialParameters(Map<String, ?> parms) {
        if (parms == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : parms.entrySet()) {
            String key = entry.getKey();
            if (key != null && CREDENTIAL_PARAMETER_NAMES_LC.contains(key.toLowerCase(Locale.ROOT))) continue;
            out.put(key, entry.getValue());
        }
        return out;
    }

    public static byte[] windowsPex = {(byte) 0x4d, (byte) 0x5a};
    public static byte[] linuxElf = {(byte) 0x7f, (byte) 0x45, (byte) 0x4c, (byte) 0x46};
    public static byte[] javaClass = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe};
    public static byte[] macOs = {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce};
    public static byte[] macOs64 = {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf};
    public static byte[] macOsReverse = {(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe};
    public static byte[] macOs64Reverse = {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe};
    public static byte[][] allOsExecutables = {windowsPex, linuxElf, javaClass, macOs, macOs64, macOsReverse, macOs64Reverse};

    /** Looks for byte patterns for Windows PE (4d5a), Linux ELF (7f454c46), Java class (cafebabe), macOS Mach-O (feedface/feedfacf and byte-swapped) */
    public static boolean isExecutable(FileItem item) throws IOException {
        InputStream is = item.getInputStream();
        byte[] bytes = new byte[4];
        is.read(bytes, 0, 4);
        is.close();
        return isExecutable(bytes);
    }
    /** Looks for byte patterns for Windows PE (4d5a), Linux ELF (7f454c46), Java class (cafebabe), macOS Mach-O (feedface/feedfacf and byte-swapped) */
    public static boolean isExecutable(byte[] bytes) {
        boolean foundPattern = false;
        for (int i = 0; i < allOsExecutables.length; i++) {
            byte[] execPattern = allOsExecutables[i];
            boolean execMatches = true;
            for (int j = 0; j < execPattern.length; j++) {
                if (bytes[j] != execPattern[j]) {
                    execMatches = false;
                    break;
                }
            }
            if (execMatches) {
                foundPattern = true;
                break;
            }
        }
        return foundPattern;
    }

    public static String simpleHttpStringRequest(String location, String requestBody, String contentType) {
        if (contentType == null || contentType.isEmpty()) contentType = "text/plain";
        String resultString = "";

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));

        try {
            httpClient.start();
            Request request = httpClient.POST(location);
            if (requestBody != null && !requestBody.isEmpty())
                request.body(new StringRequestContent(contentType, requestBody, StandardCharsets.UTF_8));
            ContentResponse response = request.send();
            resultString = StringUtilities.toStringCleanBom(response.getContent());
        } catch (Exception e) {
            throw new BaseException("Error in http client request", e);
        } finally {
            try {
                httpClient.stop();
            } catch (Exception e) {
                logger.error("Error stopping http client", e);
            }
        }
        return resultString;
    }

    public static String simpleHttpMapRequest(String location, Map requestMap) {
        String resultString = "";

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));

        try {
            httpClient.start();
            Request request = httpClient.POST(location);
            if (requestMap != null) for (Object entryObj : requestMap.entrySet()) {
                Map.Entry requestEntry = (Map.Entry) entryObj;
                request.param(requestEntry.getKey() != null ? requestEntry.getKey().toString() : null,
                        requestEntry.getValue() != null ? requestEntry.getValue().toString() : null);
            }
            ContentResponse response = request.send();
            resultString = StringUtilities.toStringCleanBom(response.getContent());
        } catch (Exception e) {
            throw new BaseException("Error in http client request", e);
        } finally {
            try {
                httpClient.stop();
            } catch (Exception e) {
                logger.error("Error stopping http client", e);
            }
        }
        return resultString;
    }

    public static class SimpleEntry implements Map.Entry<String, Object> {
        protected String key;
        protected Object value;
        SimpleEntry(String key, Object value) {
            this.key = key;
            this.value = value;
        }
        public String getKey() { return key; }
        public Object getValue() { return value; }
        public Object setValue(Object v) {
            Object orig = value;
            value = v;
            return orig;
        }
    }

    public static Enumeration<String> emptyStringEnum = new Enumeration<String>() {
        @Override public boolean hasMoreElements() { return false; }
        @Override public String nextElement() { return null; }
    };
    public static boolean testSerialization(String name, Object value) {
        // return true;
        /* for testing purposes only, don't enable by default: */
        // logger.warn("Test ser " + name + "(" + (value != null ? value.getClass().getName() : "") + ":" + (value != null && value.getClass().getClassLoader() != null ? value.getClass().getClassLoader().getClass().getName() : "") + ")" + " value: " + value);
        if (value == null) return true;
        try {
            ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream());
            out.writeObject(value);
            out.close();
            return true;
        } catch (IOException e) {
            logger.warn("Tried to set session attribute [" + name + "] with non-serializable value of type " + value.getClass().getName(), e);
            return false;
        }
    }

    public interface AttributeContainer {
        Enumeration<String> getAttributeNames();
        Object getAttribute(String name);
        void setAttribute(String name, Object value);
        void removeAttribute(String name);
        default List<String> getAttributeNameList() {
            List<String> nameList = new LinkedList<>();
            Enumeration<String> attrNames = getAttributeNames();
            while (attrNames.hasMoreElements()) nameList.add(attrNames.nextElement());
            return nameList;
        }
    }

    public static class ServletRequestContainer implements AttributeContainer {
        ServletRequest req;
        public ServletRequestContainer(ServletRequest request) { req = request; }
        @Override public Enumeration<String> getAttributeNames() {
            try {
                return req.getAttributeNames();
            } catch (NullPointerException e) {
                // Jetty EE11 recycles the underlying request object after completion; guard against background thread access
                logger.warn("Tried getAttributeNames() on recycled request: " + e.toString());
                return emptyStringEnum;
            }
        }
        @Override public Object getAttribute(String name) { return req.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) {
            if (!testSerialization(name, value)) return;
            req.setAttribute(name, value);
        }
        @Override public void removeAttribute(String name) { req.removeAttribute(name); }
    }

    public static class HttpSessionContainer implements AttributeContainer {
        HttpSession ses;
        public HttpSessionContainer(HttpSession session) { ses = session; }
        @Override public Enumeration<String> getAttributeNames() {
            try {
                return ses.getAttributeNames();
            } catch (IllegalStateException e) {
                logger.warn("Tried getAttributeNames() on invalidated session " + ses.getId() + ": " + e.toString());
                return emptyStringEnum;
            }
        }
        @Override public Object getAttribute(String name) {
            try {
                return ses.getAttribute(name);
            } catch (IllegalStateException e) {
                logger.warn("Tried getAttribute(" + name + ") on invalidated session " + ses.getId(), BaseException.filterStackTrace(e));
                return null;
            }
        }
        @Override public void setAttribute(String name, Object value) {
            if (!testSerialization(name, value)) return;

            try {
                ses.setAttribute(name, value);
            } catch (IllegalStateException e) {
                logger.warn("Tried setAttribute(" + name + ", " + value + ") on invalidated session " + ses.getId(), BaseException.filterStackTrace(e));
            }
        }
        @Override public void removeAttribute(String name) {
            try {
                ses.removeAttribute(name);
            } catch (IllegalStateException e) {
                logger.warn("Tried removeAttribute(" + name + ") on invalidated session " + ses.getId() + ": " + e.toString());
            }
        }
    }

    public static class ServletContextContainer implements AttributeContainer {
        ServletContext scxt;
        public ServletContextContainer(ServletContext servletContext) { scxt = servletContext; }
        @Override public Enumeration<String> getAttributeNames() { return scxt.getAttributeNames(); }
        @Override public Object getAttribute(String name) { return scxt.getAttribute(name); }
        @Override public void setAttribute(String name, Object value) { scxt.setAttribute(name, value); }
        @Override public void removeAttribute(String name) { scxt.removeAttribute(name); }
    }

    static final Set<String> keysToIgnore = new HashSet<>(Arrays.asList("javax.servlet.context.tempdir",
            "org.apache.catalina.jsp_classpath", "org.apache.commons.fileupload.servlet.FileCleanerCleanup.FileCleaningTracker"));

    public static class AttributeContainerMap implements Map<String, Object> {
        private AttributeContainer cont;
        public AttributeContainerMap(AttributeContainer container) { cont = container; }
        public int size() { return cont.getAttributeNameList().size(); }
        public boolean isEmpty() { return !cont.getAttributeNames().hasMoreElements(); }
        public boolean containsKey(Object o) {
            if (keysToIgnore.contains(o)) return false;
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (name.equals(o)) return true;
            }
            return false;
        }
        public boolean containsValue(Object o) {
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (keysToIgnore.contains(o)) continue;
                Object attrValue = cont.getAttribute(name);
                if (attrValue == null) { if (o == null) return true; }
                else { if (attrValue.equals(o)) return true; }
            }
            return false;
        }
        public Object get(Object o) { return cont.getAttribute((String) o); }
        public Object put(String s, Object o) {
            Object orig = cont.getAttribute(s);
            cont.setAttribute(s, o);
            return orig;
        }
        public Object remove(Object o) {
            Object orig = cont.getAttribute((String) o);
            cont.removeAttribute((String) o);
            return orig;
        }
        public void putAll(Map<? extends String, ? extends Object> map) {
            // if (map == null) return;
            for (Entry entry : map.entrySet()) {
                cont.setAttribute((String) entry.getKey(), entry.getValue());
            }
        }
        public void clear() {
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (!keysToIgnore.contains(name)) cont.removeAttribute(name);
            }
        }
        public @Nonnull Set<String> keySet() {
            Set<String> ks = new HashSet<>();
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (!keysToIgnore.contains(name)) ks.add(name);
            }
            return ks;
        }
        public @Nonnull Collection<Object> values() {
            List<Object> values = new LinkedList<>();
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (!keysToIgnore.contains(name)) values.add(cont.getAttribute(name));
            }
            return values;
        }
        public @Nonnull Set<Entry<String, Object>> entrySet() {
            Set<Entry<String, Object>> es = new HashSet<>();
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (!keysToIgnore.contains(name)) es.add(new SimpleEntry(name, cont.getAttribute(name)));
            }
            return es;
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            Enumeration<String> attrNames = cont.getAttributeNames();
            while (attrNames.hasMoreElements()) {
                String name = attrNames.nextElement();
                if (sb.length() > 1) sb.append(", ");
                sb.append(name).append(":").append(cont.getAttribute(name));
            }
            sb.append("]");
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    public static class CanonicalizeMap implements Map<String, Object> {
        Map mp;
        boolean supportsNull = true;
        public CanonicalizeMap(Map map) {
            mp = map;
            if (mp instanceof Hashtable) supportsNull = false;
        }
        public int size() { return mp.size(); }
        public boolean isEmpty() { return mp.isEmpty(); }
        public boolean containsKey(Object o) { return !(o == null && !supportsNull) && mp.containsKey(o); }
        public boolean containsValue(Object o) { return mp.containsValue(o); }
        public Object get(Object o) { return (o == null && !supportsNull) ? null : canonicalizeValue(mp.get(o)); }
        public Object put(String k, Object v) { return canonicalizeValue(mp.put(k, v)); }
        public Object remove(Object o) { return (o == null && !supportsNull) ? null : canonicalizeValue(mp.remove(o)); }
        public void putAll(Map<? extends String, ? extends Object> map) { mp.putAll(map); }
        public void clear() { mp.clear(); }
        public @Nonnull Set<String> keySet() { return mp.keySet(); }
        public @Nonnull Collection<Object> values() {
            List<Object> values = new ArrayList<>(mp.size());
            for (Object orig : mp.values()) values.add(canonicalizeValue(orig));
            return values;
        }
        public @Nonnull Set<Entry<String, Object>> entrySet() {
            Set<Entry<String, Object>> es = new HashSet<>();
            for (Object entryObj : mp.entrySet()) {
                Entry entry = (Entry) entryObj;
                es.add(new CanonicalizeEntry(entry.getKey().toString(), entry.getValue()));
            }
            return es;
        }
    }

    private static class CanonicalizeEntry implements Map.Entry<String, Object> {
        protected String key;
        protected Object value;
        CanonicalizeEntry(String key, Object value) { this.key = key; this.value = value; }
        // CanonicalizeEntry(Map.Entry<String, Object> entry) { this.key = entry.getKey(); this.value = entry.getValue(); }
        public String getKey() { return key; }
        public Object getValue() { return canonicalizeValue(value); }
        public Object setValue(Object v) {
            Object orig = value;
            value = v;
            return orig;
        }
    }
}
