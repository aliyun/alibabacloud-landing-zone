package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WsConversationTransportCommandsTest {
    @Test
    void probeFrameCarriesTypeExecutorAndConversation() {
        JSONObject frame = WsConversationTransport.buildCommandsProbeFrame(7L, 42L);
        assertEquals("CONVERSATION_COMMANDS_PROBE", frame.getString("type"));
        assertEquals(7L, frame.getLongValue("executorId"));
        assertEquals(42L, frame.getLongValue("conversationId"));
    }
}
