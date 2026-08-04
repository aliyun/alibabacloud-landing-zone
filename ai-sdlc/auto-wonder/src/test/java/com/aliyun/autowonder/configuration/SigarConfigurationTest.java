package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SigarConfigurationTest {

    @Test
    void missingOrUnloadableNativeReturnsEmptyCapability() {
        SigarConfiguration configuration = new SigarConfiguration(
                new SigarNativeLoader(path -> { throw new IOException("missing"); }));

        assertTrue(configuration.createSigar("Linux", "amd64").isEmpty());
    }
}
