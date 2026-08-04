package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface DispatchDao {
    void insert(DispatchDO d);

    DispatchDO findById(@Param("id") Long id);

    DispatchDO findByIdempotencyKey(@Param("tenantId") Long tenantId,
                                    @Param("idempotencyKey") String idempotencyKey);

    Integer findMaxAttempt(@Param("tenantId") Long tenantId,
                           @Param("workitemId") Long workitemId,
                           @Param("sdlcStepId") Long sdlcStepId);

    /** Optimistic status transition; also sets agentVersionId/executorId/packageOssRef/error/resultSummary when provided. */
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("status") String status,
                     @Param("agentVersionId") Long agentVersionId,
                     @Param("executorId") Long executorId,
                     @Param("packageOssRef") String packageOssRef,
                     @Param("resultSummary") String resultSummary,
                     @Param("error") String error,
                     @Param("version") Integer version,
                     @Param("modifierId") Long modifierId);

    /** All dispatches on the same workitem, oldest first. */
    List<DispatchDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                    @Param("workitemId") Long workitemId);

    /** Latest dispatch (highest id) per workitem for the given ids. Tenant scoping is applied automatically. */
    List<DispatchDO> listLatestByWorkitemIds(@Param("workitemIds") List<Long> workitemIds);

    /** All dispatches for the given workitem ids, for batch enrichment (e.g. active-status checks). */
    List<DispatchDO> listByWorkitemIds(@Param("workitemIds") Collection<Long> workitemIds);

    List<DispatchDO> listLatestByWorkitemAndAgent(@Param("tenantId") Long tenantId,
                                                   @Param("workitemId") Long workitemId,
                                                   @Param("agentId") Long agentId,
                                                   @Param("limit") int limit);

    /** SUCCEEDED dispatches on the same workitem, oldest first — teammate outputs. */
    List<DispatchDO> listSucceededByWorkitem(@Param("tenantId") Long tenantId,
                                             @Param("workitemId") Long workitemId);

    /** Stuck rows for compensation: status in the given set, older than the cutoff. */
    List<DispatchDO> listStuck(@Param("statuses") List<String> statuses,
                               @Param("beforeEpochMillis") long beforeEpochMillis,
                               @Param("limit") int limit);

    /** Tenant-scoped, filtered, paged dispatch list, newest first. */
    List<DispatchDO> listByTenant(@Param("tenantId") Long tenantId,
                                  @Param("status") String status,
                                  @Param("agentId") Long agentId,
                                  @Param("workitemId") Long workitemId,
                                  @Param("since") Date since,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /** Count matching the same filters, for pagination totals. */
    long countByTenant(@Param("tenantId") Long tenantId,
                       @Param("status") String status,
                       @Param("agentId") Long agentId,
                       @Param("workitemId") Long workitemId,
                       @Param("since") Date since);

    long countActiveByExecutor(@Param("executorId") Long executorId);

    List<DispatchDO> listOldestPendingByAgent(@Param("agentId") Long agentId,
                                               @Param("limit") int limit);

    int returnDispatchedToPending(@Param("id") Long id,
                                  @Param("tenantId") Long tenantId,
                                  @Param("executorId") Long executorId,
                                  @Param("version") Integer version,
                                  @Param("modifierId") Long modifierId);

    int returnPackagingToPending(@Param("id") Long id,
                                 @Param("tenantId") Long tenantId,
                                 @Param("version") Integer version,
                                 @Param("modifierId") Long modifierId);

    int returnOwnedActiveToPending(@Param("id") Long id,
                                   @Param("tenantId") Long tenantId,
                                   @Param("executorId") Long executorId,
                                   @Param("version") Integer version,
                                   @Param("modifierId") Long modifierId);

    int touchOwnedActive(@Param("tenantId") Long tenantId,
                         @Param("executorId") Long executorId,
                         @Param("dispatchIds") List<Long> dispatchIds);

    /** Atomically fail a PAUSING row only when no heartbeat has refreshed it since the cutoff. */
    int failStalePausing(@Param("id") Long id,
                         @Param("tenantId") Long tenantId,
                         @Param("beforeEpochMillis") long beforeEpochMillis,
                         @Param("error") String error,
                         @Param("modifierId") Long modifierId);

    int claimOwnedActive(@Param("id") Long id,
                         @Param("tenantId") Long tenantId,
                         @Param("executorId") Long executorId);
}
