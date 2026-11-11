package com.aliyun.autowonder.frontendproject;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Profile snapshot persistence. Snapshots are immutable once written: a changed project adds a
 * new snapshot rather than editing one that a confirmed design may already reference.
 * Every statement carries an explicit {@code workspace_id} predicate.
 */
@Mapper
public interface FrontendProjectProfileDao {

    /** Insert, ignoring a row that already exists for the same cache key (idempotent re-probe). */
    int insertIgnore(FrontendProjectProfileDO profile);

    FrontendProjectProfileDO findById(@Param("id") Long id,
                                      @Param("workspaceId") Long workspaceId);

    FrontendProjectProfileDO findByCacheKey(@Param("workspaceId") Long workspaceId,
                                            @Param("repoId") Long repoId,
                                            @Param("commitSha") String commitSha,
                                            @Param("appRootHash") String appRootHash,
                                            @Param("detectorVersion") String detectorVersion);

    /** Latest snapshot per app root for a repo, newest first. */
    List<FrontendProjectProfileDO> listByRepo(@Param("workspaceId") Long workspaceId,
                                              @Param("repoId") Long repoId,
                                              @Param("limit") int limit);

    List<FrontendProjectProfileDO> listByProbe(@Param("workspaceId") Long workspaceId,
                                               @Param("probeId") Long probeId);

    /** Snapshots at an exact commit, used to detect staleness before generation/delivery. */
    List<FrontendProjectProfileDO> listByCommit(@Param("workspaceId") Long workspaceId,
                                                @Param("repoId") Long repoId,
                                                @Param("commitSha") String commitSha,
                                                @Param("detectorVersion") String detectorVersion);

    /** Lifecycle cleanup: drop snapshots when a repo is deleted from the workspace. */
    int deleteByRepo(@Param("workspaceId") Long workspaceId,
                     @Param("repoId") Long repoId);
}
