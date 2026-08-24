package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface WorkspaceMemberDao {
    void insert(WorkspaceMemberDO member);
    void insertOrActivate(WorkspaceMemberDO member);
    WorkspaceMemberDO findByWorkspaceAndUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
    WorkspaceMemberDO findByWorkspaceAndUserForUpdate(@Param("tenantId") Long tenantId,
                                          @Param("userId") Long userId);
    List<WorkspaceMemberDO> listByTenant(@Param("tenantId") Long tenantId);
    int updateAccessLevel(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                          @Param("accessLevel") String accessLevel,
                          @Param("modifierId") Long modifierId);
    int updateIdentityTags(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                           @Param("identityTags") String identityTags,
                           @Param("modifierId") Long modifierId);
    int softDelete(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                   @Param("modifierId") Long modifierId);

    boolean isSoleAdminOfAnyWorkspace(@Param("userId") Long userId);
}
