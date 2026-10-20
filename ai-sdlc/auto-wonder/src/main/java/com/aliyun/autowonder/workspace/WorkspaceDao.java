package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface WorkspaceDao {
    void insert(WorkspaceDO workspace);
    WorkspaceDO findById(@Param("id") Long id);
    WorkspaceDO findByIdForUpdate(@Param("id") Long id);

    /** In-use name lookup; backed by {@code uk_active_name}, so a deleted row never matches. */
    WorkspaceDO findByName(@Param("name") String name);

    /**
     * Reads the row whatever its lifecycle state. The recycle bin and restore must see
     * logically deleted rows, which every other finder deliberately filters out.
     */
    WorkspaceDO findByIdAnyState(@Param("id") Long id);

    /**
     * Real-time usability check for AuthFilter: the token's workspace must still be
     * {@code is_deleted = 0 AND status = 0}. Read straight from the database rather than
     * from the jti blacklist, so a delete takes effect on already-issued tokens at once.
     */
    long countUsable(@Param("id") Long id);

    List<WorkspaceDO> listByUser(@Param("userId") Long userId);
    List<WorkspaceMembershipDO> listMembershipsByUser(@Param("userId") Long userId);
    int updateOwner(@Param("id") Long id,
                    @Param("oldOwnerId") Long oldOwnerId,
                    @Param("newOwnerId") Long newOwnerId,
                    @Param("modifierId") Long modifierId);

    /**
     * Optimistic-lock detail update. Returns 0 when the row is gone, deleted, or its
     * version moved on — the caller distinguishes those cases by re-reading.
     */
    int updateDetail(@Param("id") Long id,
                     @Param("name") String name,
                     @Param("activeNameKey") String activeNameKey,
                     @Param("description") String description,
                     @Param("background") String background,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("modifierId") Long modifierId);

    /**
     * Logically deletes and releases the name in one statement. The {@code is_deleted = 0} guard is
     * what makes a duplicate delete return 0 instead of re-stamping deleted_at/deleted_by.
     */
    int softDelete(@Param("id") Long id, @Param("deletedBy") Long deletedBy);

    /** Restore is conditional on {@code is_deleted = 1}, so a duplicate restore is a no-op. */
    int restore(@Param("id") Long id,
                @Param("name") String name,
                @Param("activeNameKey") String activeNameKey,
                @Param("modifierId") Long modifierId);

    long countActiveByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /**
     * 该用户仍是所有者的活跃空间数。owner 转让仅限本人发起，删除 owner 会把空间所有权永久锁死，
     * 用户删除校验据此阻断。
     */
    long countOwnedByUser(@Param("userId") Long userId);

    /** Bulk form of {@link #countActiveByName}, for the recycle bin's restorable flag. */
    List<String> listActiveNames(@Param("names") Collection<String> names);

    List<WorkspaceDO> listActive();

    List<WorkspaceDO> listAllPaged(@Param("keyword") String keyword,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    long countAll(@Param("keyword") String keyword);

    /**
     * 跨空间复制小队的目标查询：按名称过滤、按复制权限收敛后分页。
     * 普通用户需要目标角色的 access_level 为 READ_WRITE/ADMIN；平台管理员不做成员过滤。
     */
    List<WorkspaceDO> listCopyTargets(@Param("sourceWorkspaceId") Long sourceWorkspaceId,
                                      @Param("userId") Long userId,
                                      @Param("systemAdmin") boolean systemAdmin,
                                      @Param("keyword") String keyword,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    long countCopyTargets(@Param("sourceWorkspaceId") Long sourceWorkspaceId,
                          @Param("userId") Long userId,
                          @Param("systemAdmin") boolean systemAdmin,
                          @Param("keyword") String keyword);

    /**
     * Recycle bin page. Visibility is decided in SQL (F4): filtering in memory after a
     * {@code SELECT} would both leak ids by timing and break pagination totals.
     */
    List<WorkspaceDO> pageRecycleBin(@Param("userId") Long userId,
                                     @Param("systemAdmin") boolean systemAdmin,
                                     @Param("keyword") String keyword,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    long countRecycleBin(@Param("userId") Long userId,
                         @Param("systemAdmin") boolean systemAdmin,
                         @Param("keyword") String keyword);
}
