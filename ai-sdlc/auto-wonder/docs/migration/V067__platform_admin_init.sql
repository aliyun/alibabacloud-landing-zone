-- 平台管理员一次性初始化迁移（配套工单：统一平台管理员权限）。
--
-- 背景：旧实现存在两处按注册顺序授权的运行时逻辑——
--   1) SystemAdminService.isSystemAdmin 的「首个有效用户」兜底；
--   2) SystemAdminBootstrap 每次启动「无管理员即提升首个有效用户」的自愈。
-- 它们使首个有效用户即使被撤销 user.is_admin 也会在重启/再次升级后恢复平台特权。
--
-- 本迁移建立 platform_admin_init 完成标记表。应用启动时的迁移逻辑（见
-- SystemAdminService.ensurePlatformAdminInitialized）语义如下，本文件供运维核对与
-- 手工执行参考：
--   * 标记行已存在（initialized=1）→ 不做任何授权（撤权不可恢复）；
--   * 标记不存在且已有 is_admin=1 用户 → 保留现有名单，仅写入标记；
--   * 标记不存在且无任何 is_admin=1 用户 → 将首个有效用户置为平台管理员并写入标记。
-- 迁移异常会以 ERROR 日志明确报告，不会被运行时自愈掩盖。
--
-- 新安装无需本迁移：首个注册用户经 UserService.register 初始化为平台管理员。
CREATE TABLE IF NOT EXISTS `platform_admin_init` (
  `id`          TINYINT NOT NULL PRIMARY KEY,
  `initialized` TINYINT NOT NULL DEFAULT 0 COMMENT '0 未完成 / 1 初始化迁移已完成'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理员一次性初始化迁移完成标记';

-- 运维手工执行时：先确认平台管理员名单，再写入完成标记。
-- UPDATE `user` SET `is_admin` = 1 WHERE `id` = <平台管理员用户ID>;
INSERT INTO `platform_admin_init` (`id`, `initialized`)
VALUES (1, 1)
ON DUPLICATE KEY UPDATE `initialized` = 1;
