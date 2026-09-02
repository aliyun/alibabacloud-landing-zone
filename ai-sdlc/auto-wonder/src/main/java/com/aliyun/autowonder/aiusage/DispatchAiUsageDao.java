package com.aliyun.autowonder.aiusage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchAiUsageDao {
    void upsert(DispatchAiUsageDO usage);

    int attachArtifactIfAbsent(@Param("tenantId") long tenantId,
                               @Param("dispatchId") long dispatchId,
                               @Param("provider") String provider,
                               @Param("model") String model,
                               @Param("artifactId") Long artifactId);

    List<DispatchAiUsageDO> listByDispatchIds(@Param("tenantId") long tenantId,
                                              @Param("dispatchIds") List<Long> dispatchIds);
}
