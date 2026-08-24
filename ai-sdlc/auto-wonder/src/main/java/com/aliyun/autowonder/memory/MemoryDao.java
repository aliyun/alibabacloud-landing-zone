package com.aliyun.autowonder.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MemoryDao {
    void insert(MemoryDO memory);
    MemoryDO findById(@Param("id") Long id);
    MemoryDO findBySourceDedupeKey(@Param("tenantId") Long tenantId, @Param("source") String source,
                                   @Param("sourceDedupeKey") String sourceDedupeKey);
    List<MemoryDO> list(@Param("tenantId") Long tenantId, @Param("scope") String scope,
                        @Param("ownerRef") Long ownerRef,
                        @Param("type") String type, @Param("status") String status,
                        @Param("keyword") String keyword,
                        @Param("visibleAgentRef") Long visibleAgentRef,
                        @Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("title") String title, @Param("contentMd") String contentMd,
               @Param("type") String type,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("status") String status, @Param("contentMd") String contentMd,
                     @Param("scope") String scope, @Param("ownerRef") Long ownerRef,
                     @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int countPendingByTenant(@Param("tenantId") Long tenantId);
    List<MemoryGroupSummaryDO> listGroupSummaries(@Param("tenantId") Long tenantId,
                                                  @Param("scope") String scope,
                                                  @Param("ownerRef") Long ownerRef,
                                                  @Param("type") String type,
                                                  @Param("status") String status,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);
    List<MemoryDO> listByGroups(@Param("tenantId") Long tenantId,
                                @Param("groups") List<MemoryGroupSummaryDO> groups,
                                @Param("type") String type,
                                @Param("status") String status,
                                @Param("limit") int limit);
    List<MemoryDO> listApplicableToAgent(@Param("tenantId") Long tenantId,
                                         @Param("agentId") Long agentId);
}
