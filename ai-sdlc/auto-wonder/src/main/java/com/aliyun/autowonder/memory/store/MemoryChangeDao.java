package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryChangeDao {
    void insert(MemoryChangeDO change);
    MemoryChangeDO findLegacyMigration(@Param("tenantId") long tenantId, @Param("memoryId") long memoryId);
    MemoryChangeDO findByIdempotencyKey(@Param("tenantId") Long tenantId,
                                        @Param("storeId") Long storeId,
                                        @Param("idempotencyKey") String idempotencyKey);
    List<MemoryChangeDO> listAfterRevision(@Param("tenantId") Long tenantId,
                                           @Param("storeId") Long storeId,
                                           @Param("afterRevision") Long afterRevision,
                                           @Param("limit") int limit);
}
