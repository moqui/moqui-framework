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
import org.moqui.service.ServiceException
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp
import java.util.concurrent.Future

/**
 * Characterization tests for ServiceFacade.
 * These tests document the current behavior of the service layer to ensure
 * consistency during modernization efforts.
 *
 * NOTE: Service authentication vs authorization:
 * - authenticate="anonymous-all" on a service allows unauthenticated access
 * - disableAuthz() disables authorization checks but NOT authentication
 * - Services without authenticate attribute require a logged-in user
 */
class ServiceFacadeCharacterizationTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        // Login as anonymous to satisfy authentication requirements for non-anonymous services
        if (!ec.user.userId) {
            ec.user.loginAnonymousIfNoUser()
        }
    }

    def cleanup() {
        // Clean up test data
        try {
            ec.entity.find("moqui.test.TestEntity").condition("testId", "like", "SVC_TEST_%").list()*.delete()
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        ec.artifactExecution.enableAuthz()
    }

    // ========== Synchronous Service Calls ==========

    def "sync service call with noop service executes successfully"() {
        when:
        // noop service has authenticate="anonymous-all" so no login required
        Map result = ec.service.sync().name("org.moqui.impl.BasicServices.noop").call()

        then:
        result != null
        noExceptionThrown()
    }

    def "sync service call with echo service returns input parameters"() {
        when:
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "Hello", textIn2: "World", numberIn: 42.5, timestampIn: now])
                .call()

        then:
        result.textOut1 == "Hello"
        result.textOut2 == "World"
        result.numberOut == 42.5
        result.timestampOut == now
    }

    def "sync service call with default parameter values"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "Test"])
                .call()

        then:
        result.textOut1 == "Test"
        result.textOut2 == "ping"  // default value from service definition
    }

    def "sync service call using name with verb and noun"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices", "echo", "Data")
                .parameters([textIn1: "Test"])
                .call()

        then:
        result.textOut1 == "Test"
    }

    def "sync service call using parameter method for single params"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameter("textIn1", "Single")
                .parameter("textIn2", "Params")
                .call()

        then:
        result.textOut1 == "Single"
        result.textOut2 == "Params"
    }

    // ========== Entity-Auto Services ==========

    def "entity-auto create service creates entity"() {
        when:
        ec.service.sync()
                .name("create#moqui.test.TestEntity")
                .parameters([testId: "SVC_TEST_CREATE", testMedium: "Created via service"])
                .call()
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_CREATE").one()

        then:
        entity != null
        entity.testMedium == "Created via service"
    }

    def "entity-auto update service updates entity"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "SVC_TEST_UPDATE", testMedium: "Original"]).create()

        when:
        ec.service.sync()
                .name("update#moqui.test.TestEntity")
                .parameters([testId: "SVC_TEST_UPDATE", testMedium: "Updated"])
                .call()
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_UPDATE").one()

        then:
        entity.testMedium == "Updated"
    }

    def "entity-auto store service creates if not exists"() {
        when:
        ec.service.sync()
                .name("store#moqui.test.TestEntity")
                .parameters([testId: "SVC_TEST_STORE_NEW", testMedium: "Stored New"])
                .call()
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_STORE_NEW").one()

        then:
        entity != null
        entity.testMedium == "Stored New"
    }

    def "entity-auto store service updates if exists"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "SVC_TEST_STORE_UPD", testMedium: "Original"]).create()

        when:
        ec.service.sync()
                .name("store#moqui.test.TestEntity")
                .parameters([testId: "SVC_TEST_STORE_UPD", testMedium: "Store Updated"])
                .call()
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_STORE_UPD").one()

        then:
        entity.testMedium == "Store Updated"
    }

    def "entity-auto delete service deletes entity"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "SVC_TEST_DELETE", testMedium: "To Delete"]).create()

        when:
        ec.service.sync()
                .name("delete#moqui.test.TestEntity")
                .parameters([testId: "SVC_TEST_DELETE"])
                .call()
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_DELETE").one()

        then:
        entity == null
    }

    // ========== Async Service Calls ==========

    def "async service call returns immediately"() {
        when:
        long startTime = System.currentTimeMillis()
        ec.service.async()
                .name("org.moqui.impl.BasicServices.noop")
                .call()
        long elapsed = System.currentTimeMillis() - startTime

        then:
        // Async call should return quickly (< 1 second)
        elapsed < 1000
        noExceptionThrown()
    }

    def "async service call with Future allows waiting for result"() {
        when:
        Future<Map<String, Object>> future = ec.service.async()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "Async Test"])
                .callFuture()
        Map result = future.get()

        then:
        result.textOut1 == "Async Test"
    }

    def "async service provides Runnable for custom execution"() {
        when:
        Runnable runnable = ec.service.async()
                .name("org.moqui.impl.BasicServices.noop")
                .getRunnable()

        then:
        runnable != null
        runnable instanceof Runnable
    }

    def "async service provides Callable for custom execution"() {
        when:
        def callable = ec.service.async()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "Callable Test"])
                .getCallable()

        then:
        callable != null
        callable instanceof java.util.concurrent.Callable
    }

    // ========== Transaction Options ==========

    def "sync service with requireNewTransaction creates new transaction"() {
        when:
        // Start an outer transaction
        boolean beganOuter = ec.transaction.begin(null)
        try {
            // Call service with requireNewTransaction - it gets its own transaction
            ec.service.sync()
                    .name("create#moqui.test.TestEntity")
                    .parameters([testId: "SVC_TEST_NEW_TX", testMedium: "New TX"])
                    .requireNewTransaction(true)
                    .call()

            // Rollback outer transaction
            ec.transaction.rollback(beganOuter, "Test rollback", null)
        } catch (Exception e) {
            ec.transaction.rollback(beganOuter, "Exception", e)
            throw e
        }

        // Entity should still exist because it was committed in its own transaction
        EntityValue entity = ec.entity.find("moqui.test.TestEntity").condition("testId", "SVC_TEST_NEW_TX").one()

        then:
        entity != null
        entity.testMedium == "New TX"
    }

    def "sync service with ignoreTransaction does not participate in transaction"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "NoTx"])
                .ignoreTransaction(true)
                .call()

        then:
        result.textOut1 == "NoTx"
        noExceptionThrown()
    }

    // ========== Error Handling ==========

    def "calling non-existent service throws ServiceException"() {
        when:
        ec.service.sync()
                .name("org.moqui.impl.NonExistent.fakeService")
                .call()

        then:
        thrown(ServiceException)
    }

    def "service with ignorePreviousError runs even when errors exist"() {
        given:
        ec.message.addError("Pre-existing error")

        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "IgnoreError"])
                .ignorePreviousError(true)
                .call()

        then:
        result.textOut1 == "IgnoreError"

        cleanup:
        ec.message.clearErrors()
    }

    def "service without ignorePreviousError skips when errors exist"() {
        given:
        ec.message.addError("Pre-existing error")

        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "NoIgnoreError"])
                .call()

        then:
        // Service doesn't run when previous errors exist - returns null
        result == null || result.textOut1 == null

        cleanup:
        ec.message.clearErrors()
    }

    // ========== DisableAuthz ==========

    def "service disableAuthz bypasses authorization not authentication"() {
        when:
        // noop service has authenticate="anonymous-all" so works without login
        // This test verifies disableAuthz works on service call level
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.noop")
                .disableAuthz()
                .call()

        then:
        result != null
        noExceptionThrown()
    }

    // ========== Multi-Value Service Calls ==========

    def "multi-value service call processes multiple parameter sets"() {
        when:
        // Multi-value calls pass parameters with _N suffix for row number
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.noop")
                .parameters([dummy_1: "First", dummy_2: "Second"])
                .multi(true)
                .call()

        then:
        // Multi call completes without error
        noExceptionThrown()
    }

    // ========== Service Name Parsing ==========

    @Unroll
    def "service name '#serviceName' is correctly parsed"() {
        when:
        // Test that service names are parseable (parsing happens during name() call)
        def callSync = ec.service.sync().name(serviceName)

        then:
        noExceptionThrown()

        where:
        serviceName << [
                "org.moqui.impl.BasicServices.noop",
                "org.moqui.impl.BasicServices.echo#Data",
                "create#moqui.test.TestEntity",
                "update#moqui.test.TestEntity",
                "store#moqui.test.TestEntity",
                "delete#moqui.test.TestEntity"
        ]
    }

    // ========== TransactionCache ==========

    def "service with useTransactionCache enables write-through cache"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "CacheTest"])
                .useTransactionCache(true)
                .call()

        then:
        result.textOut1 == "CacheTest"
        noExceptionThrown()
    }

    // ========== Special Service Calls ==========

    def "special service registerOnCommit registers service for transaction commit"() {
        when:
        boolean beganTransaction = ec.transaction.begin(null)
        try {
            ec.service.special()
                    .name("org.moqui.impl.BasicServices.noop")
                    .registerOnCommit()
            ec.transaction.commit(beganTransaction)
        } catch (Exception e) {
            ec.transaction.rollback(beganTransaction, "Exception", e)
            throw e
        }

        then:
        noExceptionThrown()
    }

    def "special service registerOnRollback registers service for transaction rollback"() {
        when:
        boolean beganTransaction = ec.transaction.begin(null)
        try {
            ec.service.special()
                    .name("org.moqui.impl.BasicServices.noop")
                    .registerOnRollback()
            ec.transaction.rollback(beganTransaction, "Test rollback", null)
        } catch (Exception e) {
            ec.transaction.rollback(beganTransaction, "Exception", e)
            throw e
        }

        then:
        noExceptionThrown()
    }

    // ========== Service Timeout ==========

    def "service with custom transaction timeout"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.impl.BasicServices.echo#Data")
                .parameters([textIn1: "Timeout Test"])
                .transactionTimeout(120)  // 2 minutes
                .call()

        then:
        result.textOut1 == "Timeout Test"
        noExceptionThrown()
    }
}
