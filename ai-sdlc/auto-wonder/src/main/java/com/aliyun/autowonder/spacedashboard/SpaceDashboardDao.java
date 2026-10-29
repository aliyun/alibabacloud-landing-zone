package com.aliyun.autowonder.spacedashboard;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 空间级看板的跨空间聚合查询。所有查询都只在调用者可见的空间集合内统计；
 * 范围由 {@link SpaceDashboardService#overview} 解析后以 {@code workspaceIds} 传入，
 * 前端无法指定该集合。
 */
@Mapper
public interface SpaceDashboardDao {

    /**
     * 调用者可统计的空间：未停用（{@code status = 0}）、未删除（{@code is_deleted = 0}），
     * 且为平台管理员、空间负责人（owner）或持有 ADMIN 角色
     * （{@code workspace_role.access_level}，不读 {@code org_member.access_level} 迁移字段）。
     */
    List<ScopedWorkspace> listScopedWorkspaces(@Param("userId") long userId,
                                               @Param("systemAdmin") boolean systemAdmin);

    /** 普通数字员工数量（{@code kind = 'STANDARD'}，排除平台内置数字人），按空间分组。 */
    List<WorkspaceCount> countAgents(@Param("workspaceIds") List<Long> workspaceIds);

    /** 未解散的小队数量（{@code status = 0}），按空间分组。 */
    List<WorkspaceCount> countSquads(@Param("workspaceIds") List<Long> workspaceIds);

    /** 有效成员中的真人数量，跨空间按 user_id 去重。 */
    long countDistinctHumans(@Param("workspaceIds") List<Long> workspaceIds);

    /** 区间 [start, end) 内创建的工单数（含已取消、已完成），按空间分组。 */
    List<WorkspaceCount> countCreatedWorkitems(@Param("workspaceIds") List<Long> workspaceIds,
                                               @Param("start") Date start,
                                               @Param("end") Date end,
                                               @Param("workTypes") List<String> workTypes);

    /**
     * 区间内完成的工单数：当前处于 DONE 类状态节点，且最近一次进入 DONE 节点的
     * STATUS_CHANGE 事件时间落在 [start, end) 内，按空间分组。
     */
    List<WorkspaceCount> countCompletedWorkitems(@Param("workspaceIds") List<Long> workspaceIds,
                                                 @Param("start") Date start,
                                                 @Param("end") Date end,
                                                 @Param("workTypes") List<String> workTypes);

    /** 区间内创建且当前未结束（INIT / IN_PROGRESS）的工单数，按空间分组。 */
    List<WorkspaceCount> countInProgressWorkitems(@Param("workspaceIds") List<Long> workspaceIds,
                                                  @Param("start") Date start,
                                                  @Param("end") Date end,
                                                  @Param("workTypes") List<String> workTypes);

    /** 区间内派发与会话两条用量来源的 Credits 合计（NULL 按 0），按空间分组。 */
    List<WorkspaceCredits> sumCredits(@Param("workspaceIds") List<Long> workspaceIds,
                                      @Param("start") Date start,
                                      @Param("end") Date end);
}
