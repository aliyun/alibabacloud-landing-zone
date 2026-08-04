package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DingTalkChannelSinkTest {

    @Test
    void tokenIsCachedWithinExpiry() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("groupMessages/send"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        long now = 1_000_000L;
        sender.sendMarkdown("ak", "sk", "https://gw", "rc", "conv", "hello", now);
        sender.sendMarkdown("ak", "sk", "https://gw", "rc", "conv", "world", now + 1000);

        verify(http, times(1)).post(contains("accessToken"), anyString(), anyMap());
        verify(http, times(2)).post(contains("groupMessages/send"), anyString(), anyMap());
    }

    @Test
    void tokenCacheIsSeparatedAcrossCredentialRotationAndBaseUrl() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("groupMessages/send"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendMarkdown("ak", "secret-one", "https://gw-one", "rc", "conv", "one", 1L);
        sender.sendMarkdown("ak", "secret-two", "https://gw-one", "rc", "conv", "two", 2L);
        sender.sendMarkdown("ak", "secret-two", "https://gw-two", "rc", "conv", "three", 3L);

        verify(http, times(3)).post(contains("accessToken"), anyString(), anyMap());
    }

    @Test
    void senderPostsSessionWebhookMarkdownWithAtUser() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(anyString(), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendSessionWebhookMarkdown("https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                "@张三\n\nreply", List.of("123456"));

        verify(http).post(eq("https://oapi.dingtalk.com/robot/sendBySession?session=s1"),
                argThat(body -> body.contains("\"msgtype\":\"markdown\"")
                        && body.contains("\"title\":\"Auto Wonder\"")
                        && body.contains("\"text\":\"@张三\\n\\nreply\"")
                        && body.contains("\"atUserIds\":[\"123456\"]")
                        && body.contains("\"isAtAll\":false")),
                eq(Map.of()));
    }

    @Test
    void senderPostsSessionWebhookTextWithAtUser() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(anyString(), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendSessionWebhookText("https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                "@123456\nreply", List.of("123456"));

        verify(http).post(eq("https://oapi.dingtalk.com/robot/sendBySession?session=s1"),
                argThat(body -> body.contains("\"msgtype\":\"text\"")
                        && body.contains("\"content\":\"@123456\\nreply\"")
                        && body.contains("\"atUserIds\":[\"123456\"]")
                        && body.contains("\"isAtAll\":false")),
                eq(Map.of()));
    }

    @Test
    void senderPostsSingleChatFallback() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("oToMessages/batchSend"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendSingleMarkdown("ak", "sk", "https://gw", "rc",
                List.of("123456"), "reply", 1_000_000L);

        verify(http).post(eq("https://gw/v1.0/robot/oToMessages/batchSend"),
                argThat(body -> body.contains("\"robotCode\":\"rc\"")
                        && body.contains("\"userIds\":[\"123456\"]")
                        && body.contains("\"msgKey\":\"sampleMarkdown\"")
                        && body.contains("\\\"title\\\":\\\"Auto Wonder\\\"")
                        && body.contains("\\\"text\\\":\\\"reply\\\"")),
                eq(Map.of("x-acs-dingtalk-access-token", "tok")));
    }

    @Test
    void senderPostsGroupTextWithAtUser() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("groupMessages/send"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendGroupText("ak", "sk", "https://gw", "rc", "conv",
                "@123456\nreply", List.of("123456"), 1_000_000L);

        verify(http).post(eq("https://gw/v1.0/robot/groupMessages/send"),
                argThat(body -> body.contains("\"robotCode\":\"rc\"")
                        && body.contains("\"openConversationId\":\"conv\"")
                        && body.contains("\"msgKey\":\"sampleText\"")
                        && body.contains("\\\"content\\\":\\\"@123456")
                        && body.contains("\\\"atUserIds\\\":[\\\"123456\\\"]")),
                eq(Map.of("x-acs-dingtalk-access-token", "tok")));
    }

    @Test
    void senderPostsThinkingEmotionAndRecallPayloads() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("emotion"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.replyThinkingEmotion("ak", "sk", "https://gw", "rc", "conv", "msg-1", 1_000_000L);
        sender.recallThinkingEmotion("ak", "sk", "https://gw", "rc", "conv", "msg-1", 1_000_001L);

        verify(http).post(eq("https://gw/v1.0/robot/emotion/reply"),
                argThat(body -> body.contains("\"robotCode\":\"rc\"")
                        && body.contains("\"openConversationId\":\"conv\"")
                        && body.contains("\"openMsgId\":\"msg-1\"")
                        && body.contains("\"emotionType\":2")
                        && body.contains("\"emotionName\":\"🤔思考中\"")),
                eq(Map.of("x-acs-dingtalk-access-token", "tok")));
        verify(http).post(eq("https://gw/v1.0/robot/emotion/recall"),
                argThat(body -> body.contains("\"robotCode\":\"rc\"")
                        && body.contains("\"openConversationId\":\"conv\"")
                        && body.contains("\"openMsgId\":\"msg-1\"")),
                eq(Map.of("x-acs-dingtalk-access-token", "tok")));
    }

    @Test
    void senderUsesDefaultBaseUrlWhenBlank() {
        DingTalkOutboundSender.HttpExchange http = mock(DingTalkOutboundSender.HttpExchange.class);
        when(http.post(contains("accessToken"), anyString(), anyMap()))
                .thenReturn("{\"accessToken\":\"tok\",\"expireIn\":7200}");
        when(http.post(contains("groupMessages/send"), anyString(), anyMap())).thenReturn("{}");
        DingTalkOutboundSender sender = new DingTalkOutboundSender(http);

        sender.sendMarkdown("ak", "sk", " ", "rc", "conv", "hello", 1_000_000L);

        verify(http).post(eq(DingTalkOutboundSender.DEFAULT_BASE_URL + "/v1.0/oauth2/accessToken"),
                anyString(), anyMap());
        verify(http).post(eq(DingTalkOutboundSender.DEFAULT_BASE_URL + "/v1.0/robot/groupMessages/send"),
                anyString(), anyMap());
    }

    @Test
    void sinkDecryptsAndSends() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        Map<Long, DingtalkRobotBindingDO> byAgent = new HashMap<>();
        byAgent.put(3L, binding);
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(byAgent.values()));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendGroupMarkdown(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("openConv"), eq("reply"), eq(List.of()), anyLong());
        verify(reactionService).recallThinking(binding, "openConv", "m-1");
    }

    @Test
    void sinkUsesFreshSessionWebhookWithStaffIdMentionAndAtUserIds() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        // senderStaffId present: body uses @<senderStaffId> to match atUserIds, which carries
        // the staff id so the replied user gets the system "@" notification.
        verify(sender).sendSessionWebhookText(
                eq("https://oapi.dingtalk.com/robot/sendBySession?session=s1"),
                eq("@123456\n\nreply"), eq(List.of("123456")));
        verify(sender, never()).sendGroupMarkdown(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList(), anyLong());
        verify(reactionService).recallThinking(binding, "openConv", "m-1");
    }

    @Test
    void sinkFallsBackToGroupWhenFreshSessionWebhookThrows() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);
        doThrow(new RuntimeException("webhook failed")).when(sender)
                .sendSessionWebhookText(eq(context.getSessionWebhook()), anyString(), anyList());

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendSessionWebhookText(eq(context.getSessionWebhook()),
                eq("@123456\n\nreply"), eq(List.of("123456")));
        verify(sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("openConv"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
        verify(reactionService).recallThinking(binding, "openConv", "m-1");
        verify(bindingService).markHealth(eq(1L), eq(7L), any(java.util.Date.class), isNull());
        verify(bindingService, never()).markHealth(eq(1L), eq(7L), isNull(), anyString());
    }

    @Test
    void sinkUsesSourceRobotCodeBindingForSessionWebhookReactionAndHealth() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = conversation();
        DingtalkRobotBindingDO first = binding(7L, "rc-first");
        DingtalkRobotBindingDO selected = binding(8L, "rc-selected");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(first, selected)));
        when(dao.findByRobotCode(1L, "rc-selected")).thenReturn(selected);
        when(bindingService.decryptSecret(selected)).thenReturn("sk-selected");

        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setRobotCode("rc-selected");
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=selected");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        verify(bindingService).decryptSecret(selected);
        verify(bindingService, never()).decryptSecret(first);
        verify(sender).sendSessionWebhookText(eq(context.getSessionWebhook()),
                eq("@123456\n\nreply"), eq(List.of("123456")));
        verify(reactionService).recallThinking(selected, "openConv", "m-1");
        verify(bindingService).markHealth(eq(1L), eq(8L), any(java.util.Date.class), isNull());
    }

    @Test
    void sinkUsesSourceRobotCodeBindingForProactiveFallback() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = conversation();
        DingtalkRobotBindingDO first = binding(7L, "rc-first");
        DingtalkRobotBindingDO selected = binding(8L, "rc-selected");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(first, selected)));
        when(dao.findByRobotCode(1L, "rc-selected")).thenReturn(selected);
        when(bindingService.decryptSecret(selected)).thenReturn("sk-selected");

        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setRobotCode("rc-selected");
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=selected");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);
        doThrow(new RuntimeException(context.getSessionWebhook())).when(sender)
                .sendSessionWebhookText(eq(context.getSessionWebhook()), anyString(), anyList());

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendGroupText(eq("ak-rc-selected"), eq("sk-selected"),
                eq("https://gw-rc-selected"), eq("rc-selected"), eq("openConv"),
                eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
        verify(reactionService).recallThinking(selected, "openConv", "m-1");
        verify(bindingService).markHealth(eq(1L), eq(8L), any(java.util.Date.class), isNull());
        verify(bindingService, never()).markHealth(eq(1L), eq(7L), any(), any());
    }

    @Test
    void sinkUsesStaffIdMentionAndAtUserIdsWhenSenderIdBlank() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        // senderId blank (no LWCP) but senderStaffId present: body carries @<senderStaffId>
        // and atUserIds carries the staff id to trigger the system "@".
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendSessionWebhookText(eq(context.getSessionWebhook()),
                eq("@123456\n\nreply"), eq(List.of("123456")));
    }

    @Test
    void sinkUsesNickMentionAndEmptyAtUserIdsWhenStaffIdBlank() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        // senderStaffId blank: cannot populate atUserIds, so fall back to a readable plain-text
        // @<nick> body with empty atUserIds (no system "@").
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendSessionWebhookMarkdown(eq(context.getSessionWebhook()),
                eq("@张三\n\nreply"), eq(List.of()));
    }

    @Test
    void sinkTreatsInvalidSourceContextAsAbsentAndSendsGroupReply() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext("{invalid");
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendGroupMarkdown(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("openConv"), eq("reply"), eq(List.of()), anyLong());
        verify(reactionService).recallThinking(binding, "openConv", "m-1");
        verify(bindingService).markHealth(eq(1L), eq(7L), any(java.util.Date.class), isNull());
        verify(bindingService, never()).markHealth(eq(1L), eq(7L), isNull(), anyString());
    }

    @Test
    void sinkThrowsForSingleChatFallbackWithoutOpenPlatformUserId() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        // 1:1 with no fresh sessionWebhook: the proactive single-chat fallback uses
        // /v10/robot/oToMessages/batchSend, whose userIds field expects an open-platform userId.
        // senderStaffId (a staff id) is only proven for the group/sessionWebhook reply paths
        // covered by this workitem; whether it is accepted by the single-chat endpoint is
        // unverified, so out of the workitem's scope this path still fails cleanly rather than
        // silently sending to an unverified id.
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("123456");
        context.setConversationType("1");
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> sink.deliverReply(conv, "reply", "m-1"));

        assertEquals("missing open-platform userId for DingTalk single chat reply",
                error.getMessage());
        verify(sender, never()).sendGroupMarkdown(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList(), anyLong());
        verify(sender, never()).sendSingleMarkdown(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyString(), anyLong());
        verify(bindingService).markHealth(eq(1L), eq(7L), isNull(),
                eq("missing open-platform userId for DingTalk single chat reply"));
    }

    @Test
    void sinkUsesSessionWebhookForSingleChatWhenFresh() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        // 1:1 chat with a fresh sessionWebhook: the reply goes via sessionWebhook (which works
        // for 1:1 within its 1h validity), NOT via the proactive single-chat fallback. Body uses
        // @<senderStaffId>; atUserIds carries the staff id.
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("s999");
        context.setConversationType("1");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        verify(sender).sendSessionWebhookText(
                eq("https://oapi.dingtalk.com/robot/sendBySession?session=s1"),
                eq("@s999\n\nreply"), eq(List.of("s999")));
        verify(sender, never()).sendSingleMarkdown(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyString(), anyLong());
        verify(sender, never()).sendGroupMarkdown(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList(), anyLong());
    }

    @Test
    void sinkUsesStaffIdForAtUserIdsAndExcludesLwcpSenderId() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("u123");
        context.setSenderNick("张三");
        context.setSenderStaffId("s999");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);

        sink.deliverReply(conv, "reply", "m-1");

        // Regression guard for the LWCP-as-userId bug: senderId here stands in for a LWCP
        // conversation id ("$:LWCP_v1:$..."). It must NOT appear in the body (DingTalk would
        // render the raw token) nor in atUserIds. The body uses the senderStaffId; atUserIds
        // carries the senderStaffId so the system "@" fires.
        ArgumentCaptor<String> outgoingCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> atUserIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sender).sendSessionWebhookText(eq(context.getSessionWebhook()),
                outgoingCaptor.capture(), atUserIdsCaptor.capture());
        assertTrue(outgoingCaptor.getValue().startsWith("@s999"),
                "outgoing should start with @<senderStaffId>, got: " + outgoingCaptor.getValue());
        assertFalse(outgoingCaptor.getValue().contains("u123"),
                "LWCP senderId must not appear in the body, got: " + outgoingCaptor.getValue());
        assertEquals(List.of("s999"), atUserIdsCaptor.getValue(),
                "atUserIds must carry senderStaffId; LWCP senderId is not a valid at userId");
    }

    @Test
    void sinkDeliversAtUserIds220791OnSessionWebhookAndGroupFallbackPerAcceptance() {
        DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        DingTalkMessageReactionService reactionService = mock(DingTalkMessageReactionService.class);
        AgentConversationTurnDao turnDao = mock(AgentConversationTurnDao.class);
        DingTalkChannelSink sink =
                new DingTalkChannelSink(dao, bindingService, sender, reactionService, turnDao);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");

        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        when(dao.listByTenant(1L)).thenReturn(new java.util.ArrayList<>(List.of(binding)));
        when(bindingService.decryptSecret(binding)).thenReturn("sk");

        // Acceptance payload (workitem 12556 DB evidence): senderStaffId=220791,
        // senderNick=蔡何, senderId=$:LWCP_v1:$...
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setSenderId("$:LWCP_v1:$UytbB3eTamJpQLKTgOpnlQ==");
        context.setSenderNick("蔡何");
        context.setSenderStaffId("220791");
        context.setConversationType("2");
        context.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        context.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        AgentConversationTurnDO sourceTurn = new AgentConversationTurnDO();
        sourceTurn.setSourceContext(context.toJson());
        when(turnDao.findByExternalMsgId(1L, "m-1")).thenReturn(sourceTurn);
        // Force the sessionWebhook path to throw so the proactive group fallback is also exercised.
        doThrow(new RuntimeException("webhook failed")).when(sender)
                .sendSessionWebhookText(eq(context.getSessionWebhook()), anyString(), anyList());

        sink.deliverReply(conv, "reply", "m-1");

        // Both the sessionWebhook path and the proactive group fallback path receive
        // atUserIds=["220791"]; the body mention uses @<senderStaffId> to match.
        verify(sender).sendSessionWebhookText(eq(context.getSessionWebhook()),
                eq("@220791\n\nreply"), eq(List.of("220791")));
        verify(sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("openConv"), eq("@220791\n\nreply"), eq(List.of("220791")), anyLong());
        verify(reactionService).recallThinking(binding, "openConv", "m-1");
    }

    @Test
    void channelIsDingtalk() {
        DingTalkChannelSink sink = new DingTalkChannelSink(
                mock(DingtalkRobotBindingDao.class), mock(DingTalkBindingService.class),
                mock(DingTalkOutboundSender.class), mock(DingTalkMessageReactionService.class),
                mock(AgentConversationTurnDao.class));
        assertEquals("DINGTALK", sink.channel());
    }

    private AgentConversationDO conversation() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId("openConv");
        return conv;
    }

    private DingtalkRobotBindingDO binding(Long id, String robotCode) {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(id);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak-" + robotCode);
        binding.setRobotCode(robotCode);
        binding.setBaseUrl("https://gw-" + robotCode);
        return binding;
    }
}
