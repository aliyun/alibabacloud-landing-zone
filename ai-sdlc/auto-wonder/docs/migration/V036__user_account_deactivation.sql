-- V036: Account deactivation fields on user table
ALTER TABLE `user`
  ADD COLUMN `deactivated_at` DATETIME(3) DEFAULT NULL COMMENT '注销申请时间' AFTER `status`,
  ADD COLUMN `cooling_off_expires_at` DATETIME(3) DEFAULT NULL COMMENT '冷静期截止时间（7天后）' AFTER `deactivated_at`,
  ADD COLUMN `deactivation_revoked_at` DATETIME(3) DEFAULT NULL COMMENT '撤销注销时间' AFTER `cooling_off_expires_at`;
