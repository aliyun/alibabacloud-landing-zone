package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;

@Mapper
public interface AgentConversationDao {
    int insert(AgentConversationDO row);

    AgentConversationDO findByKey(@Param("tenantId") Long tenantId,
            @Param("channel") String channel,
            @Param("channelConversationId") String channelConversationId,
            @Param("agentId") Long agentId);

    AgentConversationDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);

    int updateCliSessionRef(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("cliSessionRef") String cliSessionRef);

    int updateExecutor(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("executorId") Long executorId);

    int updateAgentVersion(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("agentVersionId") Long agentVersionId);

    int updateStatusAndLastTurn(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("status") String status, @Param("lastTurnAt") Date lastTurnAt);

    java.util.List<AgentConversationDO> listByBizRef(
            @Param("tenantId") Long tenantId,
            @Param("channel") String channel,
            @Param("bizRefType") String bizRefType,
            @Param("bizRefId") Long bizRefId,
            @Param("agentId") Long agentId);

    AgentConversationDO findLatestByBizRef(
            @Param("tenantId") Long tenantId,
            @Param("channel") String channel,
            @Param("bizRefType") String bizRefType,
            @Param("bizRefId") Long bizRefId,
            @Param("agentId") Long agentId);
}
