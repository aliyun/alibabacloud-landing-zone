package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ExternalStatusMappingDao {
    void insert(ExternalStatusMappingDO mapping);
    ExternalStatusMappingDO findByExternal(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                           @Param("bindingId") Long bindingId, @Param("workType") String workType,
                                           @Param("externalStatusName") String externalStatusName);
    ExternalStatusMappingDO findByStatusNode(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                             @Param("bindingId") Long bindingId, @Param("statusNodeId") Long statusNodeId);
    List<ExternalStatusMappingDO> listByBinding(@Param("tenantId") Long tenantId, @Param("bindingId") Long bindingId);
    int updateExternalIssueType(@Param("id") Long id, @Param("externalIssueTypeId") String externalIssueTypeId);
}
