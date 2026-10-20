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
    int updateRole(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                   @Param("roleId") Long roleId, @Param("modifierId") Long modifierId);
    int updateIdentityTags(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                           @Param("identityTags") String identityTags,
                           @Param("modifierId") Long modifierId);
    int softDelete(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                   @Param("modifierId") Long modifierId);

    /**
     * 软删除该用户全部有效成员关系（逻辑删除用户时调用，避免空间侧出现幽灵成员/管理员）。
     * 幂等：is_deleted = 0 守卫使重复调用为 no-op。
     */
    int softDeleteAllByUser(@Param("userId") Long userId, @Param("modifierId") Long modifierId);

    /**
     * 该用户最早加入的活跃空间（joined_at 升序第一条），用于平台级事件（审计、通知）的空间归属；
     * 无活跃成员关系时返回 null。
     */
    Long findEarliestActiveWorkspaceId(@Param("userId") Long userId);

    boolean isSoleAdminOfAnyWorkspace(@Param("userId") Long userId);
}
