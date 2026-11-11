package com.aliyun.autowonder.prd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Design aggregate persistence. Every statement carries an explicit {@code workspace_id} predicate:
 * this domain uses workspace naming and is deliberately absent from {@code TenantTables}, whose
 * rewriter appends {@code tenant_id = ?} and would fail on this schema.
 */
@Mapper
public interface PrdDesignDao {

    void insert(PrdDesignDO design);

    PrdDesignDO findById(@Param("id") Long id, @Param("workspaceId") Long workspaceId);

    PrdDesignDO findByWorkitem(@Param("workspaceId") Long workspaceId,
                               @Param("workitemId") Long workitemId);

    /**
     * Bind the target frontend project. Guarded by the optimistic version so two concurrent edits
     * cannot silently clobber each other's target selection.
     */
    int updateTarget(@Param("id") Long id,
                     @Param("workspaceId") Long workspaceId,
                     @Param("targetRepoId") Long targetRepoId,
                     @Param("targetProfileId") Long targetProfileId,
                     @Param("targetAppRoot") String targetAppRoot,
                     @Param("targetCommitSha") String targetCommitSha,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("modifierId") Long modifierId);

    int updateDeliveryMode(@Param("id") Long id,
                           @Param("workspaceId") Long workspaceId,
                           @Param("deliveryMode") String deliveryMode,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("modifierId") Long modifierId);

    int updateManualTarget(@Param("id") Long id,
                           @Param("workspaceId") Long workspaceId,
                           @Param("targetRepoId") Long targetRepoId,
                           @Param("targetAppRoot") String targetAppRoot,
                           @Param("targetConfigJson") String targetConfigJson,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("modifierId") Long modifierId);

    int updateContentLanguage(@Param("id") Long id,
                              @Param("workspaceId") Long workspaceId,
                              @Param("contentLanguage") String contentLanguage,
                              @Param("expectedVersion") Integer expectedVersion,
                              @Param("modifierId") Long modifierId);

    /**
     * Claim the next version number atomically. Returns the updated row count; the caller re-reads
     * {@code latest_version_no} only after a successful CAS, so two generators cannot mint the same
     * version number.
     */
    int incrementLatestVersionNo(@Param("id") Long id,
                                 @Param("workspaceId") Long workspaceId,
                                 @Param("expectedVersion") Integer expectedVersion);

    /** Bind a confirmed version; the digest is stored so delivery can re-verify it later. */
    int confirmVersion(@Param("id") Long id,
                       @Param("workspaceId") Long workspaceId,
                       @Param("confirmedVersionId") Long confirmedVersionId,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("modifierId") Long modifierId);
}
