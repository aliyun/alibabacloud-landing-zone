package com.aliyun.autowonder.workitem.duplicate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface WorkitemDuplicateCheckDao {
    int insert(WorkitemDuplicateCheckDO row);

    WorkitemDuplicateCheckDO findById(@Param("tenantId") long tenantId, @Param("id") long id);

    /** 后台任务扫描按 id 取行，行自带 tenant_id，不再叠加租户条件。 */
    WorkitemDuplicateCheckDO findByIdGlobal(@Param("id") long id);

    /** 同一操作者、同一表单会话、同一标题指纹的最近一条记录，用于请求去重。 */
    WorkitemDuplicateCheckDO findLive(@Param("tenantId") long tenantId,
            @Param("operatorId") long operatorId,
            @Param("formRequestId") String formRequestId,
            @Param("titleFingerprint") String titleFingerprint);

    long countActiveByTenant(@Param("tenantId") long tenantId);

    /** 全局在途任务数；准入在开关行互斥区内检查，避免并发突破上限。 */
    long countActiveGlobal();

    /** 锁定全局开关行并返回 enabled；缺行返回 null（等价关闭）。 */
    Integer lockSwitchEnabled(@Param("switchKey") String switchKey);

    /** 待处理或租约已过期的任务；从 afterId 之后继续，跨轮游标避免低 id 长期占用。 */
    List<Long> listDueIds(@Param("afterId") long afterId, @Param("now") Date now,
            @Param("limit") int limit);

    List<Long> listExpiredIds(@Param("now") Date now, @Param("limit") int limit);

    /** CAS 领取：只有持有当前领取代次且 PENDING/租约过期的 RUNNING 行能被领取。 */
    int claim(@Param("id") long id, @Param("expectedAttempt") int expectedAttempt,
            @Param("leaseUntil") Date leaseUntil, @Param("now") Date now);

    /** CAS 持久化固定快照；快照一旦存在不可替换。 */
    int saveSnapshot(@Param("id") long id, @Param("expectedAttempt") int expectedAttempt,
            @Param("candidateSnapshotJson") String candidateSnapshotJson,
            @Param("candidateTotal") int candidateTotal, @Param("now") Date now);

    /** 释放租约等待下一次轮询；仍要求持有当前领取代次。 */
    int releaseLease(@Param("id") long id, @Param("expectedAttempt") int expectedAttempt);

    /** 终态 CAS：必须持有当前领取代次；SUCCEEDED 还要求未到总截止。 */
    int finish(@Param("id") long id, @Param("expectedAttempt") int expectedAttempt,
            @Param("status") String status, @Param("matchJson") String matchJson,
            @Param("error") String error, @Param("now") Date now);

    /** 超时终态：只收敛已到期活跃行，不要求调用方持有领取代次。 */
    int expire(@Param("id") long id, @Param("error") String error, @Param("now") Date now);

    List<Long> listCancelPendingIds(@Param("limit") int limit);

    /** 条件领取一次取消待办；返回 1 的实例负责尽力取消。 */
    int claimCancelPending(@Param("id") long id);

    int cancelActiveByWorkitem(@Param("tenantId") long tenantId,
            @Param("workitemId") long workitemId, @Param("operatorId") long operatorId);

    /** 同一表单会话内标题已变化：旧标题的在途检测作废。 */
    int cancelActiveByForm(@Param("tenantId") long tenantId, @Param("operatorId") long operatorId,
            @Param("formRequestId") String formRequestId, @Param("exceptId") long exceptId);

    List<Long> listExpiredDraftIds(@Param("before") Date before, @Param("limit") int limit);

    int deleteByIds(@Param("ids") java.util.Collection<Long> ids);

    /** 检测会话识别：平台内部会话且其 channel_conversation_id 关联本空间的任务 request_id。 */
    long countIntentConversations(@Param("tenantId") long tenantId,
            @Param("conversationId") long conversationId);
}
