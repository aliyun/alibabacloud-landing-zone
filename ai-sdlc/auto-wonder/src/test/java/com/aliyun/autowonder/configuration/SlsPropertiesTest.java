package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlsPropertiesTest {

    @Test
    void disabledSlsRequiresNoCredentials() {
        assertDoesNotThrow(new SlsProperties()::validate);
    }

    @Test
    void enabledSlsRequiresEveryConnectionProperty() {
        SlsProperties properties = new SlsProperties();
        properties.setEnabled(true);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void enabledSlsAcceptsCompletePublicServiceConfiguration() {
        SlsProperties properties = new SlsProperties();
        properties.setEnabled(true);
        properties.setEndpoint("cn-hangzhou.log.aliyuncs.com");
        properties.setProject("community-project");
        properties.setSysLogStore("system");
        properties.setBizLogStore("business");
        properties.setMetricLogStore("metrics");
        properties.setAccessKeyId("access-key-id");
        properties.setAccessKeySecret("access-key-secret");

        assertDoesNotThrow(properties::validate);
    }
}
