package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.ConversationTurnUsage;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentConversationUsageServiceTest {

    private final AgentConversationUsageDao usageDao = mock(AgentConversationUsageDao.class);
    private final AgentConversationUsageService service =
            new AgentConversationUsageService(usageDao);

    private AgentConversationDO conversation(String channel, String bizRefType, Long bizRefId) {
        AgentConversationDO conv = new AgentConversationDO();
        conv.setId(77L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setExecutorId(9L);
        conv.setChannel(channel);
        conv.setBizRefType(bizRefType);
        conv.setBizRefId(bizRefId);
        return conv;
    }

    private ConversationTurnUsage usage(String credits) {
        ConversationTurnUsage usage = new ConversationTurnUsage();
        usage.setProvider("qoder");
        usage.setModel("ultimate");
        usage.setInputTokens(1200L);
        usage.setOutputTokens(300L);
        usage.setCacheReadTokens(40L);
        usage.setCacheWriteTokens(10L);
        usage.setReasoningTokens(7L);
        usage.setCredits(credits == null ? null : new BigDecimal(credits));
        return usage;
    }

    private AgentConversationUsageDO captureUpsert() {
        ArgumentCaptor<AgentConversationUsageDO> captor =
                ArgumentCaptor.forClass(AgentConversationUsageDO.class);
        verify(usageDao).upsert(captor.capture());
        return captor.getValue();
    }

    @Test
    void persistsClarificationUsageWithWorkitemBinding() {
        AgentConversationUsageDO row = record(conversation(
                AgentConversationUsageService.CHANNEL_WORKITEM_CLARIFICATION, "WORKITEM", 100L));

        assertEquals(1L, row.getTenantId());
        assertEquals(Long.valueOf(100L), row.getWorkitemId());
        assertEquals(Long.valueOf(77L), row.getConversationId());
        assertEquals(Long.valueOf(55L), row.getTurnId());
        assertEquals(Long.valueOf(3L), row.getAgentId());
        assertEquals(Long.valueOf(9L), row.getExecutorId());
        assertEquals("WORKITEM_CLARIFICATION", row.getChannel());
        assertEquals("qoder", row.getProvider());
        assertEquals("ultimate", row.getModel());
        assertEquals(1200L, row.getInputTokens());
        assertEquals(300L, row.getOutputTokens());
        assertEquals(40L, row.getCacheReadTokens());
        assertEquals(10L, row.getCacheWriteTokens());
        assertEquals(7L, row.getReasoningTokens());
        assertEquals(0, new BigDecimal("0.3512").compareTo(row.getCredits()));
        assertNotNull(row.getUsageAt());
    }

    @Test
    void leavesWorkitemNullForConversationsNotBoundToAWorkitem() {
        AgentConversationUsageDO row = record(conversation("PLATFORM_ASSISTANT", null, null));

        assertNull(row.getWorkitemId());
        assertEquals("PLATFORM_ASSISTANT", row.getChannel());
    }

    @Test
    void leavesWorkitemNullWhenBizRefIsNotAWorkitem() {
        AgentConversationUsageDO row = record(conversation("DINGTALK", "SQUAD", 12L));

        assertNull(row.getWorkitemId());
    }

    @Test
    void persistsTokenOnlyUsageWithNullCredits() {
        AgentConversationUsageDO row = record(conversation("DINGTALK", null, null), usage(null));

        assertNull(row.getCredits());
        assertEquals(1200L, row.getInputTokens());
    }

    @Test
    void skipsUsageWithoutAnyPositiveConsumption() {
        ConversationTurnUsage empty = new ConversationTurnUsage();
        empty.setInputTokens(0L);
        empty.setOutputTokens(0L);
        empty.setCredits(BigDecimal.ZERO);

        service.recordTurnUsage(1L, conversation("DINGTALK", null, null), 55L, empty);
        service.recordTurnUsage(1L, conversation("DINGTALK", null, null), 55L, null);
        service.recordTurnUsage(null, conversation("DINGTALK", null, null), 55L, usage("1"));
        service.recordTurnUsage(1L, null, 55L, usage("1"));
        service.recordTurnUsage(1L, conversation("DINGTALK", null, null), null, usage("1"));

        AgentConversationDO noId = conversation("DINGTALK", null, null);
        noId.setId(null);
        service.recordTurnUsage(1L, noId, 55L, usage("1"));

        verifyNoInteractions(usageDao);
    }

    @Test
    void swallowsPersistFailureSoTheAcknowledgementStillSucceeds() {
        doThrow(new IllegalStateException("table missing")).when(usageDao).upsert(any());

        assertDoesNotThrow(() -> service.recordTurnUsage(1L,
                conversation("DINGTALK", null, null), 55L, usage("1")));
        verify(usageDao).upsert(any());
    }

    @Test
    void normalizesNegativeTokensAndTruncatesOverlongColumns() {
        AgentConversationDO conv = conversation(
                AgentConversationUsageService.CHANNEL_WORKITEM_CLARIFICATION, "WORKITEM", 100L);
        conv.setChannel("c".repeat(100));
        ConversationTurnUsage usage = usage("1");
        usage.setProvider("p".repeat(100));
        usage.setModel("m".repeat(200));
        usage.setInputTokens(-3L);
        usage.setCacheWriteTokens(-1L);

        AgentConversationUsageDO row = record(conv, usage);

        assertEquals(64, row.getChannel().length());
        assertEquals(64, row.getProvider().length());
        assertEquals(128, row.getModel().length());
        assertEquals(0L, row.getInputTokens());
        assertEquals(0L, row.getCacheWriteTokens());
    }

    @Test
    void defaultsBlankStringsWhenRuntimeOmitsProviderAndModel() {
        AgentConversationDO conv = conversation("DINGTALK", null, null);
        conv.setChannel(null);
        ConversationTurnUsage usage = usage("1");
        usage.setProvider(null);
        usage.setModel(null);

        AgentConversationUsageDO row = record(conv, usage);

        assertEquals("", row.getChannel());
        assertEquals("", row.getProvider());
        assertEquals("", row.getModel());
    }

    @Test
    void listsClarificationUsageByWorkitemAndChannel() {
        List<AgentConversationUsageDO> rows = List.of(new AgentConversationUsageDO());
        when(usageDao.listByWorkitemId(1L, 100L, "WORKITEM_CLARIFICATION")).thenReturn(rows);

        assertEquals(rows, service.listByWorkitem(1L, 100L, "WORKITEM_CLARIFICATION"));
        verify(usageDao).listByWorkitemId(1L, 100L, "WORKITEM_CLARIFICATION");
    }

    private AgentConversationUsageDO record(AgentConversationDO conv) {
        return record(conv, usage("0.3512"));
    }

    private AgentConversationUsageDO record(AgentConversationDO conv, ConversationTurnUsage usage) {
        service.recordTurnUsage(conv.getTenantId(), conv, 55L, usage);
        return captureUpsert();
    }
}
