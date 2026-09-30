package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Date;

@Mapper
public interface MemoryStoreDao {
    void insert(MemoryStoreDO store);
    int insertIfAbsent(MemoryStoreDO store);
    MemoryStoreDO findActive(@Param("tenantId") Long tenantId, @Param("scope") String scope,
                             @Param("ownerRef") Long ownerRef);
    MemoryStoreDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
    MemoryStoreDO lockById(@Param("tenantId") Long tenantId, @Param("id") Long id);
    List<MemoryStoreDO> listActive(@Param("tenantId") Long tenantId);
    int advanceRevision(@Param("tenantId") Long tenantId, @Param("id") Long id,
                        @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int archive(@Param("tenantId") Long tenantId, @Param("id") Long id,
                @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int claimMaintenance(@Param("tenantId") Long tenantId, @Param("id") Long id,
                         @Param("owner") String owner, @Param("dispatchId") Long dispatchId, @Param("now") Date now,
                         @Param("leaseUntil") Date leaseUntil);
    int reclaimMaintenance(@Param("tenantId") Long tenantId, @Param("id") Long id,
                           @Param("owner") String owner, @Param("dispatchId") Long dispatchId,
                           @Param("now") Date now, @Param("leaseUntil") Date leaseUntil);
    int releaseMaintenance(@Param("tenantId") Long tenantId, @Param("id") Long id,
                           @Param("owner") String owner);
    int renewMaintenance(@Param("tenantId") Long tenantId, @Param("id") Long id,
                         @Param("owner") String owner, @Param("now") Date now,
                         @Param("leaseUntil") Date leaseUntil);
    String lockActiveMaintenanceOwner(@Param("tenantId") Long tenantId, @Param("id") Long id,
                                      @Param("now") Date now);
}
