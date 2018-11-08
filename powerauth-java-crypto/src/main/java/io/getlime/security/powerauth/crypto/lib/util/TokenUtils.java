/*
 * PowerAuth Crypto Library
 * Copyright 2018 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.getlime.security.powerauth.crypto.lib.util;

import com.google.common.primitives.Bytes;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.crypto.lib.model.exception.GenericCryptoException;
import io.getlime.security.powerauth.provider.exception.CryptoProviderException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Class used for computing PowerAuth Token digests.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
public class TokenUtils {

    private final KeyGenerator keyGenerator = new KeyGenerator();
    private final HMACHashUtilities hmac = new HMACHashUtilities();

    /**
     * Generate random token ID. Use UUID format.
     *
     * @return Random token ID.
     */
    public String generateTokenId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generate random token secret, 16 random bytes.
     *
     * @return Random token secret.
     */
    public byte[] generateTokenSecret() {
        return keyGenerator.generateRandomBytes(16);
    }

    /**
     * Generate random token nonce, 16 random bytes.
     *
     * @return Random token nonce.
     */
    public byte[] generateTokenNonce() {
        return keyGenerator.generateRandomBytes(16);
    }

    /**
     * Helper method to get current timestamp for the purpose of token timestamping, encoded as bytes from the
     * String representation of timestamp.<br>
     * <br>
     * The timestamp conversion works like this: Long timestamp is converted to String and then, bytes of the
     * String are extracted usign the UTF-8 encoding.<br>
     * <br>
     * Code: <code>String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);</code>
     *
     * @return Current timestamp in milliseconds.
     */
    public byte[] generateTokenTimestamp() {
        return String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Helper method to convert provided timestamp into bytes (using the string representation of the timestamp),
     * for the purpose of token timestamping.
     *
     * @param timestamp Timestamp to be converted.
     * @return Provided timestamp in milliseconds converted as bytes.
     */
    public byte[] convertTokenTimestamp(long timestamp) {
        return String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Compute the digest of provided token information using given token secret.
     * @param nonce Token nonce, 16 random bytes.
     * @param timestamp Token timestamp, Unix timestamp format encoded as bytes (string representation).
     * @param tokenSecret Token secret, 16 random bytes.
     * @return Token digest computed using provided data bytes with given token secret.
     * @throws GenericCryptoException In case digest computation fails.
     * @throws CryptoProviderException In case cryptography provider is incorrectly initialized.
     */
    public byte[] computeTokenDigest(byte[] nonce, byte[] timestamp, byte[] tokenSecret) throws GenericCryptoException, CryptoProviderException {
        byte[] amp = "&".getBytes(StandardCharsets.UTF_8);
        byte[] data = Bytes.concat(nonce, amp, timestamp);
        return hmac.hash(tokenSecret, data);
    }

    /**
     * Validate provided token digest for given input data and provided token secret.
     * @param nonce Token nonce, 16 random bytes.
     * @param timestamp Token timestamp, Unix timestamp format encoded as bytes (string representation).
     * @param tokenSecret Token secret, 16 random bytes.
     * @param tokenDigest Token digest, 32 bytes to be validated.
     * @return Token digest computed using provided data bytes with given token secret.
     * @throws GenericCryptoException In case digest computation fails.
     * @throws CryptoProviderException In case cryptography provider is incorrectly initialized.
     */
    public boolean validateTokenDigest(byte[] nonce, byte[] timestamp, byte[] tokenSecret, byte[] tokenDigest) throws GenericCryptoException, CryptoProviderException {
        return Arrays.equals(computeTokenDigest(nonce, timestamp, tokenSecret), tokenDigest);
    }

}
