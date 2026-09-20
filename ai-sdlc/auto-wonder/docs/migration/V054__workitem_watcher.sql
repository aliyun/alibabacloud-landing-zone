-- V054__workitem_watcher.sql
-- 工单真人关注关系：非负责人可订阅工单进展。独立于负责人/创建人/@/数字员工指派。
CREATE TABLE IF NOT EXISTS `workitem_watcher` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL COMMENT '工作空间 ID',
  `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '工单 ID',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '关注人 user ID',
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workitem_watcher` (`tenant_id`, `workitem_id`, `user_id`),
  KEY `idx_workitem_watcher_user` (`tenant_id`, `user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单真人关注关系';
