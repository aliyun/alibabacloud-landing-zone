package com.aliyun.autowonder.skill;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SkillDao {
    void insert(SkillDO skill);
    SkillDO findById(@Param("id") Long id);
    List<SkillDO> list(@Param("type") String type,
                       @Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("name") String name, @Param("type") String type,
               @Param("installSpec") String installSpec,
               @Param("description") String description,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updatePackage(@Param("id") Long id, @Param("tenantId") Long tenantId,
                      @Param("type") String type, @Param("installSpec") String installSpec,
                      @Param("name") String name, @Param("description") String description,
                      @Param("sourceType") String sourceType, @Param("packageOssRef") String packageOssRef,
                      @Param("packageFileName") String packageFileName, @Param("packageSize") Long packageSize,
                      @Param("packageMd5") String packageMd5,
                      @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("version") Integer version, @Param("modifierId") Long modifierId);
    SkillDO findByTypeAndName(@Param("tenantId") Long tenantId,
                              @Param("type") String type, @Param("name") String name);
}
