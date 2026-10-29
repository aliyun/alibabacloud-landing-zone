package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 需求交付指标取数。数据洞察「需求交付」tab 已下线（工单 #56970），保留以兼容存量接口，
 * SQL 与映射不再演进。
 */
@Deprecated
@Mapper
public interface RequirementDeliveryDao {

    /** 区间 [start, end) 内已交付的 REQ；完成时间取最近一次进入 DONE 类节点的状态事件。 */
    List<RequirementDeliveryService.DeliveredRequirement> listDeliveredRequirements(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);
}
