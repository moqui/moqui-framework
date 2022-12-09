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
package org.moqui.impl.entity.elastic

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.transaction.Status
import javax.transaction.Synchronization
import javax.transaction.Transaction
import javax.transaction.xa.XAException

/** NOT YET IMPLEMENTED OR USED, may be used for future Elastic Entity transactional behavior (none so far...) */
@CompileStatic
class ElasticSynchronization implements Synchronization {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticSynchronization.class)

    protected ExecutionContextFactoryImpl ecfi
    protected ElasticDatasourceFactory edf

    protected Transaction tx = null


    ElasticSynchronization(ExecutionContextFactoryImpl ecfi, ElasticDatasourceFactory edf) {
        this.ecfi = ecfi
        this.edf = edf
    }

    @Override
    void beforeCompletion() { }

    @Override
    void afterCompletion(int status) {
        /*
        if (status == Status.STATUS_COMMITTED) {
            try {
                // TODO database.commit()
            } catch (Exception e) {
                logger.error("Error in OrientDB commit: ${e.toString()}", e)
                throw new XAException("Error in OrientDB commit: ${e.toString()}")
            } finally {
                // TODO database.close()
            }
        } else {
            try {
                // TODO database.rollback()
            } catch (Exception e) {
                logger.error("Error in OrientDB rollback: ${e.toString()}", e)
                throw new XAException("Error in OrientDB rollback: ${e.toString()}")
            } finally {
                // TODO database.close()
            }
        }
        */
    }
}
