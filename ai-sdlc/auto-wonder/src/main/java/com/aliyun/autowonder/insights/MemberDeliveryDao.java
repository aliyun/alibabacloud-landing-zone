package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface MemberDeliveryDao {
    List<MemberDeliveryService.Member> members(@Param("tenantId") long tenantId);
    /** 按 (memberId, workType) 分组的周期计数；不接收 work_type 参数，summary 的类型收敛由 Service 完成。 */
    List<MemberDeliveryService.TypeCounts> counts(@Param("tenantId") long tenantId,
            @Param("start") Date start, @Param("end") Date end);
}
