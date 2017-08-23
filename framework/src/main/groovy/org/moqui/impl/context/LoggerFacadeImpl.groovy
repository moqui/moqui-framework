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
package org.moqui.impl.context

import org.moqui.context.LoggerFacade
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class LoggerFacadeImpl implements LoggerFacade {
    protected final static Logger logger = LoggerFactory.getLogger(LoggerFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    LoggerFacadeImpl(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi }

    void log(String levelStr, String message, Throwable thrown) {
        int level
        switch (levelStr) {
            case "trace": level = TRACE_INT; break
            case "debug": level = DEBUG_INT; break
            case "info": level = INFO_INT; break
            case "warn": level = WARN_INT; break
            case "error": level = ERROR_INT; break
            case "off": // do nothing
            default: return
        }
        log(level, message, thrown)
    }

    @Override
    void log(int level, String message, Throwable thrown) {
        switch (level) {
            case TRACE_INT: logger.trace(message, thrown); break
            case DEBUG_INT: logger.debug(message, thrown); break
            case INFO_INT: logger.info(message, thrown); break
            case WARN_INT: logger.warn(message, thrown); break
            case ERROR_INT: logger.error(message, thrown); break
            case FATAL_INT: logger.error(message, thrown); break
            case ALL_INT: logger.warn(message, thrown); break
            case OFF_INT: break // do nothing
        }
    }

    void trace(String message) { log(TRACE_INT, message, null) }
    void debug(String message) { log(DEBUG_INT, message, null) }
    void info(String message) { log(INFO_INT, message, null) }
    void warn(String message) { log(WARN_INT, message, null) }
    void error(String message) { log(ERROR_INT, message, null) }

    @Override
    boolean logEnabled(int level) {
        switch (level) {
            case TRACE_INT: return logger.isTraceEnabled()
            case DEBUG_INT: return logger.isDebugEnabled()
            case INFO_INT: return logger.isInfoEnabled()
            case WARN_INT: return logger.isWarnEnabled()
            case ERROR_INT:
            case FATAL_INT: return logger.isErrorEnabled()
            case ALL_INT: return logger.isWarnEnabled()
            case OFF_INT: return false
            default: return false
        }
    }
}
