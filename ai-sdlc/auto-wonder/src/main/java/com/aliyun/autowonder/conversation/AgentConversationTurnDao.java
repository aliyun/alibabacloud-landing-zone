package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface AgentConversationTurnDao {
    int insert(AgentConversationTurnDO row);

    AgentConversationTurnDO findByExternalMsgId(@Param("tenantId") Long tenantId,
            @Param("externalMsgId") String externalMsgId);

    AgentConversationTurnDO findByConversationTurn(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id);

    AgentConversationTurnDO findProcessingInbound(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId);

    AgentConversationTurnDO findNextQueuedInbound(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId);

    List<AgentConversationTurnDO> listStaleProcessingInboundByExecutor(
            @Param("tenantId") Long tenantId, @Param("executorId") Long executorId,
            @Param("cutoff") Date cutoff, @Param("limit") int limit);

    List<AgentConversationTurnDO> listStaleProcessingInbound(
            @Param("cutoff") Date cutoff, @Param("limit") int limit);

    Integer acquireConversationLock(@Param("lockName") String lockName,
            @Param("timeoutSeconds") int timeoutSeconds);

    Integer releaseConversationLock(@Param("lockName") String lockName);

    int updateStatus(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("status") String status, @Param("error") String error);

    int updateInboundStatusIfProcessing(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id,
            @Param("status") String status, @Param("error") String error);

    int updateStatusIfCurrent(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
            @Param("error") String error);

    int recordDispatchAttemptIfProcessing(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id);

    int claimStaleDispatchAttemptIfProcessing(@Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId, @Param("id") Long id,
            @Param("cutoff") Date cutoff);

    List<AgentConversationTurnDO> listTurnsByConversation(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId);
}
