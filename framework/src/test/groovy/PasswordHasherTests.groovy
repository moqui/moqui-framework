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

import spock.lang.*
import org.moqui.util.PasswordHasher

class PasswordHasherTests extends Specification {

    def "BCrypt hash should verify correctly"() {
        given: "A password"
        String password = "MySecurePassword123!"

        when: "Hashing with BCrypt"
        String hash = PasswordHasher.hashWithBcrypt(password)

        then: "The hash should verify correctly"
        PasswordHasher.verifyBcrypt(password, hash)
        !PasswordHasher.verifyBcrypt("WrongPassword", hash)
    }

    def "BCrypt hash should be identifiable"() {
        given: "A BCrypt hash"
        String hash = PasswordHasher.hashWithBcrypt("test")

        expect: "It should be identified as BCrypt"
        PasswordHasher.isBcryptHash(hash)
        hash.startsWith('$2')
        hash.length() == 60
    }

    def "Legacy SHA-256 hash should not be identified as BCrypt"() {
        given: "A SHA-256 hash"
        String hash = PasswordHasher.hashWithLegacyAlgorithm("test", "salt", "SHA-256", false)

        expect: "It should not be identified as BCrypt"
        !PasswordHasher.isBcryptHash(hash)
    }

    def "BCrypt cost factor should be extractable"() {
        given: "A BCrypt hash with cost 12"
        String hash = PasswordHasher.hashWithBcrypt("test", 12)

        expect: "Cost factor should be 12"
        PasswordHasher.getBcryptCost(hash) == 12
    }

    def "Different passwords should produce different hashes"() {
        when: "Hashing the same password twice"
        String hash1 = PasswordHasher.hashWithBcrypt("password")
        String hash2 = PasswordHasher.hashWithBcrypt("password")

        then: "Hashes should be different (due to random salt)"
        hash1 != hash2

        and: "Both should verify correctly"
        PasswordHasher.verifyBcrypt("password", hash1)
        PasswordHasher.verifyBcrypt("password", hash2)
    }

    def "Legacy algorithm should hash and verify correctly"() {
        given: "A password and salt"
        String password = "TestPassword"
        String salt = "randomsalt"

        when: "Hashing with SHA-256"
        String hash = PasswordHasher.hashWithLegacyAlgorithm(password, salt, "SHA-256", false)

        then: "It should verify correctly"
        PasswordHasher.verifyLegacyHash(password, hash, salt, "SHA-256", false)
        !PasswordHasher.verifyLegacyHash("WrongPassword", hash, salt, "SHA-256", false)
    }

    def "Should upgrade from legacy hash types"() {
        expect: "SHA-256 and other legacy types should need upgrade"
        PasswordHasher.shouldUpgradeHash("SHA-256")
        PasswordHasher.shouldUpgradeHash("SHA-512")
        PasswordHasher.shouldUpgradeHash("MD5")
        PasswordHasher.shouldUpgradeHash(null)

        and: "BCrypt should not need upgrade"
        !PasswordHasher.shouldUpgradeHash("BCRYPT")
        !PasswordHasher.shouldUpgradeHash("bcrypt")
    }

    def "Random salt generation should produce unique values"() {
        when: "Generating multiple salts"
        def salts = (1..10).collect { PasswordHasher.generateRandomSalt() }

        then: "All salts should be unique"
        salts.unique().size() == 10

        and: "All salts should be 8 characters"
        salts.every { it.length() == 8 }
    }

    def "Null password should throw exception for BCrypt"() {
        when: "Hashing null password"
        PasswordHasher.hashWithBcrypt(null)

        then: "IllegalArgumentException should be thrown"
        thrown(IllegalArgumentException)
    }

    def "Null password should throw exception for legacy hash"() {
        when: "Hashing null password with legacy algorithm"
        PasswordHasher.hashWithLegacyAlgorithm(null, "salt", "SHA-256", false)

        then: "IllegalArgumentException should be thrown"
        thrown(IllegalArgumentException)
    }

    def "BCrypt verification with null inputs should return false"() {
        expect: "Null inputs should return false, not throw exception"
        !PasswordHasher.verifyBcrypt(null, "hash")
        !PasswordHasher.verifyBcrypt("password", null)
        !PasswordHasher.verifyBcrypt(null, null)
    }

    def "BCrypt cost upgrade detection should work"() {
        given: "A hash with cost 10"
        String hash = PasswordHasher.hashWithBcrypt("test", 10)

        expect: "Should recommend upgrade to cost 12"
        PasswordHasher.shouldUpgradeBcryptCost(hash, 12)
        !PasswordHasher.shouldUpgradeBcryptCost(hash, 10)
        !PasswordHasher.shouldUpgradeBcryptCost(hash, 8)
    }

    def "BCrypt with special characters should work"() {
        given: "Passwords with special characters"
        // Note: BCrypt has a 72-byte limit, so we test a long password within that limit
        def passwords = [
            "password with spaces",
            "p@ssw0rd!#\$%^&*()",
            "unicodePassword\u00e9\u00e8\u00ea",
            "a" * 71  // BCrypt max is 72 bytes
        ]

        expect: "All should hash and verify correctly"
        passwords.every { password ->
            String hash = PasswordHasher.hashWithBcrypt(password)
            PasswordHasher.verifyBcrypt(password, hash)
        }
    }

    def "BCrypt with empty password should work"() {
        given: "An empty password"
        String password = ""

        when: "Hashing empty password"
        String hash = PasswordHasher.hashWithBcrypt(password)

        then: "It should hash and verify correctly"
        PasswordHasher.verifyBcrypt(password, hash)
        !PasswordHasher.verifyBcrypt("notEmpty", hash)
    }
}
