package com.aliyun.autowonder.auth.federation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 RSA 密钥材料：证书走 JDK 自带的 keytool 现场生成（避免把私钥提交进仓库），
 * 同一个 keypair 既能给 SAML 断言签名，也能给 OIDC 的 id_token 签名。
 */
final class FederationTestKeys {
    private static final String ALIAS = "federation-test";
    private static final String PASSWORD = "changeit";

    private static KeyPair keyPair;
    private static X509Certificate certificate;

    private FederationTestKeys() {
    }

    static synchronized KeyPair keyPair() throws Exception {
        ensureGenerated();
        return keyPair;
    }

    static synchronized String certificatePem() throws Exception {
        ensureGenerated();
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----";
    }

    /** 另一组独立密钥，用于「签名与配置证书不匹配」的负向用例。 */
    static KeyPair freshKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void ensureGenerated() throws Exception {
        if (keyPair != null) {
            return;
        }
        Path directory = Files.createTempDirectory("federation-test-keys");
        Path store = directory.resolve("federation-test.p12");
        Process process = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", "CN=federation-test",
                "-validity", "3650",
                "-keystore", store.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed to generate the test certificate: " + output);
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        certificate = (X509Certificate) keyStore.getCertificate(ALIAS);
        keyPair = new KeyPair(certificate.getPublicKey(),
                (PrivateKey) keyStore.getKey(ALIAS, PASSWORD.toCharArray()));
    }
}
