package com.aliyun.autowondercrypto.autoconfigure;

import com.aliyun.autowonder.security.crypto.AesGcmSecretCrypto;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.security.crypto.SecretCryptoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretCryptoProperties.class)
public class SecretCryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecretCrypto.class)
    SecretCrypto secretCrypto(SecretCryptoProperties properties) {
        return new AesGcmSecretCrypto(properties);
    }
}
