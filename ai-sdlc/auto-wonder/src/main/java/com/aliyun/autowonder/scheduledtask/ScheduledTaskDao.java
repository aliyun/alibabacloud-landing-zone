package com.aliyun.autowonder.scheduledtask;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskSummaryVO;

@Mapper
public interface ScheduledTaskDao {
    void insert(ScheduledTaskDO task);

    ScheduledTaskDO findById(@Param("workspaceId") Long workspaceId, @Param("id") Long id);

    // Workspace-agnostic lookup for token-authenticated CLI endpoints that resolve
    // the owning workspace from the task itself.
    ScheduledTaskDO findAnyById(@Param("id") Long id);

    ScheduledTaskDO findByIdForUpdate(@Param("workspaceId") Long workspaceId, @Param("id") Long id);

    List<ScheduledTaskDO> listByWorkspace(@Param("workspaceId") Long workspaceId,
                                      @Param("status") String status,
                                      @Param("creatorId") Long creatorId,
                                      @Param("squadId") Long squadId, @Param("keyword") String keyword,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    long countByWorkspace(@Param("workspaceId") Long workspaceId, @Param("status") String status,
                       @Param("creatorId") Long creatorId, @Param("squadId") Long squadId,
                       @Param("keyword") String keyword);
    ScheduledTaskSummaryVO summarizeRuns(@Param("workspaceId") Long workspaceId, @Param("status") String status,
                                         @Param("squadId") Long squadId, @Param("keyword") String keyword);

    List<ScheduledTaskDO> findDue(@Param("now") Date now, @Param("limit") int limit);

    int claimNextFire(@Param("workspaceId") Long workspaceId,
                      @Param("id") Long id,
                      @Param("expectedVersion") Integer expectedVersion,
                      @Param("expectedNextFireAt") Date expectedNextFireAt,
                      @Param("nextFireAt") Date nextFireAt,
                      @Param("lastFireAt") Date lastFireAt,
                      @Param("status") String status,
                      @Param("modifierId") Long modifierId);

    int update(ScheduledTaskDO task);

    int updateStatus(@Param("workspaceId") Long workspaceId,
                     @Param("id") Long id,
                     @Param("expectedStatus") String expectedStatus,
                     @Param("targetStatus") String targetStatus,
                     @Param("version") Integer version,
                     @Param("modifierId") Long modifierId);
}
