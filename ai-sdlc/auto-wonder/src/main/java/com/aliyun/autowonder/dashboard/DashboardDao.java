package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.RecentTaskVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.dashboard.dto.RunningWorkitemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardDao {

    // KPI
    int countRunningDispatches(@Param("tenantId") long tenantId);

    int countTodayCompletedTasks(@Param("tenantId") long tenantId);

    int countWeekCompletedTasks(@Param("tenantId") long tenantId);

    Integer avgTodayCompletedTaskDurationMinutes(@Param("tenantId") long tenantId);

    int countInProgressWorkitems(@Param("tenantId") long tenantId);

    int countQueuedDispatches(@Param("tenantId") long tenantId);

    int countTodayCreatedWorkitems(@Param("tenantId") long tenantId);

    int countRunningWorkitems(@Param("tenantId") long tenantId);

    int countOnlineAgents(@Param("tenantId") long tenantId);

    int countActiveSquads(@Param("tenantId") long tenantId);

    // Inventory
    List<Map<String, Object>> countWorkitemsByLifecycle(@Param("tenantId") long tenantId);

    List<Map<String, Object>> countWorkitemsByType(@Param("tenantId") long tenantId);

    // Squad lines
    List<Map<String, Object>> squadLineAggregates(@Param("tenantId") long tenantId);

    List<Map<String, Object>> squadInProgressWorkitems(@Param("tenantId") long tenantId);

    // Workstations
    List<Map<String, Object>> onlineWorkstations(@Param("tenantId") long tenantId);

    // Health (today)
    int countTodaySucceeded(@Param("tenantId") long tenantId);

    int countTodayFailedOrTimeout(@Param("tenantId") long tenantId);

    int countTodayRetries(@Param("tenantId") long tenantId);

    Integer avgTodaySuccessDurationMinutes(@Param("tenantId") long tenantId);

    // Pipeline health (formal delivery dispatches inside an explicit [start, end) window)
    Map<String, Object> pipelineExecutionStats(@Param("tenantId") long tenantId,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    Map<String, Object> pipelineReworkStats(@Param("tenantId") long tenantId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    Integer avgPipelineWorkitemDurationMinutes(@Param("tenantId") long tenantId,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    // Feeds
    List<RunningTaskVO> listRunningFeed(@Param("tenantId") long tenantId, @Param("limit") int limit);

    List<RecentTaskVO> listRecentFeed(@Param("tenantId") long tenantId, @Param("limit") int limit);

    // KPI detail lists
    List<CompletedWorkitemVO> listTodayCompletedWorkitems(@Param("tenantId") long tenantId);

    List<CompletedWorkitemVO> listWeekCompletedWorkitems(@Param("tenantId") long tenantId);

    List<RunningWorkitemVO> listRunningWorkitems(@Param("tenantId") long tenantId);

    // Lazy expand + validation
    List<RunningTaskVO> listAgentRunning(@Param("tenantId") long tenantId, @Param("agentId") long agentId);

    int agentExists(@Param("tenantId") long tenantId, @Param("agentId") long agentId);
}
