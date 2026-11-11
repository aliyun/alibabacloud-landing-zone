package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkitemEventDao {
    List<com.aliyun.autowonder.workitem.dto.TimelineItemVO> listWindowKeys(
            @Param("tenantId") Long tenantId, @Param("workitemId") long workitemId,
            @Param("offset") long offset);
    void insert(WorkitemEventDO e);
    WorkitemEventDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
    List<WorkitemEventDO> listByWorkitem(@Param("workitemId") Long workitemId);
}
