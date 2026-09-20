package com.aliyun.autowonder.category;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CategoryDao {
    void insert(CategoryDO category);
    CategoryDO findByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
    CategoryDO findByIdAndTenantForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);
    List<CategoryDO> listByTenant(@Param("tenantId") Long tenantId);
    CategoryDO findSiblingByNameForUpdate(@Param("tenantId") Long tenantId, @Param("parentId") Long parentId,
                                          @Param("name") String name);
    List<Long> listChildIdsForUpdate(@Param("tenantId") Long tenantId, @Param("parentId") Long parentId);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("parentId") Long parentId, @Param("name") String name,
               @Param("description") String description,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int delete(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
