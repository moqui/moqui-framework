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
package org.moqui.impl

import groovy.transform.CompileStatic
import org.apache.http.HttpEntity
import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils

import javax.servlet.ServletRequest
import javax.servlet.http.HttpSession
import javax.servlet.ServletContext

import org.owasp.esapi.reference.DefaultEncoder
import org.owasp.esapi.Encoder
import org.owasp.esapi.Validator
import org.owasp.esapi.reference.DefaultValidator

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.Charset

@CompileStatic
class StupidWebUtilities {
    protected final static Logger logger = LoggerFactory.getLogger(StupidUtilities.class)

    static final Encoder defaultWebEncoder = DefaultEncoder.getInstance()
    static final Validator defaultWebValidator = DefaultValidator.getInstance()

    static final char tildeChar = '~' as char
    public static Map<String, Object> getPathInfoParameterMap(String pathInfoStr) {
        if (pathInfoStr == null || pathInfoStr.length() == 0) return null

        Map<String, Object> paramMap = (Map<String, Object>) null

        // add in all path info parameters /~name1=value1/~name2=value2/
        if (pathInfoStr) {
            String[] pathElements = pathInfoStr.split("/")
            for (int i = 0; i < pathElements.size(); i++) {
                String element = (String) pathElements[i]
                int equalsIndex = element.indexOf("=")
                if (element.length() > 0 && element.charAt(0) == tildeChar && equalsIndex > 0) {
                    String name = element.substring(1, equalsIndex)
                    String value = element.substring(equalsIndex + 1)
                    // NOTE: currently ignoring existing values, likely won't be any: Object curValue = paramMap.get(name)
                    if (paramMap == null) paramMap = new HashMap<String, Object>()
                    paramMap.put(name, value)
                }
            }
        }

        return paramMap
    }

    static class SimpleEntry implements Map.Entry<String, Object> {
        protected String key
        protected Object value
        SimpleEntry(String key, Object value) { this.key = key; this.value = value; }
        String getKey() { return key }
        Object getValue() { return value }
        Object setValue(Object v) { Object orig = value; value = v; return orig; }
    }
    
    static class RequestAttributeMap implements Map<String, Object> {
        protected ServletRequest req
        RequestAttributeMap(ServletRequest request) { req = request }
        int size() { return req.getAttributeNames().toList().size() }
        boolean isEmpty() { return req.getAttributeNames().toList().size() > 0 }
        boolean containsKey(Object o) { return req.getAttributeNames().toList().contains(o) }
        boolean containsValue(Object o) {
            for (String name in req.getAttributeNames()) if (req.getAttribute(name) == o) return true
            return false
        }
        Object get(Object o) { return req.getAttribute((String) o) }
        Object put(String s, Object o) { Object orig = req.getAttribute(s); req.setAttribute(s, o); return orig; }
        Object remove(Object o) { Object orig = req.getAttribute((String) o); req.removeAttribute((String) o); return orig; }
        void putAll(Map<? extends String, ? extends Object> map) {
            if (!map) return
            for (Map.Entry entry in map.entrySet()) {
                req.setAttribute((String) entry.getKey(), entry.getValue())
            }
        }
        void clear() { for (String name in req.getAttributeNames()) req.removeAttribute(name) }
        Set<String> keySet() { Set<String> ks = new HashSet<String>(); ks.addAll(req.getAttributeNames().toList()); return ks; }
        Collection<Object> values() { List values = new LinkedList(); for (String name in req.getAttributeNames()) values.add(req.getAttribute(name)); return values; }
        Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> es = new HashSet<Map.Entry<String,Object>>()
            for (String name in req.getAttributeNames()) es.add(new SimpleEntry(name, req.getAttribute(name)))
            return es
        }
        @Override
        String toString() {
            StringBuilder sb = new StringBuilder("[")
            for (String name in req.getAttributeNames()) {
                if (sb.size() > 1) sb.append(", ")
                sb.append(name).append(":").append(req.getAttribute(name))
            }
            sb.append("]")
            return sb.toString()
        }
    }

    static class SessionAttributeMap implements Map<String, Object> {
        protected HttpSession ses
        SessionAttributeMap(HttpSession session) { ses = session }
        int size() { return ses.getAttributeNames().toList().size() }
        boolean isEmpty() { return ses.getAttributeNames().toList().size() > 0 }
        boolean containsKey(Object o) { return ses.getAttributeNames().toList().contains(o) }
        boolean containsValue(Object o) {
            for (String name in ses.getAttributeNames()) if (ses.getAttribute(name) == o) return true
            return false
        }
        Object get(Object o) { return ses.getAttribute((String) o) }
        Object put(String s, Object o) { Object orig = ses.getAttribute(s); ses.setAttribute(s, o); return orig; }
        Object remove(Object o) { Object orig = ses.getAttribute((String) o); ses.removeAttribute((String) o); return orig; }
        void putAll(Map<? extends String, ? extends Object> map) {
            if (!map) return
            for (Map.Entry entry in map.entrySet()) ses.setAttribute((String) entry.getKey(), entry.getValue())
        }
        void clear() { for (String name in ses.getAttributeNames()) ses.removeAttribute(name) }
        Set<String> keySet() { Set<String> ks = new HashSet<String>(); ks.addAll(ses.getAttributeNames().toList()); return ks; }
        Collection<Object> values() { List values = new LinkedList(); for (String name in ses.getAttributeNames()) values.add(ses.getAttribute(name)); return values; }
        Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> es = new HashSet<Map.Entry<String,Object>>()
            for (String name in ses.getAttributeNames()) es.add(new SimpleEntry(name, ses.getAttribute(name)))
            return es
        }
        String toString() {
            StringBuilder sb = new StringBuilder("[")
            for (String name in ses.getAttributeNames()) {
                if (sb.size() > 1) sb.append(", ")
                sb.append(name).append(":").append(ses.getAttribute(name))
            }
            sb.append("]")
            return sb.toString()
        }
    }

    static class ServletContextAttributeMap implements Map<String, Object> {
        protected static final Set<String> keysToIgnore =
                ["javax.servlet.context.tempdir", "org.apache.catalina.jsp_classpath",
                "org.apache.commons.fileupload.servlet.FileCleanerCleanup.FileCleaningTracker"] as Set<String>
        protected ServletContext sc
        ServletContextAttributeMap(ServletContext servletContext) { sc = servletContext }
        int size() { return sc.getAttributeNames().toList().size() }
        boolean isEmpty() { return sc.getAttributeNames().toList().size() > 0 }
        boolean containsKey(Object o) { return keysToIgnore.contains(o) ? false : sc.getAttributeNames().toList().contains(o) }
        boolean containsValue(Object o) {
            for (String name in sc.getAttributeNames()) if (!keysToIgnore.contains(name) && sc.getAttribute(name) == o) return true
            return false
        }
        Object get(Object o) { return keysToIgnore.contains(o) ? null : sc.getAttribute((String) o) }
        Object put(String s, Object o) { Object orig = sc.getAttribute(s); sc.setAttribute(s, o); return orig; }
        Object remove(Object o) { Object orig = sc.getAttribute((String) o); sc.removeAttribute((String) o); return orig; }
        void putAll(Map<? extends String, ? extends Object> map) {
            if (!map) return
            for (Map.Entry entry in map.entrySet()) sc.setAttribute((String) entry.getKey(), entry.getValue())
        }
        void clear() { for (String name in sc.getAttributeNames()) if (!keysToIgnore.contains(name)) sc.removeAttribute(name) }
        Set<String> keySet() {
            Set<String> ks = new HashSet<String>();
            for (String name in sc.getAttributeNames()) if (!keysToIgnore.contains(name)) ks.add(name);
            return ks;
        }
        Collection<Object> values() {
            List values = new LinkedList();
            for (String name in sc.getAttributeNames()) if (!keysToIgnore.contains(name)) values.add(sc.getAttribute(name));
            return values;
        }
        Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> es = new HashSet<Map.Entry<String,Object>>()
            for (String name in sc.getAttributeNames())
                if (!keysToIgnore.contains(name)) es.add(new SimpleEntry(name, sc.getAttribute(name)))
            return es
        }
        String toString() {
            StringBuilder sb = new StringBuilder("[")
            for (String name in sc.getAttributeNames()) {
                if (sb.size() > 1) sb.append(", ")
                sb.append(name).append(":").append(sc.getAttribute(name))
            }
            sb.append("]")
            return sb.toString()
        }
    }
    
    static class CanonicalizeMap implements Map<String, Object> {
        protected Map mp
        protected boolean supportsNull = true
        CanonicalizeMap(Map map) {
            mp = map
            if (mp instanceof Hashtable) supportsNull = false
        }
        int size() { return mp.size() }
        boolean isEmpty() { return mp.isEmpty() }
        boolean containsKey(Object o) { return (o == null && !supportsNull) ? false : mp.containsKey(o) }
        boolean containsValue(Object o) { return mp.containsValue(o) }
        Object get(Object o) {
            // NOTE: in spite of warnings class reference to StupidWebUtilities.canonicalizeValue is necessary or Groovy blows up
            return (o == null && !supportsNull) ? null : StupidWebUtilities.canonicalizeValue(mp.get(o))
        }
        Object put(String k, Object v) { return StupidWebUtilities.canonicalizeValue(mp.put(k, v)) }
        Object remove(Object o) {
            return (o == null && !supportsNull) ? null : StupidWebUtilities.canonicalizeValue(mp.remove(o))
        }
        void putAll(Map<? extends String, ? extends Object> map) { if (map) mp.putAll(map) }
        void clear() { mp.clear() }
        Set<String> keySet() { return mp.keySet() }
        Collection<Object> values() {
            List<Object> values = new ArrayList<Object>(mp.size())
            for (Object orig in mp.values()) values.add(StupidWebUtilities.canonicalizeValue(orig))
            return values
        }
        Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> es = new HashSet<Map.Entry<String, Object>>()
            for (Map.Entry<String, Object> entry in mp.entrySet()) es.add(new CanonicalizeEntry(entry))
            return es
        }
    }

    static class CanonicalizeEntry implements Map.Entry<String, Object> {
        protected String key
        protected Object value
        CanonicalizeEntry(String key, Object value) { this.key = key; this.value = value; }
        CanonicalizeEntry(Map.Entry<String, Object> entry) { this.key = entry.getKey(); this.value = entry.getValue(); }
        String getKey() { return key }
        Object getValue() { return StupidWebUtilities.canonicalizeValue(value) }
        Object setValue(Object v) { Object orig = value; value = v; return orig; }
    }

    static Object canonicalizeValue(Object orig) {
        Object canVal = orig
        if (orig instanceof List || orig instanceof String[] || orig instanceof Object[]) {
            List lst = orig as List
            if (lst.size() == 1) {
                canVal = lst.get(0)
            } else if (lst.size() > 1) {
                List newList = new ArrayList(lst.size())
                canVal = newList
                for (Object obj in lst) {
                    if (obj instanceof CharSequence) {
                        newList.add(defaultWebEncoder.canonicalize(obj.toString(), false))
                    } else {
                        newList.add(obj)
                    }
                }
            }
        }
        // catch strings or lists with a single string in them unwrapped above
        if (canVal instanceof CharSequence) canVal = defaultWebEncoder.canonicalize(canVal.toString(), false)
        return canVal
    }

    static String simpleHttpStringRequest(String location, String requestBody, String contentType) {
        if (!contentType) contentType = "text/plain"
        String resultString = ""
        CloseableHttpClient httpClient = HttpClients.createDefault()
        try {
            HttpPost httpPost = new HttpPost(location)
            if (requestBody) {
                StringEntity requestEntity = new StringEntity(requestBody, ContentType.create(contentType, "UTF-8"))
                httpPost.setEntity(requestEntity)
                httpPost.setHeader("Content-Type", contentType)
            }

            CloseableHttpResponse response = httpClient.execute(httpPost)
            try {
                HttpEntity entity = response.getEntity()
                resultString = StupidUtilities.toStringCleanBom(EntityUtils.toByteArray(entity))
            } finally {
                response.close()
            }
        } finally {
            httpClient.close()
        }

        return resultString
    }

    static String simpleHttpMapRequest(String location, Map requestMap) {
        String resultString = ""
        CloseableHttpClient httpClient = HttpClients.createDefault()
        try {
            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>()
            if (requestMap) for (Map.Entry requestEntry in requestMap.entrySet())
                nameValuePairs.add(new BasicNameValuePair(requestEntry.key as String, requestEntry.value as String))

            HttpPost httpPost = new HttpPost(location)
            if (nameValuePairs) httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs, Charset.forName("UTF-8")))

            CloseableHttpResponse response = httpClient.execute(httpPost)
            try {
                HttpEntity entity = response.getEntity()
                resultString = StupidUtilities.toStringCleanBom(EntityUtils.toByteArray(entity))
            } finally {
                response.close()
            }
        } finally {
            httpClient.close()
        }

        return resultString
    }
}
