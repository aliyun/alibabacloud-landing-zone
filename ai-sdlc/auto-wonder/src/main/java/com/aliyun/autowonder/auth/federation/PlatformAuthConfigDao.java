package com.aliyun.autowonder.auth.federation;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformAuthConfigDao {
    PlatformAuthConfigDO find();

    /** 单行配置更新锁：并发设置默认入口时保证最终只有一个默认连接。 */
    PlatformAuthConfigDO lock();

    int insert(PlatformAuthConfigDO config);

    int update(PlatformAuthConfigDO config);
}
