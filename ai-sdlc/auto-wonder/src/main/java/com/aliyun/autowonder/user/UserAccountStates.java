package com.aliyun.autowonder.user;

/**
 * 账号状态判定与展示名解析的唯一实现。
 *
 * <p>状态口径（与平台用户管理列表、详情一致）：
 * <ul>
 *   <li>{@link #NORMAL} 正常用户（未逻辑删除、非存量匿名化）</li>
 *   <li>{@link #PENDING_REVIEW} 自助注册待审核（{@code status = 2}，未逻辑删除）</li>
 *   <li>{@link #DELETED} 已逻辑删除（数据保留、只读、可恢复），用户名释放为 {@code #deleted-{id}}，
 *       原名保存在 {@code deleted_username}</li>
 *   <li>{@link #DEACTIVATED} 存量已注销/停用（{@code status = 1} 且无可用凭据：密码为 {@code DEACTIVATED}
 *       哨兵或为空；纯 SSO 账号没有密码哈希），与 {@code AuthFilter} 的停用口径一致，永不可恢复、只读</li>
 * </ul>
 *
 * <p>数据库 {@code user.status} 取值：0 正常、1 停用/注销、2 待审核。
 *
 * <p>展示名规则（需求 §5，全平台唯一实现）：已删除用户直接展示 {@code deleted_username} 快照，
 * 不加任何标签；其余用户昵称优先、为空回退用户名。
 */
public final class UserAccountStates {

    public static final String NORMAL = "NORMAL";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String DEACTIVATED = "DEACTIVATED";
    public static final String DELETED = "DELETED";

    /** user.status：0 正常。 */
    public static final int STATUS_NORMAL = 0;
    /** user.status：2 自助注册待审核。 */
    public static final int STATUS_PENDING_REVIEW = 2;

    /** 历史匿名化用户的密码哨兵值，用于识别存量"已注销"账号。 */
    public static final String LEGACY_DEACTIVATED_HASH = "DEACTIVATED";

    private UserAccountStates() {
    }

    public static boolean isDeleted(UserDO user) {
        return user != null && Integer.valueOf(1).equals(user.getIsDeleted());
    }

    /**
     * status = 1 且没有可用凭据即为停用：历史匿名化哨兵，或纯 SSO/无密码账号（password_hash 为空）。
     * 与 {@code AuthFilter} 拒绝 status=1 且 hash 为空或哨兵的口径一致，避免同一账号在登录拦截、
     * 行内 canCreateWorkspace 与列表筛选中得出不同结论。
     */
    public static boolean isLegacyDeactivated(UserDO user) {
        return user != null
                && Integer.valueOf(1).equals(user.getStatus())
                && (user.getPasswordHash() == null
                    || LEGACY_DEACTIVATED_HASH.equals(user.getPasswordHash()));
    }

    public static boolean isPendingReview(UserDO user) {
        return user != null
                && !isDeleted(user)
                && Integer.valueOf(STATUS_PENDING_REVIEW).equals(user.getStatus());
    }

    /** 可正常登录/使用平台的有效账号：未删除、非待审、非存量注销。 */
    public static boolean isActive(UserDO user) {
        return NORMAL.equals(statusOf(user));
    }

    /** 只用 {@link UserDO} 本地字段即可判定，无需额外查询。 */
    public static String statusOf(UserDO user) {
        if (isDeleted(user)) {
            return DELETED;
        }
        if (isPendingReview(user)) {
            return PENDING_REVIEW;
        }
        return isLegacyDeactivated(user) ? DEACTIVATED : NORMAL;
    }

    /** 已删除用户展示原名快照；正常/存量已注销用户昵称优先、为空回退用户名。 */
    public static String displayName(UserDO user) {
        if (user == null) {
            return null;
        }
        if (isDeleted(user)) {
            return user.getDeletedUsername();
        }
        String nickname = user.getNickname();
        return nickname == null || nickname.isBlank() ? user.getUsername() : nickname;
    }
}
