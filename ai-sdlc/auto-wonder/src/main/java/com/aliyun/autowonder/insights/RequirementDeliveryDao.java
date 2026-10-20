package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface RequirementDeliveryDao {

    /** 区间 [start, end) 内已交付的 REQ；完成时间取最近一次进入 DONE 类节点的状态事件。 */
    List<RequirementDeliveryService.DeliveredRequirement> listDeliveredRequirements(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);
}
