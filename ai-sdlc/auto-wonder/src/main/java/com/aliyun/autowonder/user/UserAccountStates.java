package com.aliyun.autowonder.user;

/**
 * 账号状态判定与展示名解析的唯一实现。
 *
 * <p>三种状态口径（与平台用户管理列表、详情一致）：
 * <ul>
 *   <li>{@link #NORMAL} 正常用户（未逻辑删除、非存量匿名化）</li>
 *   <li>{@link #DELETED} 已逻辑删除（数据保留、只读、可恢复），用户名释放为 {@code #deleted-{id}}，
 *       原名保存在 {@code deleted_username}</li>
 *   <li>{@link #DEACTIVATED} 存量已注销（历史匿名化：{@code status = 1} 且密码为 {@code DEACTIVATED} 哨兵），
 *       永不可恢复、只读</li>
 * </ul>
 *
 * <p>展示名规则（需求 §5，全平台唯一实现）：已删除用户直接展示 {@code deleted_username} 快照，
 * 不加任何标签；其余用户昵称优先、为空回退用户名。
 */
public final class UserAccountStates {

    public static final String NORMAL = "NORMAL";
    public static final String DEACTIVATED = "DEACTIVATED";
    public static final String DELETED = "DELETED";

    /** 历史匿名化用户的密码哨兵值，用于识别存量"已注销"账号。 */
    public static final String LEGACY_DEACTIVATED_HASH = "DEACTIVATED";

    private UserAccountStates() {
    }

    public static boolean isDeleted(UserDO user) {
        return user != null && Integer.valueOf(1).equals(user.getIsDeleted());
    }

    public static boolean isLegacyDeactivated(UserDO user) {
        return user != null
                && Integer.valueOf(1).equals(user.getStatus())
                && LEGACY_DEACTIVATED_HASH.equals(user.getPasswordHash());
    }

    /** 只用 {@link UserDO} 本地字段即可判定，无需额外查询。 */
    public static String statusOf(UserDO user) {
        if (isDeleted(user)) {
            return DELETED;
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
