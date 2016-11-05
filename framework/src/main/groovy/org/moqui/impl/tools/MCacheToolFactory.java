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
package org.moqui.impl.tools;

import org.moqui.context.ExecutionContextFactory;
import org.moqui.context.ToolFactory;
import org.moqui.jcache.MCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.CacheManager;

/** A factory for getting a MCacheManager */
public class MCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(MCacheToolFactory.class);
    public final static String TOOL_NAME = "MCache";

    protected ExecutionContextFactory ecf = null;

    private MCacheManager cacheManager = null;

    /** Default empty constructor */
    public MCacheToolFactory() { }

    @Override
    public String getName() { return TOOL_NAME; }
    @Override
    public void init(ExecutionContextFactory ecf) { }
    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        cacheManager = MCacheManager.getMCacheManager();
    }

    @Override
    public CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("MCacheToolFactory not initialized");
        return cacheManager;
    }

    @Override
    public void destroy() { cacheManager.close(); }

    ExecutionContextFactory getEcf() { return ecf; }
}
