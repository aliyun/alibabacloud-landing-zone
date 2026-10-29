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

    /**
     * 取某一轮次的全部事件，按 event_seq / chunk_index 保序，供前端按需回放。
     * 必须带 limit：执行器无合并节流，一个 token 级 chunk 就是一行记录。
     */
    List<AgentConversationTurnEventDO> listByTurn(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("turnId") Long turnId,
            @Param("limit") int limit);

    List<AgentConversationTurnEventDO> listByTurnAfter(
            @Param("tenantId") Long tenantId, @Param("conversationId") Long conversationId,
            @Param("turnId") Long turnId, @Param("afterId") long afterId, @Param("limit") int limit);

    int deleteExpiredBatch(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
