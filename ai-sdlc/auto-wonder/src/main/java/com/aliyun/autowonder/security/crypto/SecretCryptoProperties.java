package com.aliyun.autowonder.security.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autowonder.security.secret-crypto")
public class SecretCryptoProperties {

    private String masterKey;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
