package com.aliyun.autowonder.im;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformImChannelConfigDao {
    List<PlatformImChannelConfigDO> listActive();

    PlatformImChannelConfigDO findByProvider(@Param("provider") String provider);

    int upsert(PlatformImChannelConfigDO config);
}
