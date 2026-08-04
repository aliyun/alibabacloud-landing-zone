package com.aliyun.autowonder.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepoConclusionDao {
    void insert(RepoConclusionDO conclusion);
    RepoConclusionDO findByRepoId(@Param("repoId") Long repoId);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("purpose") String purpose,
               @Param("keyBusiness") String keyBusiness,
               @Param("upstreams") String upstreams,
               @Param("downstreams") String downstreams,
               @Param("summaryMd") String summaryMd,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int deleteByRepoId(@Param("repoId") Long repoId, @Param("tenantId") Long tenantId);
}
