package com.aliyun.autowonder.aiusage;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiQuotaDao {
    void insert(AiQuotaDO quota);

    AiQuotaDO findByTenant(@Param("tenantId") Long tenantId);

    int update(@Param("tenantId") Long tenantId,
               @Param("maxCalls") Long maxCalls,
               @Param("maxTokens") Long maxTokens,
               @Param("concurrencyLimit") Integer concurrencyLimit);
}
