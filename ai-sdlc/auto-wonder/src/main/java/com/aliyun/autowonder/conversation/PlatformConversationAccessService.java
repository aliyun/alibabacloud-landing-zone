package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 平台管家对话的唯一可见性闸门。
 *
 * <p>创建会话的人就是不可变更的 Owner：读写永远只属于 Owner 本人；历史分享记录不再授予访问权限。
 * 这里刻意没有工作空间管理员旁路 —— 管理员能管工作空间，不等于能替别人和他的管家聊天、
 * 更不等于能替别人确认写操作。
 */
@Service
public class PlatformConversationAccessService {

    private final AgentConversationDao conversationDao;
    private final ConversationShareDao shareDao;

    public PlatformConversationAccessService(AgentConversationDao conversationDao,
            ConversationShareDao shareDao) {
        this.conversationDao = conversationDao;
        this.shareDao = shareDao;
    }

    /** 只有 Owner 可以读会话正文；其余人拿到的是与「不存在」无法区分的错误。 */
    public AgentConversationDO requireBodyRead(Long tenantId, Long conversationId, Long userId) {
        AgentConversationDO conversation = loadPlatformConversation(tenantId, conversationId);
        if (isOwner(conversation, userId)) {
            return conversation;
        }
        throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION);
    }

    /** 改名、归档、删除、发消息、确认写操作都只认 Owner。 */
    public AgentConversationDO requireBodyWrite(Long tenantId, Long conversationId, Long userId) {
        AgentConversationDO conversation = loadPlatformConversation(tenantId, conversationId);
        if (isOwner(conversation, userId)) {
            return conversation;
        }
        throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION);
    }

    public boolean isOwner(AgentConversationDO conversation, Long userId) {
        Long owner = conversation == null ? null : conversation.getOwnerUserId();
        return owner != null && userId != null && owner.longValue() == userId.longValue();
    }

    /**
     * 遗留渠道（钉钉、工单澄清）的会话在这里一律视为不存在：
     * 平台管家入口不能成为绕过工单权限读别人澄清记录的后门，反之亦然。
     */
    private AgentConversationDO loadPlatformConversation(Long tenantId, Long conversationId) {
        if (tenantId == null || conversationId == null) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION);
        }
        AgentConversationDO conversation = conversationDao.findById(tenantId, conversationId);
        if (conversation == null
                || !PlatformConversationChannel.CHANNEL.equals(conversation.getChannel())
                || conversation.getDeletedAt() != null) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION);
        }
        return conversation;
    }

}
