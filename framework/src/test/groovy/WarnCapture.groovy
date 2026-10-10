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

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

/** Collects formatted log lines from one logger for the duration of a closure. */
class WarnCapture extends AbstractAppender {
    final List<String> messages = []

    WarnCapture() {
        super("warn-capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY)
        start()
    }

    @Override
    void append(LogEvent event) {
        def msg = event.message
        messages.add(msg != null ? msg.formattedMessage : "")
    }

    static List<String> of(Class type, Closure work) {
        def core = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(type)
        def app = new WarnCapture()
        core.addAppender(app)
        try {
            work.call()
            return new ArrayList<String>(app.messages)
        } finally {
            core.removeAppender(app)
        }
    }
}
