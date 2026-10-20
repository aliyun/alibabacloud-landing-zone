package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface WorkspaceRoleDao {
    List<WorkspaceRoleDO> listByTenant(@Param("tenantId") long tenantId);
    WorkspaceRoleDO findById(@Param("tenantId") long tenantId, @Param("id") long id);
    WorkspaceRoleDO findByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    void insert(WorkspaceRoleDO role);
    int update(WorkspaceRoleDO role);
    int softDelete(@Param("tenantId") long tenantId, @Param("id") long id,
                   @Param("version") int version, @Param("modifierId") long modifierId);
    int countAssignments(@Param("tenantId") long tenantId, @Param("id") long id);
}
