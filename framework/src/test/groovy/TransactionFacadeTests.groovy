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

import java.sql.Connection
import java.sql.Statement

import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import spock.lang.Shared
import spock.lang.Specification

class TransactionFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "test connection bind to tx"() {
        when:
        boolean beganTransaction = false
        Connection rawCon1, rawCon2, rawCon3
        try {
            beganTransaction = ec.transaction.begin(null)
            Connection conn1 = ec.entity.getConnection("transactional")
            Statement st = conn1.createStatement()
            rawCon1 = conn1.unwrap(Connection.class)
            conn1.close()

            Connection conn2 = ec.entity.getConnection("transactional")
            conn2.createStatement()
            rawCon2 = conn2.unwrap(Connection.class)
            conn2.close()

            Connection conn3 = ec.entity.getConnection("transactional")
            conn3.createStatement()
            rawCon3 = conn3.unwrap(Connection.class)
            conn3.close()
        }  finally {
            ec.transaction.commit(beganTransaction)
        }

        then:
        noExceptionThrown()
        rawCon1 == rawCon2
        rawCon1 == rawCon3
    }

    def "test connection bind to tx atomikos bug"() {
        when:
        boolean beganTransaction = false
        Connection rawCon1, rawCon2, rawCon3
        try {
            beganTransaction = ec.transaction.begin(null)
            Connection conn1 = ec.entity.getConnection("transactional")
            Statement st = conn1.createStatement()

            Connection conn2 = ec.entity.getConnection("transactional")
            conn2.createStatement()
            rawCon2 = conn2.unwrap(Connection.class)
            conn2.close()

            rawCon1 = conn1.unwrap(Connection.class)
            conn1.close()

            Connection conn3 = ec.entity.getConnection("transactional")
            conn3.createStatement()
            rawCon3 = conn3.unwrap(Connection.class)
            conn3.close()
        }  finally {
            ec.transaction.commit(beganTransaction)
        }

        then:
        noExceptionThrown()
        rawCon1 == rawCon2
        rawCon1 == rawCon3
    }

    def "test suspend resume"() {
        when:
        boolean beganTransaction = false
        Connection rawCon1, rawCon2, rawCon3
        try {
            beganTransaction = ec.transaction.begin(null)
            Connection conn1 = ec.entity.getConnection("transactional")
            Statement st = conn1.createStatement()
            rawCon1 = conn1.unwrap(Connection.class)
            conn1.close()
            ec.transaction.suspend()

            ec.transaction.begin(null)
            Connection conn2 = ec.entity.getConnection("transactional")
            conn2.createStatement()
            rawCon2 = conn2.unwrap(Connection.class)
            conn2.close()
            ec.transaction.commit()

            ec.transaction.resume()
            Connection conn3 = ec.entity.getConnection("transactional")
            conn3.createStatement()
            rawCon3 = conn3.unwrap(Connection.class)
            conn3.close()
        }  finally {
            ec.transaction.commit(beganTransaction)
        }

        then:
        noExceptionThrown()
        rawCon1 != rawCon2
        rawCon1 == rawCon3
    }

    def "test atomikos bug"() {
        when:
        // This bug cause runtime add missing not work
        boolean beganTransaction = false
        Connection rawCon1, rawCon2, rawCon3
        try {
            beganTransaction = ec.transaction.begin(null)
            Connection conn1 = ec.entity.getConnection("transactional")
            Statement st = conn1.createStatement()
            rawCon1 = conn1.unwrap(Connection.class)
            conn1.close()
            //A connection close without create statement cause atomikos mark
            //a previouse to delisted XAResource to terminate state.
            Connection conn2 = ec.entity.getConnection("transactional")
            rawCon2 = conn2.unwrap(Connection.class)
            conn2.close()
            // A new connection other than conn1 will return.
            Connection conn3 = ec.entity.getConnection("transactional")
            // Call createStatement cause enlist, will throw Exception.
            conn3.createStatement()
            rawCon3 = conn3.unwrap(Connection.class)
            conn3.close()
        }  finally {
            ec.transaction.commit(beganTransaction)
        }

        then:
        noExceptionThrown()
        rawCon1 == rawCon2
        rawCon1 == rawCon3
    }
}
