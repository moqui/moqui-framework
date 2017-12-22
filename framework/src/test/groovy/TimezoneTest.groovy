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
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Date
import java.sql.Time
import java.sql.Timestamp

class TimezoneTest extends Specification {
    @Shared
    ExecutionContext ec

    @Shared
    String oldTz

    @Shared
    String oldDbTz

    def setupSpec() {
        // init the framework, get the ec
        oldTz = System.setProperty("default_time_zone", 'Pacific/Kiritimati')
        oldDbTz = System.setProperty("database_time_zone", 'US/Samoa')

        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
        if (oldTz == null) {
            System.clearProperty("default_time_zone")
        } else {
            System.setProperty("default_time_zone", oldTz)
        }
        if (oldDbTz == null) {
            System.clearProperty("database_time_zone")
        } else {
            System.setProperty("database_time_zone", oldDbTz)
        }
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    def "test timestamp with timezone"() {
        given:
        Timestamp ts = new Timestamp(0)

        when:
        EntityValue testValue = ec.entity.makeValue('moqui.test.TestEntity')

        testValue.set('testId', 'TIMEZONE1')
        testValue.set('testDateTime', ts)

        testValue.create()
        testValue.refresh()
        testValue.delete()

        then:
        testValue.testDateTime.time == ts.time


    }

    def "test time with timezone"() {
        given:
        Time t = new Time(0)

        when:
        EntityValue testValue = ec.entity.makeValue('moqui.test.TestEntity')

        testValue.set('testId', 'TIMEZONE1')
        testValue.set('testTime', t)

        testValue.create()
        testValue.refresh()
        testValue.delete()

        then:
        testValue.testTime.toLocalTime() == t.toLocalTime()

    }

    def "test date with timezone"() {
        given:
        Date d = new Date(0)

        when:
        EntityValue testValue = ec.entity.makeValue('moqui.test.TestEntity')

        testValue.set('testId', 'TIMEZONE1')
        testValue.set('testDate', d)

        testValue.create()
        testValue.refresh()
        testValue.delete()

        then:
        testValue.testDate.toLocalDate() == d.toLocalDate()

    }
}
