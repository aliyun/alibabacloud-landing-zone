package com.aliyun.autowonder.security.crypto;

import com.aliyun.autowondercrypto.autoconfigure.SecretCryptoAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCryptoConfigurationTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecretCryptoAutoConfiguration.class))
            .withPropertyValues("autowonder.security.secret-crypto.master-key=" + KEY);

    @Test
    void suppliesAesGcmAsTheDefaultProvider() {
        runner.run(context -> assertThat(context).hasSingleBean(SecretCrypto.class)
                .getBean(SecretCrypto.class).isInstanceOf(AesGcmSecretCrypto.class));
    }

    @Test
    void downstreamProviderReplacesTheDefaultProvider() {
        runner.withUserConfiguration(CustomProviderConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(SecretCrypto.class)
                        .getBean(SecretCrypto.class).isSameAs(CustomProviderConfiguration.CUSTOM));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomProviderConfiguration {
        static final SecretCrypto CUSTOM = new SecretCrypto() {
            public String encrypt(String value) { return value; }
            public String decrypt(String value) { return value; }
            public String mask(String value) { return value; }
        };

        @Bean
        SecretCrypto customSecretCrypto() {
            return CUSTOM;
        }
    }
}
