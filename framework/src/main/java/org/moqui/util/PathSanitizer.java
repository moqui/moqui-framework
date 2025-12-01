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

import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to prevent path traversal attacks (CWE-22, CWE-23).
 *
 * SEC-010: Validates file paths to ensure they don't escape allowed directories
 * using directory traversal sequences like "../".
 */
public class PathSanitizer {
    private static final Logger logger = LoggerFactory.getLogger(PathSanitizer.class);

    /**
     * Validate that a path does not contain path traversal sequences.
     *
     * @param path The path to check
     * @return true if the path is safe, false if it contains traversal sequences
     */
    public static boolean isPathSafe(String path) {
        if (path == null) return false;

        // Check for obvious path traversal patterns
        if (path.contains("..")) {
            return false;
        }

        // Check for null bytes (can be used to bypass filters)
        if (path.contains("\0")) {
            return false;
        }

        // Check for URL-encoded traversal attempts
        String decoded = path.replace("%2e", ".").replace("%2E", ".")
                            .replace("%2f", "/").replace("%2F", "/")
                            .replace("%5c", "\\").replace("%5C", "\\");
        if (decoded.contains("..")) {
            return false;
        }

        return true;
    }

    /**
     * Validate that a resolved path stays within the base directory.
     * Uses canonical path comparison to handle symlinks and path normalization.
     *
     * @param baseDir The allowed base directory
     * @param requestedPath The user-requested path (can be relative or absolute)
     * @return The validated canonical path
     * @throws SecurityException if path traversal is detected
     */
    public static String validatePath(String baseDir, String requestedPath) throws SecurityException {
        if (baseDir == null || requestedPath == null) {
            throw new SecurityException("Base directory and path cannot be null");
        }

        try {
            File base = new File(baseDir).getCanonicalFile();
            File requested;

            // Handle absolute vs relative paths
            if (requestedPath.startsWith("/") ||
                (requestedPath.length() > 1 && requestedPath.charAt(1) == ':')) {
                // Absolute path
                requested = new File(requestedPath).getCanonicalFile();
            } else {
                // Relative path - resolve against base
                requested = new File(base, requestedPath).getCanonicalFile();
            }

            String basePath = base.getPath();
            String resolvedPath = requested.getPath();

            // Ensure the resolved path is under the base directory
            if (!resolvedPath.startsWith(basePath)) {
                logger.warn("Path traversal attempt detected: baseDir={}, requestedPath={}, resolvedPath={}",
                           baseDir, requestedPath, resolvedPath);
                throw new SecurityException("Path traversal detected: path escapes base directory");
            }

            return resolvedPath;

        } catch (IOException e) {
            throw new SecurityException("Error validating path: " + e.getMessage(), e);
        }
    }

    /**
     * Sanitize a filename by removing dangerous characters.
     *
     * @param filename The filename to sanitize
     * @return A safe filename
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null) return null;

        // Remove path separators and null bytes
        String safe = filename.replace("/", "_")
                             .replace("\\", "_")
                             .replace("\0", "")
                             .replace(":", "_");

        // Remove leading/trailing whitespace and dots
        safe = safe.trim();
        while (safe.startsWith(".")) safe = safe.substring(1);

        return safe;
    }

    /**
     * Validate that a relative path does not attempt directory traversal.
     * Does not require a base directory - just checks the path itself.
     *
     * @param path The path to validate
     * @return The normalized path if safe
     * @throws SecurityException if path traversal is detected
     */
    public static String validateRelativePath(String path) throws SecurityException {
        if (path == null) {
            throw new SecurityException("Path cannot be null");
        }

        if (!isPathSafe(path)) {
            logger.warn("Unsafe path detected: {}", path);
            throw new SecurityException("Path traversal sequence detected in: " + path);
        }

        // Normalize the path
        Path normalized = Paths.get(path).normalize();
        String normalizedStr = normalized.toString();

        // After normalization, check again for escape attempts
        if (normalizedStr.startsWith("..") || normalizedStr.contains("/..") || normalizedStr.contains("\\..")) {
            logger.warn("Path traversal after normalization: original={}, normalized={}", path, normalizedStr);
            throw new SecurityException("Path traversal detected after normalization");
        }

        return normalizedStr;
    }
}
