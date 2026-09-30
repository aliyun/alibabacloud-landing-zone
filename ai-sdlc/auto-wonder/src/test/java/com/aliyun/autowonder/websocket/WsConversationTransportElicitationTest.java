package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.ConversationCapabilityService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 与执行器 daemon/wsclient.ConversationElicitationReplyFrame 的字段契约。
 * 任何一侧改字段名都必须在这里断掉。
 */
class WsConversationTransportElicitationTest {

    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final RedisManager redisManager = mock(RedisManager.class);
    private final ConversationCapabilityService capabilityService =
            mock(ConversationCapabilityService.class);
    private final WsConversationTransport transport =
            new WsConversationTransport(sessionRegistry, redisManager, capabilityService);

    @Test
    void acceptFrameMatchesRuntimeContract() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                9L, 11L, 22L, "deadbeef", "accept", "{\"q0\":\"Next.js\"}");

        assertEquals("CONVERSATION_ELICITATION_REPLY", frame.getString("type"));
        assertEquals(11L, frame.getLongValue("conversationId"));
        assertEquals(22L, frame.getLongValue("turnId"));
        assertEquals("deadbeef", frame.getString("requestId"));
        assertEquals("accept", frame.getString("action"));
        assertEquals("Next.js", frame.getJSONObject("content").getString("q0"));
    }

    @Test
    void declineFrameOmitsContent() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                9L, 11L, 22L, "deadbeef", "decline", null);

        assertEquals("decline", frame.getString("action"));
        assertFalse(frame.containsKey("content"),
                "decline 不该带 content，执行器会忽略但契约要干净");
    }

    @Test
    void cancelFrameOmitsBlankContent() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                9L, 11L, 22L, "deadbeef", "cancel", "   ");

        assertEquals("cancel", frame.getString("action"));
        assertFalse(frame.containsKey("content"),
                "空白 answerJson 等同于没有答案，不能塞出一个畸形 content");
    }

    /**
     * Redis 广播兜底靠 NodeMailboxListener 按 executorId 找本机会话，
     * 帧里没有 executorId 时跨节点投递会被静默丢弃。
     */
    @Test
    void frameCarriesExecutorIdForCrossNodeBroadcastRouting() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                9L, 11L, 22L, "deadbeef", "accept", "{\"q0\":\"A\"}");

        assertEquals(9L, frame.getLongValue("executorId"));
    }

    @Test
    void sendElicitationReplyPublishesFrameForBoundExecutor() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setExecutorId(9L);
        when(sessionRegistry.findByExecutorId(9L)).thenReturn(null);

        transport.sendElicitationReply(conv, 22L, "deadbeef", "accept", "{\"q0\":\"A\"}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisManager).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), payload.capture());
        String sent = payload.getValue();
        assertTrue(sent.contains("\"type\":\"CONVERSATION_ELICITATION_REPLY\""));
        assertTrue(sent.contains("\"requestId\":\"deadbeef\""));
        assertTrue(sent.contains("\"content\":{\"q0\":\"A\"}"));
        // 卡片回答不重新协商能力快照，不该触碰 capabilityService。
        verifyNoInteractions(capabilityService);
    }

    /** 没有绑定执行器就没有投递目标，不能盲发一个无处可去的帧。 */
    @Test
    void sendElicitationReplyRejectsConversationWithoutExecutor() {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(11L);

        assertThrows(IllegalArgumentException.class, () -> transport.sendElicitationReply(
                conv, 22L, "deadbeef", "accept", "{\"q0\":\"A\"}"));
        verifyNoInteractions(redisManager);
    }

    /**
     * 字段集封闭契约。执行器那侧有一份对称的断言
     * （daemon/wsclient TestConversationElicitationReplyFieldSetIsClosed）。
     *
     * <p>跨仓库的帧是唯一没有编译器保护的接口，任何一方悄悄加字段都会在这里
     * 断掉，迫使新增是有意为之。
     */
    @Test
    void frameFieldSetStaysClosed() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                7L, 11L, 22L, "deadbeef", "accept", "{\"q0\":\"A\"}");

        Set<String> expected = new HashSet<>(Arrays.asList(
                "type", "executorId", "conversationId", "turnId", "requestId", "action", "content"));
        assertEquals(expected, frame.keySet(),
                "帧字段集变了；请同时更新执行器侧的对称契约测试");
    }

    /**
     * executorId 不是执行器读取的业务字段，但 Redis 跨节点广播兜底靠它定位
     * 本机会话，缺失会让跨节点投递被静默丢弃。
     */
    @Test
    void frameCarriesExecutorIdForCrossNodeDelivery() {
        JSONObject frame = WsConversationTransport.buildElicitationReplyFrame(
                7L, 11L, 22L, "deadbeef", "decline", null);

        assertEquals(7L, frame.getLongValue("executorId"));
    }
}
