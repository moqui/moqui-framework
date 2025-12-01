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
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.credential.HashedCredentialsMatcher
import org.apache.shiro.crypto.hash.SimpleHash
import org.apache.shiro.mgt.DefaultSecurityManager
import org.apache.shiro.SecurityUtils
import org.apache.shiro.lang.util.SimpleByteSource
import org.moqui.util.PasswordHasher

/**
 * Tests for Shiro 2.x authentication integration after migration.
 * Verifies that authentication flows work correctly with the upgraded Shiro version.
 */
class ShiroAuthenticationTests extends Specification {

    def "Shiro 2.x DefaultSecurityManager should initialize correctly"() {
        when: "Creating a DefaultSecurityManager"
        DefaultSecurityManager securityManager = new DefaultSecurityManager()

        then: "It should be created successfully"
        securityManager != null
    }

    def "HashedCredentialsMatcher should work with SHA-256 for legacy passwords"() {
        given: "A SHA-256 hashed password"
        String password = "testPassword123"
        String salt = "randomSalt"
        SimpleHash hash = new SimpleHash("SHA-256", password, salt)
        String hashedPassword = hash.toHex()

        and: "A credential matcher configured for SHA-256"
        HashedCredentialsMatcher matcher = new HashedCredentialsMatcher()
        matcher.setHashAlgorithmName("SHA-256")
        matcher.setStoredCredentialsHexEncoded(true)

        and: "Authentication info with the stored hash"
        SimpleAuthenticationInfo authInfo = new SimpleAuthenticationInfo(
            "testUser",
            hashedPassword,
            new SimpleByteSource(salt.getBytes()),
            "testRealm"
        )

        when: "Verifying correct password"
        UsernamePasswordToken correctToken = new UsernamePasswordToken("testUser", password)
        boolean correctMatch = matcher.doCredentialsMatch(correctToken, authInfo)

        and: "Verifying incorrect password"
        UsernamePasswordToken wrongToken = new UsernamePasswordToken("testUser", "wrongPassword")
        boolean wrongMatch = matcher.doCredentialsMatch(wrongToken, authInfo)

        then: "Correct password should match"
        correctMatch == true

        and: "Wrong password should not match"
        wrongMatch == false
    }

    def "SimpleByteSource should work with Shiro 2.x package location"() {
        given: "A salt string"
        String salt = "testSalt123"

        when: "Creating SimpleByteSource from the new package location"
        SimpleByteSource byteSource = new SimpleByteSource(salt.getBytes())

        then: "It should be created successfully"
        byteSource != null
        byteSource.getBytes() != null
        byteSource.getBytes().length > 0
    }

    def "BCrypt password hashing should work alongside Shiro"() {
        given: "A password hashed with BCrypt"
        String password = "mySecurePassword"
        String bcryptHash = PasswordHasher.hashWithBcrypt(password)

        when: "Verifying the password with BCrypt"
        boolean matches = PasswordHasher.verifyBcrypt(password, bcryptHash)
        boolean wrongMatches = PasswordHasher.verifyBcrypt("wrongPassword", bcryptHash)

        then: "Correct password should verify"
        matches == true

        and: "Wrong password should not verify"
        wrongMatches == false
    }

    def "UsernamePasswordToken should work with Shiro 2.x"() {
        given: "Username and password"
        String username = "testUser"
        String password = "testPass123"

        when: "Creating a token"
        UsernamePasswordToken token = new UsernamePasswordToken(username, password, true)

        then: "Token should be created correctly"
        token.getUsername() == username
        token.getPassword() == password.toCharArray()
        token.isRememberMe() == true
    }

    def "SimpleHash should work with various algorithms in Shiro 2.x"() {
        given: "A password to hash"
        String password = "testPassword"
        String salt = "testSalt"

        when: "Hashing with different algorithms"
        SimpleHash sha256Hash = new SimpleHash("SHA-256", password, salt)
        SimpleHash sha512Hash = new SimpleHash("SHA-512", password, salt)
        SimpleHash md5Hash = new SimpleHash("MD5", password, salt)

        then: "All hashes should be created"
        sha256Hash.toHex() != null
        sha256Hash.toHex().length() > 0
        sha512Hash.toHex() != null
        sha512Hash.toHex().length() > 0
        md5Hash.toHex() != null
        md5Hash.toHex().length() > 0

        and: "Hashes should be different for different algorithms"
        sha256Hash.toHex() != sha512Hash.toHex()
        sha256Hash.toHex() != md5Hash.toHex()
    }

    def "Multiple hash iterations should work"() {
        given: "A password and salt"
        String password = "iteratedPassword"
        String salt = "iteratedSalt"

        when: "Hashing with multiple iterations"
        SimpleHash singleIteration = new SimpleHash("SHA-256", password, salt, 1)
        SimpleHash multipleIterations = new SimpleHash("SHA-256", password, salt, 1000)

        then: "Both hashes should be created"
        singleIteration.toHex() != null
        multipleIterations.toHex() != null

        and: "They should be different due to different iteration counts"
        singleIteration.toHex() != multipleIterations.toHex()
    }

    def "Base64 encoding should work for password hashes"() {
        given: "A hashed password"
        String password = "base64TestPassword"
        String salt = "base64Salt"
        SimpleHash hash = new SimpleHash("SHA-256", password, salt)

        when: "Getting Base64 and Hex encodings"
        String hexEncoded = hash.toHex()
        String base64Encoded = hash.toBase64()

        then: "Both encodings should work"
        hexEncoded != null
        base64Encoded != null

        and: "They should be different representations"
        hexEncoded != base64Encoded
    }

    def "PasswordHasher legacy algorithm should match Shiro SimpleHash"() {
        given: "A password and salt"
        String password = "legacyCompatTest"
        String salt = "legacySalt"

        when: "Hashing with PasswordHasher and SimpleHash"
        String passwordHasherResult = PasswordHasher.hashWithLegacyAlgorithm(password, salt, "SHA-256", false)
        SimpleHash shiroHash = new SimpleHash("SHA-256", password, salt)
        String shiroResult = shiroHash.toHex()

        then: "Both should produce the same hash"
        passwordHasherResult == shiroResult
    }
}
