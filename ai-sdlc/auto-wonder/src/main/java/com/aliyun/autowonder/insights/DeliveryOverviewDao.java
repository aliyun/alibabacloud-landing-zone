package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface DeliveryOverviewDao {

    /** 区间 [start, end) 内创建的工单（全部类型）。 */
    List<DeliveryOverviewService.CreatedWorkitem> listCreatedWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间 [start, end) 内创建的工单总数。 */
    long countCreatedWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间 [start, end) 内创建的工单分页明细：创建时间降序（最新在前）。 */
    List<DeliveryOverviewService.CreatedWorkitemRow> listCreatedWorkitemsPage(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end,
            @Param("offset") long offset, @Param("limit") int limit);

    /** 区间 [start, end) 内交付完成的工单数；完成时间取最近一次进入 DONE 类节点的状态事件。 */
    long countDeliveredWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 当前用户待处理工单总数（未完成/取消且处理人为本人的工单）。 */
    long countPendingWorkitems(@Param("tenantId") long tenantId, @Param("currentUserId") long currentUserId);

    /** 当前用户待处理工单分页：待决策优先，再按优先级升序（0 最高）、更新时间降序。 */
    List<DeliveryOverviewService.PendingWorkitemRow> listPendingWorkitems(
            @Param("tenantId") long tenantId, @Param("currentUserId") long currentUserId,
            @Param("offset") long offset, @Param("limit") int limit);
}
