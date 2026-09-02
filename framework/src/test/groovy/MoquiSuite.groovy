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

import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

// for JUnit Platform Suite annotations see: https://junit.org/junit5/docs/current/api/org.junit.platform.suite.api/org/junit/platform/suite/api/package-summary.html
// for JUnit 5 Jupiter annotations see: https://junit.org/junit5/docs/current/user-guide/index.html#writing-tests-annotations

@Suite
@SelectClasses([ CacheFacadeTests.class, EntityCrud.class, EntityFindTests.class, EntityNoSqlCrud.class,
        L10nFacadeTests.class, MessageFacadeTests.class, ResourceFacadeTests.class, ServiceCrudImplicit.class,
        ServiceFacadeTests.class, SubSelectTests.class, TransactionFacadeTests.class, 
        TransactionCacheDbTests.class, UserFacadeTests.class,
        SystemScreenRenderTests.class, ToolsRestApiTests.class, ToolsScreenRenderTests.class,
        SecurityAccessControlTests.class, SecurityAuthnTests.class, SecurityInjectionTests.class,
        SecurityMisconfigTests.class, SecurityCryptoTests.class, SecurityIntegrityTests.class,
        SecurityLoggingTests.class, SecurityErrorTests.class, SecurityHandshakeTests.class,
        SecurityTarpitTests.class])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() {
        // HTTP proofs reuse this DB; leave the lock user enabled (failed-login tests disable it).
        ExecutionContext ec = null
        try {
            ec = Moqui.getExecutionContext()
            SecurityTestSupport.resetLockAccount(ec)
            SecurityTestSupport.withAuthzDisabled(ec) { SecurityTestSupport.ensureMfaFactor(ec) }
        } catch (Throwable t) {
            System.err.println("MoquiSuite: could not reset sec.lock.test: " + t)
        } finally {
            if (ec != null) ec.destroy()
            Moqui.destroyActiveExecutionContextFactory()
        }
    }
}
