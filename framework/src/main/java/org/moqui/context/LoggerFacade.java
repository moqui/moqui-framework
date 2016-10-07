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
package org.moqui.context;

/** For trace, error, etc logging to the console, files, etc. */
public interface LoggerFacade {
    /** Log level copied from org.apache.logging.log4j.spi.StandardLevel to avoid requiring that on the classpath. */
    int	OFF_INT = 0;
    int	FATAL_INT = 100;
    int	ERROR_INT = 200;
    int	WARN_INT = 300;
    int	INFO_INT = 400;
    int	DEBUG_INT = 500;
    int	TRACE_INT = 600;
    int	ALL_INT = 2147483647;

    /** Log a message and/or Throwable error at the given level.
     *
     * This is meant to be used for scripts, xml-actions, etc.
     *
     * In Java or Groovy classes it is better to use SLF4J directly, with something like:
     * <code>
     * public class Wombat {
     *   final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Wombat.class);
     *
     *   public void setTemperature(Integer temperature) {
     *     Integer oldT = t;
     *     Integer t = temperature;
     *     logger.debug("Temperature set to {}. Old temperature was {}.", t, oldT);
     *     if(temperature.intValue() &gt; 50) {
     *       logger.info("Temperature has risen above 50 degrees.");
     *     }
     *   }
     * }
     * </code>
     *
     * @param level The logging level. Options should come from org.apache.log4j.Level.  
     * @param message The message text to log. If contains ${} syntax will be expanded from the current context.
     * @param thrown Throwable with stack trace, etc to be logged along with the message.
     */
    void log(int level, String message, Throwable thrown);

    void trace(String message);
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);

    /** Is the given logging level enabled? */
    boolean logEnabled(int level);
}
