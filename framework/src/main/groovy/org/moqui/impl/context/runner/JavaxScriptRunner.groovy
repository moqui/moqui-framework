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
package org.moqui.impl.context.runner

import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ScriptRunner
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.cache.Cache
import javax.script.Bindings
import javax.script.Compilable
import javax.script.CompiledScript
import javax.script.SimpleBindings
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class JavaxScriptRunner implements ScriptRunner {
    protected final static Logger logger = LoggerFactory.getLogger(JavaxScriptRunner.class)

    protected ScriptEngineManager mgr = new ScriptEngineManager();

    protected ExecutionContextFactoryImpl ecfi
    protected Cache scriptLocationCache
    protected String engineName

    JavaxScriptRunner() { this.engineName = "groovy" }
    JavaxScriptRunner(String engineName) { this.engineName = engineName }

    ScriptRunner init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        this.scriptLocationCache = ecfi.cacheFacade.getCache("resource.${engineName}.location")
        return this
    }

    Object run(String location, String method, ExecutionContext ec) {
        // this doesn't support methods, so if passed warn about that
        if (method) logger.warn("Tried to invoke script at [${location}] with method [${method}] through javax.script (JSR-223) runner which does NOT support methods, so it is being ignored.", new BaseException("Script Run Location"))

        ScriptEngine engine = mgr.getEngineByName(engineName)
        return bindAndRun(location, ec, engine, scriptLocationCache)
    }

    void destroy() { }

    static Object bindAndRun(String location, ExecutionContext ec, ScriptEngine engine, Cache scriptLocationCache) {
        Bindings bindings = new SimpleBindings()
        for (Map.Entry ce in ec.getContext().entrySet()) bindings.put((String) ce.getKey(), ce.getValue())

        Object result
        if (engine instanceof Compilable) {
            // cache the CompiledScript
            CompiledScript script = (CompiledScript) scriptLocationCache.get(location)
            if (script == null) {
                script = engine.compile(ec.getResource().getLocationText(location, false))
                scriptLocationCache.put(location, script)
            }
            result = script.eval(bindings)
        } else {
            // cache the script text
            String scriptText = (String) scriptLocationCache.get(location)
            if (scriptText == null) {
                scriptText = ec.getResource().getLocationText(location, false)
                scriptLocationCache.put(location, scriptText)
            }
            result = engine.eval(scriptText, bindings)
        }

        return result
    }
}
