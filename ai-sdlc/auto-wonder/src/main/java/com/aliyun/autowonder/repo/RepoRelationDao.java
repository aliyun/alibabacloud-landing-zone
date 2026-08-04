package com.aliyun.autowonder.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface RepoRelationDao {
    void insert(RepoRelationDO relation);
    RepoRelationDO findById(@Param("id") Long id);
    List<RepoRelationDO> listByTenantId(@Param("tenantId") Long tenantId);
    RepoRelationDO findByUk(@Param("tenantId") Long tenantId,
                            @Param("fromRepoId") Long fromRepoId,
                            @Param("toRepoId") Long toRepoId,
                            @Param("relationType") String relationType);
    RepoRelationDO findByUkIncludeDeleted(@Param("tenantId") Long tenantId,
                                          @Param("fromRepoId") Long fromRepoId,
                                          @Param("toRepoId") Long toRepoId,
                                          @Param("relationType") String relationType);
    int undelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                 @Param("description") String description, @Param("aiSessionId") Long aiSessionId,
                 @Param("creatorId") Long creatorId);
    List<RepoRelationDO> listByRepoId(@Param("tenantId") Long tenantId, @Param("repoId") Long repoId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId);
    int deleteByRepoId(@Param("repoId") Long repoId, @Param("tenantId") Long tenantId);
}
