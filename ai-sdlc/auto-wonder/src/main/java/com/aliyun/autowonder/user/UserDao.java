package com.aliyun.autowonder.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface UserDao {
    int insert(UserDO user);

    UserDO findByUsername(@Param("username") String username);

    List<UserDO> findByUsernameOrNickname(@Param("name") String name);

    UserDO findById(@Param("id") Long id);

    List<UserDO> listByIds(@Param("ids") Collection<Long> ids);

    Long findFirstActiveUserId();

    long countSystemAdmins();

    /** Completion marker for the one-shot platform-admin init migration (platform_admin_init row). */
    boolean isPlatformAdminInitDone();

    /** Idempotent INSERT of the one-shot migration completion marker. */
    int markPlatformAdminInitDone();

    /** Idempotent: the {@code is_admin = 0} guard makes a repeat call a no-op. */
    int markSystemAdmin(@Param("id") Long id);

    /** Idempotent: the {@code is_admin = 1} guard makes a repeat call a no-op. */
    int revokeSystemAdmin(@Param("id") Long id);

    List<UserDO> listSystemAdmins();

    List<UserDO> searchSystemAdminCandidates(@Param("keyword") String keyword,
                                             @Param("limit") int limit);

    List<UserDO> searchWorkspaceCandidates(@Param("tenantId") Long tenantId,
                                      @Param("keyword") String keyword,
                                      @Param("limit") int limit);

    int updatePasswordHash(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /** Updates only the self-service basic-profile columns; email uniqueness stays with uk_email. */
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("email") String email,
                      @Param("phone") String phone);

    UserDO findActiveByEmail(@Param("email") String email);

    // ---- 平台用户管理（平台管理员域；含已删除用户） ----

    /** 全部用户总数（含已删除），列表页固定展示，不随筛选变化。 */
    long countAllUsers();

    /** 管理列表分页查询：全量含已删除；keyword 模糊匹配用户名/昵称/邮箱/手机号及删除前原名快照。 */
    List<UserDO> searchUsersForAdmin(@Param("keyword") String keyword,
                                     @Param("status") String status,
                                     @Param("type") String type,
                                     @Param("offset") int offset,
                                     @Param("limit") int size);

    long countUsersForAdmin(@Param("keyword") String keyword,
                            @Param("status") String status,
                            @Param("type") String type);

    /** 展示专用：可查到已删除用户，供历史数据名称解析使用；鉴权/校验路径继续用 findById。 */
    UserDO findByIdIncludingDeleted(@Param("id") Long id);

    List<UserDO> listByIdsIncludingDeleted(@Param("ids") Collection<Long> ids);

    /**
     * 逻辑删除：原用户名/邮箱存入 deleted_* 快照并释放唯一键占用（username 改写、email 置 NULL），
     * 全部字段在同一条原子 UPDATE 内完成；昵称/头像/手机号/密码/status 保留不动。
     */
    int logicalDeleteUser(@Param("id") Long id,
                          @Param("deletedAt") java.util.Date deletedAt,
                          @Param("deletedBy") Long deletedBy);

    /** 恢复：由快照还原用户名/邮箱并清空删除标记；is_deleted = 1 守卫保证仅已删除用户可被恢复。 */
    int restoreUser(@Param("id") Long id);
}
