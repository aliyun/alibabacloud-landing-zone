package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.conversation.AgentConversationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DingTalkInboundServiceTest {

    private final DingtalkRobotBindingDao bindingDao = mock(DingtalkRobotBindingDao.class);
    private final DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
    private final AgentConversationService convService = mock(AgentConversationService.class);
    private final DingTalkMessageReactionService reactionService =
            mock(DingTalkMessageReactionService.class);
    private final DingTalkInboundService svc =
            new DingTalkInboundService(bindingDao, bindingService, convService, reactionService);

    @Test
    void routesByRobotCodeToAgentAndSubmits() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setRobotCode("rc");
        binding.setStatus("ENABLED");
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(binding);

        InboundBotMessage msg = new InboundBotMessage("openConv", "user1", "rc", "hello", "m-1");
        svc.handle(msg);

        var order = inOrder(reactionService, convService);
        order.verify(reactionService).markThinking(binding, "openConv", "m-1");
        order.verify(convService).submitTurn(eq(1L), eq(3L), eq("DINGTALK"), eq("openConv"),
                eq("hello"), eq("m-1"), anyString());
    }

    @Test
    void reactionFailureDoesNotBlockSubmitTurn() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setRobotCode("rc");
        binding.setStatus("ENABLED");
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(binding);
        doThrow(new RuntimeException("reaction failed"))
                .when(reactionService).markThinking(binding, "openConv", "m-1");

        InboundBotMessage msg = new InboundBotMessage("openConv", "user1", "rc", "hello", "m-1");
        svc.handle(msg);

        verify(convService).submitTurn(eq(1L), eq(3L), eq("DINGTALK"), eq("openConv"),
                eq("hello"), eq("m-1"), anyString());
    }

    @Test
    void duplicateMessageDoesNotAddReactionOrSubmitTurn() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setRobotCode("rc");
        binding.setStatus("ENABLED");
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(binding);
        when(convService.hasExternalMessage(1L, "m-1")).thenReturn(true);

        InboundBotMessage msg = new InboundBotMessage("openConv", "user1", "rc", "hello", "m-1");
        svc.handle(msg);

        verify(reactionService, never()).markThinking(any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ignoresUnknownRobotCode() {
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(null);
        InboundBotMessage msg = new InboundBotMessage("openConv", "user1", "rc", "hello", "m-1");
        svc.handle(msg);
        verify(reactionService, never()).markThinking(any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonTextMessageIsAcknowledgedWithoutSubmittingTurn() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setRobotCode("rc");
        binding.setStatus("ENABLED");
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(binding);

        InboundBotMessage msg = message("picture", null);
        svc.handle(msg);

        verify(reactionService, never()).markThinking(any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any());
        verify(convService, never()).submitTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void textMessageStoresSourceContextOnSubmittedTurn() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setRobotCode("rc");
        binding.setStatus("ENABLED");
        when(bindingDao.findByRobotCodeGlobal("rc")).thenReturn(binding);

        InboundBotMessage msg = message("text", "hello");
        svc.handle(msg);

        verify(convService).submitTurn(eq(1L), eq(3L), eq("DINGTALK"), eq("openConv"),
                eq("hello"), eq("m-1"), argThat(sourceContext -> {
                    JSONObject json = JSONObject.parseObject(sourceContext);
                    return "Wang Wu".equals(json.getString("senderNick"))
                            && "https://oapi.dingtalk.com/robot/sendBySession?session=s1"
                                    .equals(json.getString("sessionWebhook"));
                }));
    }

    private InboundBotMessage message(String msgType, String textContent) {
        return new InboundBotMessage(
                "openConv",
                "Requirements group",
                "2",
                "user1",
                "Wang Wu",
                "staff-1",
                "corp-1",
                List.of(new InboundBotMessage.AtUser("dt-1", "staff-1")),
                Boolean.TRUE,
                "corp-1",
                "bot-user",
                "rc",
                textContent,
                "m-1",
                msgType,
                "https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                4102444800000L,
                1784810000000L,
                "{}");
    }
}
