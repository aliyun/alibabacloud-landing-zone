package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface MemberDeliveryDao {
    List<MemberDeliveryService.Member> members(@Param("tenantId") long tenantId);
    List<MemberDeliveryService.Counts> counts(@Param("tenantId") long tenantId,
            @Param("start") Date start, @Param("end") Date end);
}
