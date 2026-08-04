package com.aliyun.autowonder.security.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmSecretCryptoTest {

    private static final String KEY = encodedKey(0);

    @Test
    void encryptsWithVersionedEnvelopeAndRoundTrips() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);

        String encrypted = crypto.encrypt("sk-secret");

        assertTrue(encrypted.startsWith("enc:v1:"));
        assertNotEquals("sk-secret", encrypted);
        assertEquals("sk-secret", crypto.decrypt(encrypted));
    }

    @Test
    void usesANewNonceForEveryWrite() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);

        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
    }

    @Test
    void rejectsPlaintextAndUnknownVersions() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("secret"));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("enc:v2:AAAA"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);
        String encrypted = crypto.encrypt("secret");
        String payload = encrypted.substring("enc:v1:".length());
        byte[] bytes = Base64.getUrlDecoder().decode(payload);
        bytes[bytes.length - 1] ^= 1;
        String tampered = "enc:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void rejectsCiphertextEncryptedWithAnotherKey() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);
        String encrypted = crypto.encrypt("secret");

        assertThrows(IllegalStateException.class,
                () -> new AesGcmSecretCrypto(encodedKey(1)).decrypt(encrypted));
    }

    @Test
    void rejectsMissingMalformedAndWrongLengthKeys() {
        assertThrows(IllegalStateException.class, () -> new AesGcmSecretCrypto(""));
        assertThrows(IllegalStateException.class, () -> new AesGcmSecretCrypto("not-base64"));
        assertThrows(IllegalStateException.class,
                () -> new AesGcmSecretCrypto(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void masksWithoutDecryptingOrExposingTheWholeValue() {
        SecretCrypto crypto = new AesGcmSecretCrypto(KEY);

        assertEquals("****", crypto.mask(null));
        assertEquals("****", crypto.mask("abc"));
        assertEquals("ab****yz", crypto.mask("abcdefyz"));
    }

    private static String encodedKey(int firstByte) {
        byte[] key = new byte[32];
        key[0] = (byte) firstByte;
        return Base64.getEncoder().encodeToString(key);
    }
}
