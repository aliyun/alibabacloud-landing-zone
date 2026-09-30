package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryStoreAclDao {
    void insert(MemoryStoreAclDO acl);
    List<MemoryStoreAclDO> listByStore(@Param("tenantId") Long tenantId,
                                       @Param("storeId") Long storeId);
    List<MemoryStoreAclDO> listForSubject(@Param("tenantId") Long tenantId,
                                          @Param("subjectType") String subjectType,
                                          @Param("subjectRef") String subjectRef);
    int delete(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
               @Param("id") Long id);
}
