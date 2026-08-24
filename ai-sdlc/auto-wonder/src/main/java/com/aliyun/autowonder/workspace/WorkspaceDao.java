package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkspaceDao {
    void insert(WorkspaceDO workspace);
    WorkspaceDO findById(@Param("id") Long id);
    WorkspaceDO findByIdForUpdate(@Param("id") Long id);
    WorkspaceDO findByName(@Param("name") String name);
    List<WorkspaceDO> listByUser(@Param("userId") Long userId);
    List<WorkspaceMembershipDO> listMembershipsByUser(@Param("userId") Long userId);
    int updateOwner(@Param("id") Long id,
                    @Param("oldOwnerId") Long oldOwnerId,
                    @Param("newOwnerId") Long newOwnerId,
                    @Param("modifierId") Long modifierId);

    List<WorkspaceDO> listActive();
}
