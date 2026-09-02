package com.aliyun.autowonder.scheduledtask;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface ScheduledTaskRunDao {
    int insert(ScheduledTaskRunDO run);

    ScheduledTaskRunDO findByTriggerKey(@Param("workspaceId") Long workspaceId,
                                        @Param("triggerKey") String triggerKey);

    ScheduledTaskRunDO findById(@Param("workspaceId") Long workspaceId, @Param("id") Long id);

    List<ScheduledTaskRunDO> listByTask(@Param("workspaceId") Long workspaceId,
                                       @Param("scheduledTaskId") Long scheduledTaskId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    List<ScheduledTaskRunDO> findActiveByTask(@Param("workspaceId") Long workspaceId,
                                              @Param("scheduledTaskId") Long scheduledTaskId);

    /**
     * Current (not snapshot) read used after the parent ScheduledTask row is
     * locked for an overlap decision.  Under MySQL REPEATABLE READ a plain
     * select can otherwise retain a snapshot from the preliminary task lookup.
     */
    List<ScheduledTaskRunDO> findActiveByTaskForUpdate(@Param("workspaceId") Long workspaceId,
                                                       @Param("scheduledTaskId") Long scheduledTaskId);
    long countActive();
    long countCompletedByTaskSince(@Param("workspaceId") Long workspaceId, @Param("scheduledTaskId") Long scheduledTaskId, @Param("since") Date since);
    long countSucceededByTaskSince(@Param("workspaceId") Long workspaceId, @Param("scheduledTaskId") Long scheduledTaskId, @Param("since") Date since);

    ScheduledTaskRunDO findNextQueued(@Param("workspaceId") Long workspaceId,
                                      @Param("scheduledTaskId") Long scheduledTaskId);

    List<ScheduledTaskRunDO> listStaleStarting(@Param("before") Date before,
                                               @Param("limit") int limit);

    List<ScheduledTaskRunDO> listStaleQueued(@Param("before") Date before,
                                             @Param("limit") int limit);

    int updateStatus(@Param("workspaceId") Long workspaceId,
                     @Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("targetStatus") String targetStatus,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("modifierId") Long modifierId);

    int updateCurrentAssignment(@Param("workspaceId") Long workspaceId,
                                @Param("id") Long id,
                                @Param("sdlcId") Long sdlcId,
                                @Param("currentAgentId") Long currentAgentId,
                                @Param("currentStepId") Long currentStepId,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("modifierId") Long modifierId);

    int initializeExecution(@Param("workspaceId") Long workspaceId,
                            @Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("sdlcId") Long sdlcId,
                            @Param("currentAgentId") Long currentAgentId,
                            @Param("currentStepId") Long currentStepId,
                            @Param("expectedVersion") Integer expectedVersion,
                            @Param("modifierId") Long modifierId);

    int updateTerminalResult(@Param("workspaceId") Long workspaceId,
                             @Param("id") Long id,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("targetStatus") String targetStatus,
                             @Param("resultSummary") String resultSummary,
                             @Param("error") String error,
                             @Param("expectedVersion") Integer expectedVersion,
                             @Param("modifierId") Long modifierId);

    int markDegraded(@Param("workspaceId") Long workspaceId,
                     @Param("id") Long id,
                     @Param("degradedReason") String degradedReason,
                     @Param("resumeFromRunId") Long resumeFromRunId,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("modifierId") Long modifierId);

    int markResumeSource(@Param("workspaceId") Long workspaceId,
                         @Param("id") Long id,
                         @Param("resumeFromRunId") Long resumeFromRunId,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("modifierId") Long modifierId);
    int markCancelPending(@Param("workspaceId") Long workspaceId, @Param("id") Long id,
                          @Param("expectedVersion") Integer expectedVersion, @Param("modifierId") Long modifierId);
    int markCancelIntent(@Param("workspaceId") Long workspaceId, @Param("id") Long id,
                         @Param("expectedVersion") Integer expectedVersion, @Param("modifierId") Long modifierId);
}
