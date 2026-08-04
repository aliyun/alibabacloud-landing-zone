package com.aliyun.autowonder.sdlc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface SdlcDao {
    void insert(SdlcDO sdlc);
    SdlcDO findById(@Param("id") Long id);
    List<SdlcDO> listByIds(@Param("ids") Collection<Long> ids);
    List<SdlcDO> list(@Param("workType") String workType,
                      @Param("status") String status,
                      @Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("name") String name, @Param("description") String description,
               @Param("workType") String workType, @Param("version") Integer version,
               @Param("modifierId") Long modifierId);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("status") String status, @Param("entryStepId") Long entryStepId,
                     @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("version") Integer version, @Param("modifierId") Long modifierId);
    SdlcDO findDefault(@Param("workType") String workType);
}
