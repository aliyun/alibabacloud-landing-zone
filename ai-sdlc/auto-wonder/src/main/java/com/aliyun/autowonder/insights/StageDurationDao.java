package com.aliyun.autowonder.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

@Mapper
public interface StageDurationDao {
    List<StageDurationService.Execution> listExecutions(@Param("tenantId") long tenantId,
            @Param("start") Date start, @Param("end") Date end,
            @Param("squadId") Long squadId, @Param("sdlcId") Long sdlcId,
            @Param("limit") int limit);

    List<StageDurationService.Event> listEvents(@Param("tenantId") long tenantId,
            @Param("dispatchIds") List<Long> dispatchIds);
}
