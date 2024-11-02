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
package ars.synchro

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SynchroMasterToolFactory implements ToolFactory<SynchroMaster> {
    protected final static Logger logger = LoggerFactory.getLogger(SynchroMasterToolFactory.class)
    protected SynchroMaster synchroMaster
    protected ExecutionContextFactory ecf = null
    final static String TOOL_NAME = "SynchroMaster"

    /** Default empty constructor */
    SynchroMasterToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf

        // initialize caches list
        def cachesList = []
        def sync_ntts_prop = System.getProperty("synced_entities", "")
        if (sync_ntts_prop)
        {
            sync_ntts_prop.split(",").each {
                def ntt = it.replace("'", "")
                cachesList.add(ntt)
            }
        }

        // initialize SyncMaster with list provided by settings
        synchroMaster = new SynchroMaster(ecf, cachesList)
    }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {}

    @Override
    SynchroMaster getInstance(Object... parameters) {
        if (synchroMaster == null) throw new IllegalStateException("SynchroMaster factory not initialized")
        return synchroMaster
    }

    @Override
    void destroy() {
        if (synchroMaster != null) try {
            logger.info("SynchroMaster deactivated")
        } catch (Throwable t) { logger.error("Error in SynchroMaster closing procedure.", t) }
    }

    ExecutionContextFactory getEcf() { return ecf }
}