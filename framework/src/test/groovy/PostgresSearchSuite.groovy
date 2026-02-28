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
 * Run all:    ./gradlew :framework:test --tests PostgresSearchSuite
 * Unit only:  ./gradlew :framework:test --tests PostgresSearchTranslatorTests (requires MoquiSuite or separate runs)
 */
@Suite
@SelectClasses([PostgresSearchTranslatorTests.class, PostgresElasticClientTests.class])
class PostgresSearchSuite {
    @AfterAll
    static void destroyMoqui() {
        Moqui.destroyActiveExecutionContextFactory()
    }
}
