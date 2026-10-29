CREATE TABLE IF NOT EXISTS `task_data_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `namespace` VARCHAR(128) NOT NULL COMMENT '业务命名空间，由脚本定义',
  `record_key` VARCHAR(256) NOT NULL COMMENT '业务记录键，由脚本定义',
  `owner_workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '写入该记录的工单',
  `value_json` JSON NOT NULL COMMENT '业务数据，结构由脚本维护',
  `version` INT NOT NULL DEFAULT 0,
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_data_record` (`workspace_id`, `namespace`, `record_key`),
  KEY `idx_task_data_list` (`workspace_id`, `namespace`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工作空间通用任务数据记录';
