package com.aliyun.autowonder.org;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrgMemberDao {
    void insert(OrgMemberDO member);
    void insertOrActivate(OrgMemberDO member);
    OrgMemberDO findByOrgAndUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
    OrgMemberDO findByOrgAndUserForUpdate(@Param("tenantId") Long tenantId,
                                          @Param("userId") Long userId);
    List<OrgMemberDO> listByTenant(@Param("tenantId") Long tenantId);
    int updateAccessLevel(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                          @Param("accessLevel") String accessLevel,
                          @Param("modifierId") Long modifierId);
    int updateIdentityTags(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                           @Param("identityTags") String identityTags,
                           @Param("modifierId") Long modifierId);
    int softDelete(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                   @Param("modifierId") Long modifierId);

    boolean isSoleAdminOfAnyOrg(@Param("userId") Long userId);
}
