/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.MNode
import spock.lang.Shared
import spock.lang.Specification

class SecurityCryptoTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        SecurityTestSupport.ensureUsers(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }
    def setup() { SecurityTestSupport.logout(ec) }

    def "password encrypt-hash-type is SHA-256"() {
        expect:
        ecfi.getPasswordHashType() == "SHA-256"
    }

    def "entity crypt-algo is PBEWithHmacSHA256AndAES_128"() {
        when:
        MNode fac = ecfi.getConfXmlRoot().first("entity-facade")
        then:
        fac.attribute("crypt-algo") == "PBEWithHmacSHA256AndAES_128"
    }

    def "stored password is hashed not plaintext"() {
        when:
        def ua = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            ua = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.ALL_USERNAME).one()
        }
        then:
        ua != null
        ua.currentPassword != SecurityTestSupport.ALL_PASSWORD
        (ua.currentPassword as String).length() >= 32
        ua.passwordHashType == "SHA-256" || ua.passwordHashType == null
    }

    def "getSimpleHash round-trips with credentials matcher"() {
        when:
        String salt = "secTestSalt"
        String hashed = ecfi.getSimpleHash(SecurityTestSupport.ALL_PASSWORD, salt)
        then:
        hashed != SecurityTestSupport.ALL_PASSWORD
        hashed == ecfi.getSimpleHash(SecurityTestSupport.ALL_PASSWORD, salt, "SHA-256", false)
        hashed != ecfi.getSimpleHash("other-password", salt)
    }

    def "login key is stored hashed"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        String key = ec.user.getLoginKey(1)
        String hashed = ecfi.getSimpleHash(key, "", ecfi.getLoginKeyHashType(), false)
        def stored = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            stored = ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hashed).one()
        }
        then:
        key != null && key.length() > 10
        stored != null
        stored.loginKey != key
        stored.loginKey == hashed
        cleanup:
        SecurityTestSupport.withAuthzDisabled(ec) {
            if (stored != null) stored.delete()
        }
    }

    def "default crypt-pass property is the documented CHANGEME value"() {
        // Operators must override entity_ds_crypt_pass in production. This records the shipped default.
        expect:
        SecurityTestSupport.defaultProperty("entity_ds_crypt_pass") == "MoquiDefaultPassword:CHANGEME"
    }

    def "password min-length is at least 8"() {
        when:
        MNode pw = ecfi.getConfXmlRoot().first("user-facade")?.first("password")
        int minLen = (pw?.attribute("min-length") ?: "0") as int
        then:
        minLen >= 8
    }
}
