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
package org.moqui.util;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

/**
 * Secure password hashing utility supporting both modern BCrypt and legacy hash algorithms.
 * <p>
 * BCrypt is the recommended algorithm for new passwords. It includes the salt in the hash output
 * and uses a configurable work factor (cost) to resist brute-force attacks.
 * <p>
 * Legacy algorithms (SHA-256, SHA-512, etc.) are supported for backward compatibility during
 * migration but should not be used for new passwords.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html">OWASP Password Storage</a>
 */
public class PasswordHasher {
    private static final Logger logger = LoggerFactory.getLogger(PasswordHasher.class);

    /** BCrypt hash type identifier */
    public static final String HASH_TYPE_BCRYPT = "BCRYPT";

    /** Default BCrypt cost factor (2^12 = 4096 iterations) */
    public static final int DEFAULT_BCRYPT_COST = 12;

    /** Minimum recommended BCrypt cost factor */
    public static final int MIN_BCRYPT_COST = 10;

    /** Maximum BCrypt cost factor (anything higher takes too long) */
    public static final int MAX_BCRYPT_COST = 14;

    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Hash a password using BCrypt with the default cost factor.
     *
     * @param password The plaintext password to hash
     * @return The BCrypt hash string (includes algorithm, cost, salt, and hash)
     */
    public static String hashWithBcrypt(String password) {
        return hashWithBcrypt(password, DEFAULT_BCRYPT_COST);
    }

    /**
     * Hash a password using BCrypt with a specified cost factor.
     *
     * @param password The plaintext password to hash
     * @param cost The cost factor (10-14 recommended, higher = slower/more secure)
     * @return The BCrypt hash string (includes algorithm, cost, salt, and hash)
     */
    public static String hashWithBcrypt(String password, int cost) {
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        if (cost < MIN_BCRYPT_COST || cost > MAX_BCRYPT_COST) {
            logger.warn("BCrypt cost {} is outside recommended range ({}-{}), using default {}",
                    cost, MIN_BCRYPT_COST, MAX_BCRYPT_COST, DEFAULT_BCRYPT_COST);
            cost = DEFAULT_BCRYPT_COST;
        }

        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    /**
     * Verify a password against a BCrypt hash.
     *
     * @param password The plaintext password to verify
     * @param bcryptHash The BCrypt hash to verify against
     * @return true if the password matches the hash, false otherwise
     */
    public static boolean verifyBcrypt(String password, String bcryptHash) {
        if (password == null || bcryptHash == null) {
            return false;
        }

        try {
            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), bcryptHash);
            return result.verified;
        } catch (Exception e) {
            logger.warn("BCrypt verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a hash string is a BCrypt hash.
     *
     * @param hash The hash string to check
     * @return true if it appears to be a BCrypt hash
     */
    public static boolean isBcryptHash(String hash) {
        if (hash == null || hash.length() < 59) {
            return false;
        }
        // BCrypt hashes start with $2a$, $2b$, or $2y$ followed by cost
        return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
    }

    /**
     * Hash a password using a legacy algorithm (for backward compatibility only).
     * <p>
     * WARNING: These algorithms are not recommended for new passwords.
     * Use {@link #hashWithBcrypt(String)} for new passwords.
     *
     * @param password The plaintext password to hash
     * @param salt The salt to use
     * @param hashType The hash algorithm (e.g., "SHA-256", "SHA-512")
     * @param base64 Whether to encode as Base64 (false = hex encoding)
     * @return The hashed password
     */
    public static String hashWithLegacyAlgorithm(String password, String salt, String hashType, boolean base64) {
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }

        SimpleHash simpleHash = new SimpleHash(hashType != null ? hashType : "SHA-256", password, salt);
        return base64 ? simpleHash.toBase64() : simpleHash.toHex();
    }

    /**
     * Verify a password against a legacy hash.
     *
     * @param password The plaintext password to verify
     * @param storedHash The stored hash to verify against
     * @param salt The salt used when creating the hash
     * @param hashType The hash algorithm used
     * @param base64 Whether the hash is Base64 encoded
     * @return true if the password matches the hash
     */
    public static boolean verifyLegacyHash(String password, String storedHash, String salt, String hashType, boolean base64) {
        if (password == null || storedHash == null) {
            return false;
        }

        String computedHash = hashWithLegacyAlgorithm(password, salt, hashType, base64);
        return storedHash.equals(computedHash);
    }

    /**
     * Generate a random salt for legacy algorithms.
     *
     * @return A random 8-character salt string
     */
    public static String generateRandomSalt() {
        return StringUtilities.getRandomString(8);
    }

    /**
     * Determine if a password hash should be upgraded to BCrypt.
     * <p>
     * This should be called after successful password verification to check if
     * the hash should be upgraded to a more secure algorithm.
     *
     * @param hashType The current hash type
     * @return true if the hash should be upgraded to BCrypt
     */
    public static boolean shouldUpgradeHash(String hashType) {
        if (hashType == null) {
            return true;
        }
        // Any non-BCrypt hash should be upgraded
        return !HASH_TYPE_BCRYPT.equalsIgnoreCase(hashType);
    }

    /**
     * Get the BCrypt cost factor from an existing hash.
     *
     * @param bcryptHash The BCrypt hash string
     * @return The cost factor, or -1 if not a valid BCrypt hash
     */
    public static int getBcryptCost(String bcryptHash) {
        if (!isBcryptHash(bcryptHash)) {
            return -1;
        }
        try {
            // BCrypt format: $2a$XX$... where XX is the cost
            String costStr = bcryptHash.substring(4, 6);
            return Integer.parseInt(costStr);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Check if a BCrypt hash needs to be upgraded due to increased cost factor.
     *
     * @param bcryptHash The current BCrypt hash
     * @param targetCost The target cost factor
     * @return true if the hash should be re-hashed with a higher cost
     */
    public static boolean shouldUpgradeBcryptCost(String bcryptHash, int targetCost) {
        int currentCost = getBcryptCost(bcryptHash);
        return currentCost > 0 && currentCost < targetCost;
    }
}
