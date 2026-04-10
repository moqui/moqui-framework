/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

/**
 * JUnit Platform Suite for PostgreSQL search backend tests.
 *
 * This suite is separate from MoquiSuite because it requires a live PostgreSQL database.
 * It will NOT run as part of the main MoquiSuite — it is opt-in via:
 *
 *   ./gradlew :framework:test --tests PostgresSearchSuite
 *
 * Or run individual test classes:
 *   ./gradlew :framework:test --tests PostgresSearchTranslatorTests
 *   ./gradlew :framework:test --tests PostgresElasticClientTests
 */
@Suite
@SelectClasses([PostgresSearchTranslatorTests.class, PostgresElasticClientTests.class])
class PostgresSearchSuite {
    @AfterAll
    static void destroyMoqui() {
        Moqui.destroyActiveExecutionContextFactory()
    }
}
