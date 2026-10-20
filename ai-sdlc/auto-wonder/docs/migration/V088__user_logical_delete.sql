-- 平台管理「用户管理」——逻辑删除与恢复：
--   1) user 表新增 4 个可空列：删除时把原用户名/邮箱快照存入 deleted_username/deleted_email，
--      同时把 username 改写为 #deleted-{id}、email 置 NULL 释放唯一键占用（唯一索引允许多个 NULL），
--      用户名/邮箱因此可被他人重新注册；deleted_at/deleted_by 记录删除时间与操作人（本人注销即本人）。
--   2) 存量冷静期用户（已申请未撤销、status = 0）一次性迁移为逻辑删除，与运行时删除同一字段矩阵；
--      已匿名化用户（status = 1、password_hash = 'DEACTIVATED'）保持原样：只读展示、不可恢复、不迁移。
--   3) 运行时删除会同步软删 org_member / user_im_identity（避免已删除用户继续被算作空间成员/管理员），
--      迁移脚本对存量冷静期用户做同样清理。
-- 幂等性：ALTER 为一次性 DDL；三条 UPDATE 可重复执行——①的条件只看冷静期字段与 is_deleted，
--   重跑时对应行已 is_deleted = 1 不再命中；②③ 以 is_deleted = 1 / m.is_deleted = 0 为幂等守卫。
-- 执行顺序：ALTER → ① → ② → ③。

ALTER TABLE `user`
  ADD COLUMN `deleted_username` VARCHAR(64)     DEFAULT NULL COMMENT '逻辑删除时保留的原用户名（展示/恢复还原；用户名唯一键占用已释放）' AFTER `is_deleted`,
  ADD COLUMN `deleted_email`    VARCHAR(128)    DEFAULT NULL COMMENT '逻辑删除时保留的原邮箱（恢复还原）' AFTER `deleted_username`,
  ADD COLUMN `deleted_at`       DATETIME(3)     DEFAULT NULL COMMENT '逻辑删除时间（仅详情展示）' AFTER `deleted_email`,
  ADD COLUMN `deleted_by`       BIGINT UNSIGNED DEFAULT NULL COMMENT '逻辑删除操作人 user_id（管理员删除即该管理员，本人注销即本人）' AFTER `deleted_at`;

-- ① 冷静期用户转逻辑删除（操作人 = 本人；与运行时删除同一矩阵）
UPDATE `user`
SET deleted_username = username,
    username = CONCAT('#deleted-', id),
    deleted_email = email,
    email = NULL,
    deleted_at = NOW(3),
    deleted_by = id,
    deactivated_at = NULL,
    cooling_off_expires_at = NULL,
    deactivation_revoked_at = NULL,
    is_deleted = 1,
    gmt_modified = NOW(3)
WHERE is_deleted = 0
  AND status = 0
  AND deactivated_at IS NOT NULL
  AND cooling_off_expires_at IS NOT NULL
  AND deactivation_revoked_at IS NULL;

-- ② 清理其空间成员关系（与运行时删除一致，避免幽灵成员/管理员）
UPDATE org_member m
JOIN `user` u ON u.id = m.user_id
SET m.is_deleted = 1,
    m.modifier_id = m.user_id,
    m.gmt_modified = NOW(3)
WHERE u.is_deleted = 1
  AND m.is_deleted = 0;

-- ③ 清理其 IM 工号
UPDATE user_im_identity i
JOIN `user` u ON u.id = i.user_id
SET i.is_deleted = 1,
    i.modifier_id = i.user_id,
    i.version = i.version + 1
WHERE u.is_deleted = 1
  AND i.is_deleted = 0;
