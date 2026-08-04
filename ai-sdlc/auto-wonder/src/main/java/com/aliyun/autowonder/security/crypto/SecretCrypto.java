package com.aliyun.autowonder.security.crypto;

public interface SecretCrypto {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);

    String mask(String value);
}
