package com.aliyun.autowonder.category;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SkillCategoryRefDao {
    SkillCategoryRefDO findByAsset(@Param("tenantId") Long tenantId, @Param("assetType") String assetType,
                                   @Param("assetId") Long assetId);
    List<SkillCategoryRefDO> listByAssets(@Param("tenantId") Long tenantId, @Param("assetType") String assetType,
                                          @Param("assetIds") List<Long> assetIds);
    List<Long> listIdsByCategoryForUpdate(@Param("tenantId") Long tenantId, @Param("categoryId") Long categoryId);
    int upsert(@Param("tenantId") Long tenantId, @Param("assetType") String assetType,
               @Param("assetId") Long assetId, @Param("categoryId") Long categoryId,
               @Param("modifierId") Long modifierId);
    int deleteByAsset(@Param("tenantId") Long tenantId, @Param("assetType") String assetType,
                      @Param("assetId") Long assetId);
}
