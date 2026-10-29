package com.aliyun.autowonder.integration.dingtalk;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DingTalkMessageReactionServiceTest {

    private final DingTalkBindingService bindings = mock(DingTalkBindingService.class);
    private final DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
    private final DingTalkMessageReactionService service =
            new DingTalkMessageReactionService(bindings, sender);

    @Test
    void skipsSharedNullAndBlankTargets() {
        service.markThinking(null, "conv", "m-1");
        service.recallThinking(null, "conv", "m-1");

        var shared = binding("SHARED");
        when(bindings.isShared(shared)).thenReturn(true);
        service.markThinking(shared, "conv", "m-1");
        service.recallThinking(shared, "conv", "m-1");

        var legacy = binding("LEGACY");
        service.markThinking(legacy, " ", "m-1");
        service.recallThinking(legacy, "conv", "");

        verifyNoInteractions(sender);
        verify(bindings, never()).decryptSecret(any());
    }

    @Test
    void deliversLegacyReactionsAndSwallowsProviderFailures() {
        var legacy = binding("LEGACY");
        when(bindings.decryptSecret(legacy)).thenReturn("sk");

        service.markThinking(legacy, "conv", "m-1");
        service.recallThinking(legacy, "conv", "m-1");

        verify(sender).replyThinkingEmotion(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("conv"), eq("m-1"), anyLong());
        verify(sender).recallThinkingEmotion(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("conv"), eq("m-1"), anyLong());

        doThrow(new IllegalStateException("provider down")).when(sender)
                .replyThinkingEmotion(anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyLong());
        doThrow(new IllegalStateException("provider down")).when(sender)
                .recallThinkingEmotion(anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyLong());
        service.markThinking(legacy, "conv", "m-2");
        service.recallThinking(legacy, "conv", "m-2");
    }

    private static DingtalkRobotBindingDO binding(String mode) {
        var binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setBindingMode(mode);
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        return binding;
    }
}
