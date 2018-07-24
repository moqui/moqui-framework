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
package org.moqui.impl.util;

import org.moqui.BaseException;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class SimpleSigner {
    protected final static Logger logger = LoggerFactory.getLogger(SimpleSigner.class);

    private String keyResource, keyType = "RSA", signatureType = "SHA1withRSA";
    private PrivateKey key = null;

    public SimpleSigner(String keyResource) {
        this.keyResource = keyResource;
        initKey();
    }
    public SimpleSigner(String keyResource, String keyType, String signatureType) {
        this.keyResource = keyResource;
        if (keyType != null) this.keyType = keyType;
        if (signatureType != null) this.signatureType = signatureType;
        initKey();
    }

    public String sign(String data) throws Exception {
        if (key == null) throw new BaseException("Cannot sign message, key could not be loaded from resource " + keyResource);
        Signature signature = Signature.getInstance(signatureType);
        signature.initSign(key);
        signature.update(data.getBytes());
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private void initKey() {
        try {
            byte[] keyData = readKey(keyResource);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyData);
            KeyFactory kf = KeyFactory.getInstance(keyType);
            key = kf.generatePrivate(keySpec);
        } catch (Exception e) {
            logger.warn("Could not initialize signing key " + keyResource + ": " + e.toString());
        }
    }
    public static byte[] readKey(String resourcePath) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (is == null) throw new BaseException("Could not find signing key resource " + resourcePath + " on classpath");

        String keyData = ObjectUtilities.getStreamText(is);

        StringBuilder sb = new StringBuilder();
        String[] lines = keyData.split("\n");
        String[] skips = new String[]{"-----BEGIN", "-----END", ": "};
        for (String line : lines) {
            boolean skipLine = false;
            for (String skip : skips) if (line.contains(skip)) { skipLine = true; }
            if (!skipLine) sb.append(line.trim());
        }
        return Base64.getDecoder().decode(sb.toString());
    }
}
