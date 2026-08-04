package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface ExternalProjectBindingDao {
    void insert(ExternalProjectBindingDO binding);
    ExternalProjectBindingDO findById(@Param("id") Long id);
    ExternalProjectBindingDO findByProject(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                           @Param("externalProjectId") String externalProjectId);
    List<ExternalProjectBindingDO> list(@Param("tenantId") Long tenantId,
                                        @Param("provider") String provider,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);
    List<ExternalProjectBindingDO> listEnabled(@Param("provider") String provider);
    int updateHealth(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("lastSuccessAt") Date lastSuccessAt, @Param("lastError") String lastError);
}
