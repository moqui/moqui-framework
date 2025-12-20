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

import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionManagerImple
import com.arjuna.ats.internal.jta.transaction.arjunacore.UserTransactionImple
import jakarta.transaction.TransactionManager
import jakarta.transaction.UserTransaction
import jakarta.transaction.Status
import spock.lang.*

class NarayanaTransactionTests extends Specification {

    def "Narayana TransactionManager should initialize"() {
        when: "Initializing Narayana TransactionManager"
        TransactionManager tm = new TransactionManagerImple()
        UserTransaction ut = new UserTransactionImple()

        then: "Both should be non-null"
        tm != null
        ut != null
    }

    def "Narayana should begin and commit transaction"() {
        given: "Narayana TransactionManager"
        TransactionManager tm = new TransactionManagerImple()
        UserTransaction ut = new UserTransactionImple()

        when: "Beginning a transaction"
        ut.begin()

        then: "Transaction status should be ACTIVE"
        ut.getStatus() == Status.STATUS_ACTIVE

        when: "Committing the transaction"
        ut.commit()

        then: "Transaction status should be NO_TRANSACTION"
        ut.getStatus() == Status.STATUS_NO_TRANSACTION
    }

    def "Narayana should begin and rollback transaction"() {
        given: "Narayana TransactionManager"
        TransactionManager tm = new TransactionManagerImple()
        UserTransaction ut = new UserTransactionImple()

        when: "Beginning a transaction"
        ut.begin()

        then: "Transaction status should be ACTIVE"
        ut.getStatus() == Status.STATUS_ACTIVE

        when: "Rolling back the transaction"
        ut.rollback()

        then: "Transaction status should be NO_TRANSACTION"
        ut.getStatus() == Status.STATUS_NO_TRANSACTION
    }

    def "Narayana should support nested transaction suspend/resume"() {
        given: "Narayana TransactionManager"
        TransactionManager tm = new TransactionManagerImple()
        UserTransaction ut = new UserTransactionImple()

        when: "Beginning first transaction"
        ut.begin()
        def tx1 = tm.getTransaction()

        then: "First transaction is active"
        tx1 != null
        ut.getStatus() == Status.STATUS_ACTIVE

        when: "Suspending first transaction and beginning second"
        def suspended = tm.suspend()
        ut.begin()
        def tx2 = tm.getTransaction()

        then: "Second transaction is active and different from first"
        tx2 != null
        tx2 != suspended
        ut.getStatus() == Status.STATUS_ACTIVE

        when: "Committing second and resuming first"
        ut.commit()
        tm.resume(suspended)

        then: "First transaction is active again"
        ut.getStatus() == Status.STATUS_ACTIVE
        tm.getTransaction() == suspended

        cleanup:
        try { ut.rollback() } catch (Exception e) {}
    }
}
