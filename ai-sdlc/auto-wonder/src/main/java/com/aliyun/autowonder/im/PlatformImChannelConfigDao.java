package com.aliyun.autowonder.im;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlatformImChannelConfigDao {
    List<PlatformImChannelConfigDO> listActive();

    PlatformImChannelConfigDO findByProvider(@Param("provider") String provider);

    String selectedProvider();

    String lockSelection();

    int selectProvider(@Param("provider") String provider);

    int disableOthers(@Param("provider") String provider, @Param("userId") long userId);

    int upsert(PlatformImChannelConfigDO config);
}
