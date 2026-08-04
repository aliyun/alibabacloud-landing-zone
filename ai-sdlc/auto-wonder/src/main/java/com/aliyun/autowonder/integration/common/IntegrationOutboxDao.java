package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IntegrationOutboxDao {
    void insert(IntegrationOutboxDO outbox);
    IntegrationOutboxDO findById(@Param("id") Long id);
    List<IntegrationOutboxDO> listPending(@Param("provider") String provider, @Param("limit") int limit);
    List<IntegrationOutboxDO> listPendingAny(@Param("limit") int limit);
    List<IntegrationOutboxDO> listPendingExcludingProvider(@Param("provider") String provider,
                                                           @Param("limit") int limit);
    int markSucceeded(@Param("id") Long id);
    int markFailed(@Param("id") Long id, @Param("status") String status, @Param("lastError") String lastError);
}
