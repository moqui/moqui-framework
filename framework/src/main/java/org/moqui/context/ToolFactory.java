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

/** Implement this interface to manage lifecycle and factory for tools initialized and destroyed with Moqui Framework.
 * Implementations must have a public no parameter constructor. */
public interface ToolFactory<V> {
    /** Return a name that the factory will be available under through the ExecutionContextFactory.getToolFactory()
     * method and instances will be available under through the ExecutionContextFactory.getTool() method. */
    default String getName() {
        String className = this.getClass().getSimpleName();
        int tfIndex = className.indexOf("ToolFactory");
        if (tfIndex > 0) className = className.substring(0, tfIndex);
        return className;
    }

    /** Initialize the underlying tool and if the instance is a singleton also the instance. */
    default void init(ExecutionContextFactory ecf) { }

    /** Rarely used, initialize before Moqui Facades are initialized; useful for tools that ResourceReference,
     * ScriptRunner, TemplateRenderer, ServiceRunner, etc implementations depend on. */
    default void preFacadeInit(ExecutionContextFactory ecf) { }

    /** Called by ExecutionContextFactory.getTool() to get an instance object for this tool.
     * May be created for each call or a singleton.
     *
     * @throws IllegalStateException if not initialized
     */
    V getInstance(Object... parameters);

    /** Called on destroy/shutdown of Moqui to destroy (shutdown, close, etc) the underlying tool. */
    default void destroy() { }

    /** Rarely used, like destroy() but runs after the facades are destroyed. */
    default void postFacadeDestroy() { }
}
