package com.aliyun.autowonder.im;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImProviderRegistryTest {

    @Test
    void normalizesProviderNamesAndRejectsDuplicates() {
        ImProvider provider = provider("dingtalk");
        ImProviderRegistry registry = new ImProviderRegistry(List.of(provider));

        assertSame(provider, registry.require(" DINGTALK "));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ImProviderRegistry(List.of(provider, provider("DINGTALK"))));
        assertEquals("Duplicate IM provider: DINGTALK", error.getMessage());
    }

    private static ImProvider provider(String name) {
        return new ImProvider() {
            @Override
            public String provider() {
                return name;
            }

            @Override
            public void send(ImSendCommand command) {
            }
        };
    }
}
