package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AoneActivationContractTest {

    private static final List<Class<?>> CONDITIONAL_ENTRY_POINTS = List.of(
            AoneIntegrationController.class,
            AoneInboundPoller.class);

    @Test
    void everyAoneOutboundComponentRequiresExplicitEnablement() {
        for (Class<?> type : CONDITIONAL_ENTRY_POINTS) {
            ConditionalOnProperty condition = AnnotatedElementUtils
                    .findMergedAnnotation(type, ConditionalOnProperty.class);
            assertNotNull(condition, type.getName());
            assertEquals("autowonder.integration.aone", condition.prefix());
            assertArrayEquals(new String[]{"enabled"}, condition.name());
            assertEquals("true", condition.havingValue());
            assertFalse(condition.matchIfMissing());
        }
    }

    @Test
    void sharedOutboxDispatcherRemainsActiveForPublicProviders() {
        ConditionalOnProperty condition = AnnotatedElementUtils
                .findMergedAnnotation(AoneOutboxDispatcher.class, ConditionalOnProperty.class);
        assertEquals(null, condition);
    }

    @Test
    void capabilitiesExposeDisabledAndEnabledState() throws Exception {
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new IntegrationCapabilityController(properties)).build();

        mvc.perform(get("/api/integrations/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aoneEnabled").value(false));

        properties.setEnabled(true);
        mvc.perform(get("/api/integrations/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aoneEnabled").value(true));
    }
}
