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
package org.moqui.impl.webapp

import groovy.json.JsonOutput
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Health check servlet for container orchestration (Kubernetes, Docker, etc.)
 *
 * Provides three endpoints:
 * - /health/live   - Liveness probe (is the application running?)
 * - /health/ready  - Readiness probe (can the application accept traffic?)
 * - /health/startup - Startup probe (has initialization completed?)
 *
 * Response format:
 * {
 *   "status": "UP|DOWN",
 *   "checks": {
 *     "database": "UP|DOWN|UNKNOWN",
 *     "cache": "UP|DOWN|UNKNOWN"
 *   },
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 */
class HealthServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(HealthServlet.class)

    private static final String STATUS_UP = "UP"
    private static final String STATUS_DOWN = "DOWN"
    private static final String STATUS_UNKNOWN = "UNKNOWN"

    private volatile boolean startupComplete = false
    private volatile long startupTime = 0

    HealthServlet() { super() }

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        logger.info("HealthServlet initialized")
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = request.getPathInfo() ?: ""

        response.setContentType("application/json")
        response.setCharacterEncoding("UTF-8")

        // No caching for health endpoints
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.setHeader("Pragma", "no-cache")

        Map<String, Object> result

        switch (pathInfo) {
            case "/live":
                result = checkLiveness(request)
                break
            case "/ready":
                result = checkReadiness(request)
                break
            case "/startup":
                result = checkStartup(request)
                break
            case "":
            case "/":
                // Default to readiness check (most comprehensive)
                result = checkReadiness(request)
                break
            default:
                response.setStatus(HttpServletResponse.SC_NOT_FOUND)
                result = [status: STATUS_DOWN, error: "Unknown health endpoint: ${pathInfo}"]
        }

        // Set HTTP status based on health status
        String status = result.status as String
        if (STATUS_UP.equals(status)) {
            response.setStatus(HttpServletResponse.SC_OK)
        } else {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
        }

        response.getWriter().write(JsonOutput.toJson(result))
    }

    /**
     * Liveness check - is the application process alive?
     * This should be lightweight and only check if the JVM is responsive.
     */
    private Map<String, Object> checkLiveness(HttpServletRequest request) {
        ExecutionContextFactoryImpl ecfi = getEcfi(request)

        // Basic liveness: is the ECF present and not destroyed?
        boolean alive = ecfi != null && !ecfi.isDestroyed()

        return [
            status: alive ? STATUS_UP : STATUS_DOWN,
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        ]
    }

    /**
     * Readiness check - can the application accept traffic?
     * Checks database connectivity and other critical dependencies.
     */
    private Map<String, Object> checkReadiness(HttpServletRequest request) {
        ExecutionContextFactoryImpl ecfi = getEcfi(request)

        Map<String, String> checks = [:]
        boolean allHealthy = true

        // Check ECF
        if (ecfi == null || ecfi.isDestroyed()) {
            return [
                status: STATUS_DOWN,
                checks: [ecf: STATUS_DOWN],
                timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
            ]
        }

        // Check database connectivity
        try {
            def entityFacade = ecfi.getEntity()
            if (entityFacade != null) {
                // Try to execute a simple query to verify database connectivity
                // Using count on a system table that should always exist
                def count = entityFacade.find("moqui.basic.Enumeration").count()
                checks.database = STATUS_UP
            } else {
                checks.database = STATUS_DOWN
                allHealthy = false
            }
        } catch (Exception e) {
            logger.warn("Database health check failed: ${e.message}")
            checks.database = STATUS_DOWN
            allHealthy = false
        }

        // Check cache
        try {
            def cacheFacade = ecfi.getCache()
            if (cacheFacade != null) {
                checks.cache = STATUS_UP
            } else {
                checks.cache = STATUS_UNKNOWN
            }
        } catch (Exception e) {
            logger.warn("Cache health check failed: ${e.message}")
            checks.cache = STATUS_DOWN
        }

        // Check transaction manager
        try {
            def txFacade = ecfi.getTransaction()
            if (txFacade != null) {
                checks.transactions = STATUS_UP
            } else {
                checks.transactions = STATUS_DOWN
                allHealthy = false
            }
        } catch (Exception e) {
            logger.warn("Transaction health check failed: ${e.message}")
            checks.transactions = STATUS_DOWN
            allHealthy = false
        }

        return [
            status: allHealthy ? STATUS_UP : STATUS_DOWN,
            checks: checks,
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        ]
    }

    /**
     * Startup check - has the application completed initialization?
     * Used during container startup to determine when the app is ready.
     */
    private Map<String, Object> checkStartup(HttpServletRequest request) {
        ExecutionContextFactoryImpl ecfi = getEcfi(request)

        // ECF being present and not destroyed indicates startup is complete
        if (ecfi != null && !ecfi.isDestroyed()) {
            if (!startupComplete) {
                startupComplete = true
                startupTime = System.currentTimeMillis()
                logger.info("Startup probe succeeded - application initialization complete")
            }

            return [
                status: STATUS_UP,
                startupTime: startupTime,
                timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
            ]
        }

        return [
            status: STATUS_DOWN,
            message: "Application still initializing",
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        ]
    }

    private ExecutionContextFactoryImpl getEcfi(HttpServletRequest request) {
        return (ExecutionContextFactoryImpl) getServletContext()?.getAttribute("executionContextFactory")
    }
}
