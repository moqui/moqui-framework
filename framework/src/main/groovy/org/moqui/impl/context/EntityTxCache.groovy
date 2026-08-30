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
package org.moqui.impl.context

import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFindBase
import org.moqui.impl.entity.EntityJavaUtil.FindAugmentInfo
import org.moqui.impl.entity.EntityJavaUtil.WriteMode
import org.moqui.impl.entity.EntityListImpl
import org.moqui.impl.entity.EntityValueBase

/**
 * Per-thread entity write overlay used by {@link TransactionCache} (maps, one JTA TX) and
 * {@link TransactionCacheDb} (H2 working set, many TXs). See framework/plans/LlmSkillLearning.md.
 */
interface EntityTxCache {
    boolean isReadOnly()
    void makeReadOnly()
    void makeWriteThrough()

    /** Returns true if the write was handled (caller should not SQL the world datasource). */
    boolean create(EntityValueBase evb)
    boolean update(EntityValueBase evb)
    boolean delete(EntityValueBase evb)
    boolean refresh(EntityValueBase evb)

    EntityValueBase oneGet(EntityFindBase efb)
    void onePut(EntityValueBase evb, boolean forUpdate)

    EntityListImpl listGet(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByExpanded)
    void listPut(EntityDefinition ed, EntityCondition whereCondition, EntityListImpl eli)

    WriteMode checkUpdateValue(EntityValueBase evb, FindAugmentInfo fai)
    FindAugmentInfo getFindAugmentInfo(String entityName, EntityCondition econd)

    boolean isTxCreate(EntityValueBase evb)
    boolean isKnownLocked(EntityValueBase evb)

    void flushCache(boolean clearRead)
    void close()

    /** If false, EntityValue CUD must not call EntityCache.clearCacheForValue (HOLD overlay). */
    boolean shouldClearEntityCache()
}
