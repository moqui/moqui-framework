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

import java.io.*;
import java.util.*;

/**
 * Utility class for safe deserialization to prevent CWE-502 (Deserialization of Untrusted Data).
 * Uses Java's ObjectInputFilter to whitelist safe classes before deserialization.
 *
 * SEC-009: Mitigates insecure deserialization vulnerabilities by restricting
 * which classes can be deserialized.
 */
public class SafeDeserialization {

    // Whitelist of safe package prefixes allowed for deserialization
    private static final Set<String> SAFE_PACKAGES = new HashSet<>(Arrays.asList(
        "java.lang.",
        "java.util.",
        "java.math.",
        "java.time.",
        "java.sql.",
        "java.io.Serializable",
        "java.net.URI",
        "java.net.URL",
        "javax.sql.",
        "org.moqui.",
        "groovy.lang.",
        "groovy.util."
    ));

    // Explicitly blocked dangerous classes
    private static final Set<String> BLOCKED_CLASSES = new HashSet<>(Arrays.asList(
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.reflect.Method",
        "java.lang.reflect.Constructor",
        "javax.script.ScriptEngine",
        "javax.naming.InitialContext",
        "org.apache.commons.collections.functors.",
        "org.apache.commons.collections4.functors.",
        "org.apache.xalan.",
        "com.sun.org.apache.xalan.",
        "org.codehaus.groovy.runtime.",
        "org.springframework.beans.factory."
    ));

    /**
     * Creates a safe ObjectInputStream with class filtering enabled.
     * This prevents deserialization of dangerous classes that could lead to RCE.
     *
     * @param inputStream The underlying input stream
     * @return A filtered ObjectInputStream
     * @throws IOException if stream creation fails
     */
    public static ObjectInputStream createSafeObjectInputStream(InputStream inputStream) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(inputStream);
        ois.setObjectInputFilter(SafeDeserialization::filterCheck);
        return ois;
    }

    /**
     * ObjectInputFilter implementation that checks classes against whitelist.
     * Rejects any class not in the safe packages or explicitly blocked.
     */
    private static ObjectInputFilter.Status filterCheck(ObjectInputFilter.FilterInfo filterInfo) {
        Class<?> clazz = filterInfo.serialClass();

        // Allow null (for arrays and primitives)
        if (clazz == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }

        String className = clazz.getName();

        // Check blocked classes first
        for (String blocked : BLOCKED_CLASSES) {
            if (className.startsWith(blocked)) {
                return ObjectInputFilter.Status.REJECTED;
            }
        }

        // Allow primitives and primitive arrays
        if (clazz.isPrimitive() || clazz.isArray()) {
            Class<?> componentType = clazz.isArray() ? clazz.getComponentType() : clazz;
            if (componentType.isPrimitive()) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            // For object arrays, check the component type
            if (clazz.isArray()) {
                className = componentType.getName();
            }
        }

        // Check if class is in safe packages
        for (String safePackage : SAFE_PACKAGES) {
            if (className.startsWith(safePackage) || className.equals(safePackage)) {
                return ObjectInputFilter.Status.ALLOWED;
            }
        }

        // Reject unknown classes by default (fail-safe)
        return ObjectInputFilter.Status.REJECTED;
    }

    /**
     * Safely deserialize an object from a byte array.
     *
     * @param bytes The serialized bytes
     * @return The deserialized object, or null if deserialization fails
     */
    public static Object safeDeserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = createSafeObjectInputStream(bais)) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Add additional safe packages at runtime (e.g., for plugins).
     * Should be called during application initialization.
     *
     * @param packagePrefix The package prefix to add (e.g., "com.mycompany.")
     */
    public static void addSafePackage(String packagePrefix) {
        if (packagePrefix != null && !packagePrefix.isEmpty()) {
            SAFE_PACKAGES.add(packagePrefix);
        }
    }
}
