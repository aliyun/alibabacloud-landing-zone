package com.aliyun.autowonder.feature;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlatformFeatureSwitchDao {
    PlatformFeatureSwitchDO findByKey(@Param("switchKey") String switchKey);

    /** 带版本的条件更新；版本不一致或行不存在时返回 0。 */
    int updateEnabled(@Param("switchKey") String switchKey, @Param("enabled") int enabled,
            @Param("version") int version, @Param("modifierId") Long modifierId);

    int insert(PlatformFeatureSwitchDO row);
}
