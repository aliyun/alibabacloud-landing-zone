package com.aliyun.autowonder.aiusage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiUsageDao {
    void upsert(@Param("tenantId") Long tenantId,
                @Param("period") String period, @Param("scene") String scene,
                @Param("callCount") long callCount,
                @Param("inputTokens") long inputTokens,
                @Param("outputTokens") long outputTokens);

    List<AiUsageDO> listByTenant(@Param("tenantId") Long tenantId,
                                  @Param("period") String period);

    AiUsageDO findByUk(@Param("tenantId") Long tenantId,
                        @Param("period") String period,
                        @Param("scene") String scene);
}
