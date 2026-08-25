package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ExternalWorkitemLinkDao {
    void insert(ExternalWorkitemLinkDO link);
    ExternalWorkitemLinkDO findByExternalScope(@Param("tenantId") Long tenantId,
                                               @Param("bindingId") Long bindingId,
                                               @Param("externalWorkitemId") String externalWorkitemId);
    ExternalWorkitemLinkDO findByWorkitem(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                          @Param("workitemId") Long workitemId);
    List<ExternalWorkitemLinkDO> listByWorkitem(@Param("tenantId") Long tenantId, @Param("workitemId") Long workitemId);
    List<ExternalWorkitemLinkDO> listByWorkitemIds(@Param("tenantId") Long tenantId, @Param("workitemIds") Collection<Long> workitemIds);
    List<ExternalWorkitemLinkDO> listByExternal(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                                @Param("externalWorkitemId") String externalWorkitemId,
                                                @Param("offset") int offset, @Param("limit") int limit);
    List<ExternalWorkitemLinkDO> listByBindingAfterId(@Param("bindingId") Long bindingId,
                                                      @Param("afterId") Long afterId,
                                                      @Param("limit") int limit);
    int updateRemoteState(@Param("id") Long id, @Param("remoteVersionHash") String remoteVersionHash,
                          @Param("lastSyncDirection") String lastSyncDirection);
    int updateSnapshot(ExternalWorkitemLinkDO link);
    int updateSyncError(@Param("id") Long id,
                        @Param("syncStatus") String syncStatus,
                        @Param("lastErrorCode") String lastErrorCode,
                        @Param("lastError") String lastError);
}
