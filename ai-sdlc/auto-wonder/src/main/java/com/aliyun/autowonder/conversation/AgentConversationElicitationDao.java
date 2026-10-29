package com.aliyun.autowonder.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface AgentConversationElicitationDao {

    List<AgentConversationElicitationDO> listDueAnswers(@Param("now") Date now, @Param("limit") int limit);
    int advanceAnswerDelivery(@Param("id") long id, @Param("answerId") String answerId, @Param("stage") String stage);
    int claimAnswerDelivery(@Param("id") long id, @Param("now") Date now, @Param("next") Date next);
    List<AgentConversationElicitationDO> listQuestionHistory(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId);
    List<AgentConversationElicitationDO> listAnswerDeliveries(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId);
    AgentConversationElicitationDO findByRequestIdForUpdate(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId, @Param("requestId") String requestId);
    int closeAnswerDeliveriesForTurn(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId, @Param("turnId") long turnId);
    int finishAnswerDeliveriesForTurn(@Param("tenantId") long tenantId,@Param("executorId") long executorId,
            @Param("conversationId") long conversationId,@Param("turnId") long turnId,@Param("attempt") int attempt);
    int finishAnswerDelivery(@Param("id") long id, @Param("answerId") String answerId);
    int saveAnswerIfPending(AgentConversationElicitationDO row);

    /** 事件可能因分片重投而重复到达，用唯一键做幂等插入。 */
    int insertIfAbsent(AgentConversationElicitationDO record);

    AgentConversationElicitationDO findByRequestId(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("requestId") String requestId);

    List<AgentConversationElicitationDO> listPendingByTurn(@Param("tenantId") long tenantId,
            @Param("turnId") long turnId);

    List<AgentConversationElicitationDO> listPendingByConversation(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId);

    /**
     * 仅当当前仍为 PENDING 时才落终态。返回受影响行数，供调用方判断
     * 是否抢到了这次状态转移（并发回答 / 回答与过期竞争）。
     */
    int settleIfPending(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("requestId") String requestId,
            @Param("status") String status,
            @Param("answerJson") String answerJson);

    /**
     * 把记录补偿回 PENDING，仅当它仍停在 {@code expectedStatus} 时才生效。
     *
     * <p>回答已落终态但投递失败时用它回滚，否则卡片永远是 ANSWERED，用户拿到
     * 错误却再也无法重答。限定当前状态是为了不覆盖并发赢家（过期 / 取消）。
     */
    int restorePendingIfStatus(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId,
            @Param("requestId") String requestId,
            @Param("expectedStatus") String expectedStatus);

    List<AgentConversationElicitationDO> listPendingOlderThan(@Param("cutoff") Date cutoff,
            @Param("afterId") long afterId, @Param("limit") int limit);
}
