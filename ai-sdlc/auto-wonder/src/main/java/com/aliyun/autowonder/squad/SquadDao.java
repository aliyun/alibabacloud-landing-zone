package com.aliyun.autowonder.squad;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SquadDao {
    void insert(SquadDO squad);
    SquadDO findById(@Param("id") Long id);
    List<SquadDO> list(@Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("name") String name, @Param("description") String description,
            @Param("ownerId") Long ownerId, @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
}
