package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.ConversationTurnUsage;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/** 会话轮次用量的落库入口：写入失败不得影响会话 ACK 主流程。 */
@Service
public class AgentConversationUsageService {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationUsageService.class);

    public static final String CHANNEL_WORKITEM_CLARIFICATION = "WORKITEM_CLARIFICATION";
    public static final String BIZ_REF_TYPE_WORKITEM = "WORKITEM";
    private static final int CHANNEL_MAX_LENGTH = 64;
    private static final int PROVIDER_MAX_LENGTH = 64;
    private static final int MODEL_MAX_LENGTH = 128;

    private final AgentConversationUsageDao usageDao;

    public AgentConversationUsageService(AgentConversationUsageDao usageDao) {
        this.usageDao = usageDao;
    }

    public void recordTurnUsage(Long tenantId, AgentConversationDO conv, Long turnId,
                                ConversationTurnUsage usage) {
        if (tenantId == null || conv == null || conv.getId() == null || turnId == null
                || usage == null || !usage.hasPositiveUsage()) {
            return;
        }
        AgentConversationUsageDO row = new AgentConversationUsageDO();
        row.setTenantId(tenantId);
        row.setWorkitemId(workitemIdOf(conv));
        row.setConversationId(conv.getId());
        row.setTurnId(turnId);
        row.setAgentId(conv.getAgentId());
        row.setExecutorId(conv.getExecutorId());
        row.setChannel(truncate(conv.getChannel(), CHANNEL_MAX_LENGTH));
        row.setProvider(truncate(usage.getProvider(), PROVIDER_MAX_LENGTH));
        row.setModel(truncate(usage.getModel(), MODEL_MAX_LENGTH));
        row.setInputTokens(zeroIfNull(usage.getInputTokens()));
        row.setOutputTokens(zeroIfNull(usage.getOutputTokens()));
        row.setCacheReadTokens(zeroIfNull(usage.getCacheReadTokens()));
        row.setCacheWriteTokens(zeroIfNull(usage.getCacheWriteTokens()));
        row.setReasoningTokens(zeroIfNull(usage.getReasoningTokens()));
        row.setCredits(usage.getCredits());
        row.setUsageAt(new Date());
        try {
            usageDao.upsert(row);
        } catch (RuntimeException ex) {
            // 用量表缺失或写入失败都不能影响会话回复；下一轮 ACK 仍会继续尝试。
            log.warn("conversation usage persist failed conversationId={} turnId={} channel={}",
                    conv.getId(), turnId, conv.getChannel(), ex);
        }
    }

    public List<AgentConversationUsageDO> listByWorkitem(long tenantId, long workitemId,
                                                         String channel) {
        return usageDao.listByWorkitemId(tenantId, workitemId, channel);
    }

    private Long workitemIdOf(AgentConversationDO conv) {
        return BIZ_REF_TYPE_WORKITEM.equals(conv.getBizRefType()) ? conv.getBizRefId() : null;
    }

    private static Long zeroIfNull(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
