package com.aliyun.autowonder.org;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrgDao {
    void insert(OrgDO org);
    OrgDO findById(@Param("id") Long id);
    OrgDO findByIdForUpdate(@Param("id") Long id);
    OrgDO findByName(@Param("name") String name);
    List<OrgDO> listByUser(@Param("userId") Long userId);
    List<OrgMembershipDO> listMembershipsByUser(@Param("userId") Long userId);
    int updateOwner(@Param("id") Long id,
                    @Param("oldOwnerId") Long oldOwnerId,
                    @Param("newOwnerId") Long newOwnerId,
                    @Param("modifierId") Long modifierId);
}
