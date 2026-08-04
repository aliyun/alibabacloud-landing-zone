package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;

final class AoneTestProperties {

    private AoneTestProperties() {
    }

    static AoneIntegrationProperties enabled() {
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        properties.setEnabled(true);
        return properties;
    }
}
