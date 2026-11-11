package com.aliyun.autowonder.frontendproject;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Probe persistence. Every statement carries an explicit {@code workspace_id} predicate:
 * this table uses workspace naming and is deliberately absent from {@code TenantTables},
 * whose rewriter appends {@code tenant_id = ?} and would fail on this schema.
 */
@Mapper
public interface FrontendProjectProbeDao {

    void insert(FrontendProjectProbeDO probe);

    FrontendProjectProbeDO findById(@Param("id") Long id,
                                    @Param("workspaceId") Long workspaceId);

    /**
     * Lookup for the worker/scheduler only. Not workspace-scoped on purpose: the scheduler is a
     * system driver with no space context, and it must never be reachable from an API path.
     */
    FrontendProjectProbeDO findByIdForExecution(@Param("id") Long id);

    /** Most recent probe for a repo, used to answer "what do we already know". */
    FrontendProjectProbeDO findLatestByRepo(@Param("workspaceId") Long workspaceId,
                                            @Param("repoId") Long repoId);

    List<FrontendProjectProbeDO> listByRepo(@Param("workspaceId") Long workspaceId,
                                            @Param("repoId") Long repoId,
                                            @Param("limit") int limit);

    /** QUEUED rows oldest-first, for the worker poll. Claimed via {@link #updateRunning} CAS. */
    List<FrontendProjectProbeDO> listByStatus(@Param("status") String status,
                                              @Param("limit") int limit);

    /** Optimistic state transition; returns 0 when the row moved on under us. */
    int updateStatus(@Param("id") Long id,
                     @Param("workspaceId") Long workspaceId,
                     @Param("status") String status,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("modifierId") Long modifierId);

    int updateRunning(@Param("id") Long id,
                      @Param("workspaceId") Long workspaceId,
                      @Param("nodeId") String nodeId,
                      @Param("expectedVersion") Integer expectedVersion);

    int updateSucceeded(@Param("id") Long id,
                        @Param("workspaceId") Long workspaceId,
                        @Param("branch") String branch,
                        @Param("commitSha") String commitSha,
                        @Param("candidateCount") Integer candidateCount,
                        @Param("expectedVersion") Integer expectedVersion);

    int updateFailed(@Param("id") Long id,
                     @Param("workspaceId") Long workspaceId,
                     @Param("error") String error,
                     @Param("expectedVersion") Integer expectedVersion);

    /**
     * Stuck non-terminal runs for the compensation sweep. Deliberately not workspace-scoped:
     * like {@code AiSessionDao.listStuck}, this is a system-wide liveness repair.
     */
    List<FrontendProjectProbeDO> listStuck(@Param("statuses") List<String> statuses,
                                           @Param("beforeMs") long beforeMs,
                                           @Param("limit") int limit);
}
