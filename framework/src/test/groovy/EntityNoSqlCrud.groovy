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


import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Time
import java.sql.Timestamp

class EntityNoSqlCrud extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EntityNoSqlCrud.class)

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    def "create and find TestNoSqlEntity TEST1"() {
        when:
        long curTime = System.currentTimeMillis()
        ec.entity.makeValue("moqui.test.TestNoSqlEntity")
                .setAll([testId:"TEST1", testMedium:"Test Name", testLong:"Very Long ".repeat(200), testIndicator:"N",
                        testDate:new java.sql.Date(curTime), testDateTime:new Timestamp(curTime),
                        testTime:new Time(curTime), testNumberInteger:Long.MAX_VALUE, testNumberDecimal:BigDecimal.ZERO,
                        testNumberFloat:Double.MAX_VALUE, testCurrencyAmount:1111.12, testCurrencyPrecise:2222.12345])
                .createOrUpdate()
        EntityValue testCheck = ec.entity.find("moqui.test.TestNoSqlEntity").condition("testId", "TEST1").one()

        // logger.warn("testCheck.testTime ${testCheck.testTime} ${testCheck.testTime.getTime()} type ${testCheck.testTime?.class} new Time(curTime) ${new Time(curTime)} ${curTime}")

        then:
        testCheck.testMedium == "Test Name"
        testCheck.testLong.toString().startsWith("Very Long Very Long")
        testCheck.testDate == new java.sql.Date(curTime)
        testCheck.testDateTime == new Timestamp(curTime)
        // compare time strings because object compare with original and truncated long millis are not considered the same, even if the time is the same
        testCheck.testTime.toString() == new Time(curTime).toString()
        testCheck.testNumberInteger == Long.MAX_VALUE
        testCheck.testNumberDecimal == BigDecimal.ZERO
        testCheck.testNumberFloat == Double.MAX_VALUE
        testCheck.testCurrencyAmount == 1111.12
        testCheck.testCurrencyPrecise == 2222.12345
    }

    def "update TestNoSqlEntity TEST1"() {
        when:
        EntityValue testValue = ec.entity.find("moqui.test.TestNoSqlEntity").condition("testId", "TEST1").one()
        testValue.testMedium = "Test Name 2"
        testValue.update()
        EntityValue testCheck = ec.entity.find("moqui.test.TestNoSqlEntity").condition([testId:"TEST1"]).one()

        then:
        testCheck.testMedium == "Test Name 2"
    }

    def "delete TestNoSqlEntity TEST1"() {
        when:
        ec.entity.find("moqui.test.TestNoSqlEntity").condition([testId:"TEST1"]).one().delete()
        EntityValue testCheck = ec.entity.find("moqui.test.TestNoSqlEntity").condition([testId:"TEST1"]).one()

        then:
        testCheck == null
    }

    def "createBulk TestNoSqlEntity"() {
        when:
        long beforeCount = ec.entity.find("moqui.test.TestNoSqlEntity").count()
        int recordCount = 200


        List<EntityValue> createList = new ArrayList<>(recordCount)
        for (int i = 0; i < recordCount; i++) {
            EntityValue newValue = ec.entity.makeValue("moqui.test.TestNoSqlEntity")
            newValue.setAll([testId:"BULK" + i, testMedium:"Test Name ${i}", testNumberInteger:i])
            createList.add(newValue)
        }
        ec.entity.createBulk(createList)

        long afterCount = ec.entity.find("moqui.test.TestNoSqlEntity").count()
        // logger.warn("beforeCount ${beforeCount} recordCount ${recordCount} afterCount ${afterCount}")

        then:
        afterCount == beforeCount + recordCount
    }

    def "ELI find TestNoSqlEntity"() {
        when:
        EntityList partialEl = null
        EntityValue first = null
        try (EntityListIterator eli = ec.entity.find("moqui.test.TestNoSqlEntity")
                .orderBy("-testNumberInteger").iterator()) {


            partialEl = eli.getPartialList(0, 100, false)

            eli.beforeFirst()
            first = eli.next()
        } catch (Exception e) {
            logger.error("partialEl error", e)
        }
        // logger.warn("partialEl.size() ${partialEl.size()} first value ${first}")

        then:
        partialEl?.size() == 100
        first?.testNumberInteger == 199
    }
}
