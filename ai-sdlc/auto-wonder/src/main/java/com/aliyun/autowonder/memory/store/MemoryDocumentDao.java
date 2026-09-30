package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryDocumentDao {
    void insert(MemoryDocumentDO document);
    int insertEmptyIndexIfAbsent(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                 @Param("creatorId") Long creatorId);
    MemoryDocumentDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
    MemoryDocumentDO findByPath(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
                                @Param("path") String path, @Param("includeDeleted") boolean includeDeleted);
    List<MemoryDocumentDO> listLive(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
               @Param("path") String path, @Param("contentMd") String contentMd,
               @Param("contentSha256") String contentSha256, @Param("byteSize") Long byteSize,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int eraseAndDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                       @Param("storeId") Long storeId, @Param("version") Integer version,
                       @Param("modifierId") Long modifierId);
}
