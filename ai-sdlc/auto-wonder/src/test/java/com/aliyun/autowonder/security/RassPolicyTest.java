package com.aliyun.autowonder.security;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.assertNull;

class RassPolicyTest {

    @Test
    void internalRassPolicyIsNotPackaged() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("rass-policy.xml")) {
            assertNull(input, "The community server must not package the internal RASS policy");
        }
    }
}
