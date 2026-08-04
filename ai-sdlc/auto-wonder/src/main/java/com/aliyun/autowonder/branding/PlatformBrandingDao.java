package com.aliyun.autowonder.branding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformBrandingDao {
    PlatformBrandingDO findActive();

    int update(PlatformBrandingDO config);

    int updateLogo(@Param("logoOssRef") String logoOssRef,
                   @Param("logoContentType") String logoContentType,
                   @Param("modifierId") Long modifierId);
}
