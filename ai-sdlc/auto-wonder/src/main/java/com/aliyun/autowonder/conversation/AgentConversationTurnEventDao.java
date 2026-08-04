package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface AgentConversationTurnEventDao {
    int insertChunkIfAbsent(AgentConversationTurnEventDO event);

    List<AgentConversationTurnEventDO> listLogicalEventChunks(
            @Param("tenantId") Long tenantId,
            @Param("turnId") Long turnId,
            @Param("dispatchAttempt") int dispatchAttempt,
            @Param("eventSeq") long eventSeq);

    List<AgentConversationTurnEventDO> listCompletedAfter(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    int deleteExpiredBatch(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
