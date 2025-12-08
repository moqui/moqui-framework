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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/**
 * ARCH-004: Extracted from EntityFacadeImpl - handles sequence ID generation
 *
 * Responsible for:
 * - Managing sequence banks for efficient ID generation
 * - Thread-safe sequence number allocation
 * - Database-backed sequence persistence
 */
@CompileStatic
class SequenceGenerator {
    protected final static Logger logger = LoggerFactory.getLogger(SequenceGenerator.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    protected final EntityFacadeImpl efi
    protected final ExecutionContextFactoryImpl ecfi

    /** Sequence name (often entity name) is the key and the value is an array of 2 Longs the first is the next
     * available value and the second is the highest value reserved/cached in the bank. */
    final Cache<String, long[]> entitySequenceBankCache
    protected final ConcurrentHashMap<String, Lock> dbSequenceLocks = new ConcurrentHashMap<String, Lock>()

    protected final String sequencedIdPrefix
    protected final static long defaultBankSize = 50L

    SequenceGenerator(EntityFacadeImpl efi, ExecutionContextFactoryImpl ecfi, String sequencedIdPrefix) {
        this.efi = efi
        this.ecfi = ecfi
        this.sequencedIdPrefix = sequencedIdPrefix
        this.entitySequenceBankCache = ecfi.cacheFacade.getCache("entity.sequence.bank")
    }

    /** Get the current sequence bank for debugging/diagnostics */
    long[] getSequenceBank(String seqName) {
        return (long[]) entitySequenceBankCache.get(seqName)
    }

    /** For testing: set a specific sequence value */
    void tempSetSequencedIdPrimary(String seqName, long nextSeqNum, long bankSize) {
        long[] bank = new long[2]
        bank[0] = nextSeqNum
        bank[1] = nextSeqNum + bankSize
        entitySequenceBankCache.put(seqName, bank)
    }

    /** For testing: reset a sequence */
    void tempResetSequencedIdPrimary(String seqName) {
        entitySequenceBankCache.put(seqName, null)
    }

    /** Get the next primary sequence ID for the given sequence name */
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        try {
            // is the seqName an entityName?
            if (efi.isEntityDefined(seqName)) {
                EntityDefinition ed = efi.getEntityDefinition(seqName)
                if (ed.entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
            }
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        long staggerMaxPrim = staggerMax != null ? staggerMax.longValue() : 0L
        long bankSizePrim = (bankSize != null && bankSize.longValue() > 0) ? bankSize.longValue() : defaultBankSize
        return dbSequencedIdPrimary(seqName, staggerMaxPrim, bankSizePrim)
    }

    /** Get the next primary sequence ID using EntityDefinition settings */
    String sequencedIdPrimaryEd(EntityDefinition ed) {
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        try {
            // is the seqName an entityName?
            if (entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        return dbSequencedIdPrimary(ed.getFullEntityName(), entityInfo.sequencePrimaryStagger, entityInfo.sequenceBankSize)
    }

    protected Lock getDbSequenceLock(String seqName) {
        Lock oldLock, dbSequenceLock = dbSequenceLocks.get(seqName)
        if (dbSequenceLock == null) {
            dbSequenceLock = new ReentrantLock()
            oldLock = dbSequenceLocks.putIfAbsent(seqName, dbSequenceLock)
            if (oldLock != null) return oldLock
        }
        return dbSequenceLock
    }

    protected String dbSequencedIdPrimary(String seqName, long staggerMax, long bankSize) {
        // TODO: find some way to get this running non-synchronized for performance reasons (right now if not
        // TODO:     synchronized the forUpdate won't help if the record doesn't exist yet, causing errors in high
        // TODO:     traffic creates; is it creates only?)

        Lock dbSequenceLock = getDbSequenceLock(seqName)
        dbSequenceLock.lock()

        // NOTE: simple approach with forUpdate, not using the update/select "ethernet" approach used in OFBiz; consider
        // that in the future if there are issues with this approach

        try {
            // first get a bank if we don't have one already
            long[] bank = (long[]) entitySequenceBankCache.get(seqName)
            if (bank == null || bank[0] > bank[1]) {
                if (bank == null) {
                    bank = new long[2]
                    bank[0] = 0
                    bank[1] = -1
                    entitySequenceBankCache.put(seqName, bank)
                }

                ecfi.transactionFacade.runRequireNew(null, "Error getting primary sequenced ID", true, true, {
                    ArtifactExecutionFacadeImpl aefi = ecfi.getEci().artifactExecutionFacade
                    boolean enableAuthz = !aefi.disableAuthz()
                    try {
                        EntityValue svi = efi.find("moqui.entity.SequenceValueItem").condition("seqName", seqName)
                                .useCache(false).forUpdate(true).one()
                        if (svi == null) {
                            svi = efi.makeValue("moqui.entity.SequenceValueItem")
                            svi.set("seqName", seqName)
                            // a new tradition: start sequenced values at one hundred thousand instead of ten thousand
                            bank[0] = 100000L
                            bank[1] = bank[0] + bankSize
                            svi.set("seqNum", bank[1])
                            svi.create()
                        } else {
                            Long lastSeqNum = svi.getLong("seqNum")
                            bank[0] = (lastSeqNum > bank[0] ? lastSeqNum + 1L : bank[0])
                            bank[1] = bank[0] + bankSize
                            svi.set("seqNum", bank[1])
                            svi.update()
                        }
                    } finally {
                        if (enableAuthz) aefi.enableAuthz()
                    }
                })
            }

            long seqNum = bank[0]
            if (staggerMax > 1L) {
                long stagger = Math.round(Math.random() * staggerMax)
                bank[0] = seqNum + stagger
                // NOTE: if bank[0] > bank[1] because of this just leave it and the next time we try to get a sequence
                //     value we'll get one from a new bank
            } else {
                bank[0] = seqNum + 1L
            }

            return sequencedIdPrefix != null ? sequencedIdPrefix + seqNum : seqNum
        } finally {
            dbSequenceLock.unlock()
        }
    }
}
