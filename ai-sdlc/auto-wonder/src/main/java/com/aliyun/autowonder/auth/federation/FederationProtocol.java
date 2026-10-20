package com.aliyun.autowonder.auth.federation;

public enum FederationProtocol {
    SAML2,
    OIDC;

    public static FederationProtocol parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (FederationProtocol protocol : values()) {
            if (protocol.name().equalsIgnoreCase(value.trim())) {
                return protocol;
            }
        }
        return null;
    }
}
