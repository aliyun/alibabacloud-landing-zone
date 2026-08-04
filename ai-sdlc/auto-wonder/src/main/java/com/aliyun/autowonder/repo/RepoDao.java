package com.aliyun.autowonder.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface RepoDao {
    void insert(RepoDO repo);
    RepoDO findById(@Param("id") Long id);
    List<RepoDO> list(@Param("tenantId") Long tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("name") String name, @Param("url") String url,
               @Param("defaultBranch") String defaultBranch,
               @Param("description") String description,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateScanStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                         @Param("scanStatus") String scanStatus,
                         @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("version") Integer version, @Param("modifierId") Long modifierId);
}
