// see http://logback.qos.ch/manual/groovy.html

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.AsyncAppender
import static ch.qos.logback.classic.Level.*

String moquiRuntime = System.getProperty("moqui.runtime", "moqui_logs")

// useful sometimes in dev, commented by default
// scan("30 seconds")

appender("Console", ConsoleAppender) {
    encoder(PatternLayoutEncoder) { pattern = "%highlight(%d{HH:mm:ss.SSS} %5level %12.12thread %38.38logger{38}) %msg%n" }
}

appender("FileLog", RollingFileAppender) {
    encoder(PatternLayoutEncoder) { pattern = "--- %d{yyyy-MM-dd HH:mm:ss.SSS} [%20.20t] %-5level %50.50logger{50}%n %msg%n" }
    rollingPolicy(TimeBasedRollingPolicy) {
        fileNamePattern = "${moquiRuntime}/log/moqui-%d{yyyy-MM-dd}.log"
        maxHistory = 14
        totalSizeCap = ch.qos.logback.core.util.FileSize.valueOf("200MB")
    }
}
appender("FileError", RollingFileAppender) {
    filter(ThresholdFilter) { level = ERROR }
    encoder(PatternLayoutEncoder) { pattern = "--- %d{yyyy-MM-dd HH:mm:ss.SSS} [%20.20t] %-5level %50.50logger{50}%n %msg%n" }
    rollingPolicy(TimeBasedRollingPolicy) {
        fileNamePattern = "${moquiRuntime}/log/moqui-error-%d{yyyy-MM-dd}.log"
        maxHistory = 14
        totalSizeCap = ch.qos.logback.core.util.FileSize.valueOf("200MB")
    }
}

appender("AsyncLog", AsyncAppender) { appenderRef("FileLog") }
appender("AsyncError", AsyncAppender) {
    appenderRef("FileError")
}

logger("ch.qos.logback", WARN)

logger("org.apache", WARN)
logger("org.apache.fop.fo.extensions.svg.SVGElementMapping", ERROR)

logger("freemarker", WARN)
logger("org.elasticsearch", WARN)
logger("org.drools", WARN)
logger("atomikos", WARN)
logger("com.atomikos", WARN)
logger("bitronix", WARN)
logger("cz.vutbr", WARN) // cssbox

// show Groovy generated from XML Actions:
// logger("org.moqui.impl.actions.XmlAction", DEBUG)
// show SQL generated for finds:
// logger("org.moqui.impl.entity.EntityFindBuilder", DEBUG)
// show SQL generated for crud ops:
// logger("org.moqui.impl.entity.EntityQueryBuilder", DEBUG)

logger("org.moqui", INFO)

root(INFO, ["Console", "AsyncLog", "AsyncError"])
