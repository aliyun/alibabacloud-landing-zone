package com.aliyun.autowonder.integration.aone;

final class AoneClientTestSupport {

    private AoneClientTestSupport() {
    }

    static AoneIntegrationProperties enabledProperties() {
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        properties.setEnabled(true);
        return properties;
    }
}
