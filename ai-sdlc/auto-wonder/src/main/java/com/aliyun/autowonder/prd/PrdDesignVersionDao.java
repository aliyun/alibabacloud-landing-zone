package com.aliyun.autowonder.prd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Design version persistence. Versions are append-only: a confirmed version is never updated in
 * place, and editing one creates a new draft. Every statement carries {@code workspace_id}.
 */
@Mapper
public interface PrdDesignVersionDao {

    void insert(PrdDesignVersionDO version);

    PrdDesignVersionDO findById(@Param("id") Long id, @Param("workspaceId") Long workspaceId);

    PrdDesignVersionDO findByVersionNo(@Param("workspaceId") Long workspaceId,
                                       @Param("designId") Long designId,
                                       @Param("versionNo") Integer versionNo);

    /** All versions of a design, newest first, excluding soft-deleted rows. */
    List<PrdDesignVersionDO> listByDesign(@Param("workspaceId") Long workspaceId,
                                          @Param("designId") Long designId);

    PrdDesignVersionDO findLatestByDesign(@Param("workspaceId") Long workspaceId,
                                          @Param("designId") Long designId);

    /** Find a version by the generation task that produced it. */
    PrdDesignVersionDO findByAiSession(@Param("workspaceId") Long workspaceId,
                                       @Param("aiSessionId") Long aiSessionId);

    /** Rewrite a draft's payload in place. Guarded so a confirmed version can never be touched. */
    int updateDraft(@Param("v") PrdDesignVersionDO version,
                    @Param("expectedVersion") Integer expectedVersion,
                    @Param("modifierId") Long modifierId);

    /** Attach the rendered HTML after the row exists, so the object key can include the version id. */
    int attachHtml(@Param("id") Long id,
                   @Param("workspaceId") Long workspaceId,
                   @Param("htmlOssRef") String htmlOssRef,
                   @Param("htmlSha256") String htmlSha256,
                   @Param("htmlSize") Long htmlSize);

    /**
     * Move DRAFT -> PENDING_REVIEW. Only a draft can be submitted; the status guard plus the
     * optimistic version means a concurrent submit or edit loses the race instead of double-writing.
     */
    int submitForReview(@Param("id") Long id,
                        @Param("workspaceId") Long workspaceId,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("modifierId") Long modifierId);

    /**
     * Bind confirmation to a specific version and its content digest. Refuses to confirm a version
     * that is already confirmed (immutable) unless the digest matches, and requires the caller to
     * pass the exact digest it validated.
     */
    int confirm(@Param("id") Long id,
                @Param("workspaceId") Long workspaceId,
                @Param("confirmedSha256") String confirmedSha256,
                @Param("confirmedBy") Long confirmedBy,
                @Param("expectedVersion") Integer expectedVersion);

    int softDelete(@Param("id") Long id,
                   @Param("workspaceId") Long workspaceId,
                   @Param("expectedVersion") Integer expectedVersion,
                   @Param("modifierId") Long modifierId);
}
