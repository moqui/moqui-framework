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

import java.io.Serializable;
import java.util.List;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.moqui.Moqui;

@Plugin(name="MoquiLog4jAppender", category="Core", elementType="appender", printObject=true)
public final class MoquiLog4jAppender extends AbstractAppender {

    // private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    // private final Lock readLock = rwLock.readLock();

    protected MoquiLog4jAppender(String name, Filter filter, Layout<? extends Serializable> layout, final boolean ignoreExceptions) {
        super(name, filter, layout, ignoreExceptions);
    }

    @Override
    public void append(LogEvent event) {
        ExecutionContextFactory ecf = Moqui.getExecutionContextFactory();
        // ECF may not yet be initialized
        if (ecf == null) return;
        List<LogEventSubscriber> subscribers = ecf.getLogEventSubscribers();
        int subscribersSize = subscribers.size();
        for (int i = 0; i < subscribersSize; i++) {
            LogEventSubscriber subscriber = subscribers.get(i);
            subscriber.process(event);
        }
        /*
        readLock.lock();
        try {
            final String message = (String) getLayout().toSerializable(event);
            System.out.write(message);
        } catch (Exception e) {
            if (!ignoreExceptions()) { throw new AppenderLoggingException(e); }
        } finally {
            readLock.unlock();
        }
        */
    }

    @PluginFactory
    public static MoquiLog4jAppender createAppender(@PluginAttribute("name") String name,
                                                    @PluginElement("Filter") final Filter filter) {
        // not using Layout config, let subscribers choose: @PluginElement("Layout") Layout<? extends Serializable> layout
        if (name == null) { LOGGER.error("No name provided for MoquiLog4jAppender"); return null; }
        return new MoquiLog4jAppender(name, filter, null, true);
    }
}
