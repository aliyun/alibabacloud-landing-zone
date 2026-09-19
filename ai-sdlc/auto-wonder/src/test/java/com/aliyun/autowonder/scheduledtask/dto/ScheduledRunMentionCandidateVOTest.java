package com.aliyun.autowonder.scheduledtask.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledRunMentionCandidateVOTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAndDeserializesTheIsAgentFlagAndMentionState() throws Exception {
        ScheduledRunMentionCandidateVO vo = new ScheduledRunMentionCandidateVO();
        vo.setUserId(12L);
        vo.setTargetType("AGENT");
        vo.setName("CodeBot");
        vo.setAgent(true);
        vo.setOnline(true);
        vo.setMentionable(false);
        vo.setMentionDisabledReason("不在本次运行的冻结快照中，无法 @ 触发执行");

        String json = objectMapper.writeValueAsString(vo);

        assertTrue(json.contains("\"isAgent\":true"));
        assertTrue(json.contains("\"mentionable\":false"));
        assertTrue(json.contains("\"mentionDisabledReason\":\"不在本次运行的冻结快照中，无法 @ 触发执行\""));

        ScheduledRunMentionCandidateVO parsed = objectMapper.readValue(json, ScheduledRunMentionCandidateVO.class);
        assertTrue(parsed.isAgent());
        assertEquals(12L, parsed.getUserId());
        assertEquals("AGENT", parsed.getTargetType());
        assertFalse(parsed.isMentionable());
        assertEquals("不在本次运行的冻结快照中，无法 @ 触发执行", parsed.getMentionDisabledReason());
    }
}
