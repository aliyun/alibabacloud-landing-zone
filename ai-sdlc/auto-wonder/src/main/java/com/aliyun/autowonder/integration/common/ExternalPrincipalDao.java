package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ExternalPrincipalDao {
    int upsert(ExternalPrincipalDO principal);

    ExternalPrincipalDO findBySource(@Param("provider") String provider,
                                     @Param("subjectId") String subjectId);

    ExternalPrincipalDO findById(@Param("id") Long id);

    List<ExternalPrincipalDO> listByIds(@Param("ids") Collection<Long> ids);
}
