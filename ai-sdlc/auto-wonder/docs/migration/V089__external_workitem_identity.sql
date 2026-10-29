-- 外部工单身份唯一：同一租户、同一来源下，一张外部工单只能映射一个本地工单。
-- Aone 以唯一工单号去重，同一工单可同时属于多个项目；项目绑定不能作为本地去重依据。
--
-- 安全策略：本迁移不删除任何存量映射。
-- 1) 先整表备份；备份表已存在时迁移直接失败，避免重跑沿用旧快照。
-- 2) 生成冲突清单与孤儿清单；只要存在未处理项，迁移在加唯一键前显式中断。
-- 3) 人工按清单处理（见 MR 描述中的独立清理方案）后，移走旧备份表再重跑。
-- 不要在 mysql CLI 使用 --force，否则可能绕过冲突阻断并只留下唯一键错误。

-- 1. 迁移前快照。重跑前必须先把上一轮备份表改名/移走。
CREATE TABLE external_workitem_link_backup_v089 AS
SELECT * FROM external_workitem_link;

-- 2. 冲突清单：每条重复映射一行，附带交付/评论历史，供人工决定保留哪一份。
DROP TABLE IF EXISTS external_workitem_identity_conflicts_v089;
CREATE TABLE external_workitem_identity_conflicts_v089 (
  tenant_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(32) NOT NULL,
  external_workitem_id VARCHAR(64) NOT NULL,
  link_id BIGINT UNSIGNED NOT NULL,
  binding_id BIGINT UNSIGNED NOT NULL,
  workitem_id BIGINT UNSIGNED NOT NULL,
  workitem_status_name VARCHAR(128) DEFAULT NULL,
  workitem_status_category VARCHAR(16) DEFAULT NULL,
  comment_count BIGINT NOT NULL,
  dispatch_count BIGINT NOT NULL,
  active_dispatch_count BIGINT NOT NULL,
  external_comment_link_count BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, provider, external_workitem_id, link_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V089 待人工处理的重复外部工单映射';

INSERT INTO external_workitem_identity_conflicts_v089
(tenant_id, provider, external_workitem_id, link_id, binding_id, workitem_id,
 workitem_status_name, workitem_status_category, comment_count, dispatch_count,
 active_dispatch_count, external_comment_link_count)
SELECT l.tenant_id, l.provider, l.external_workitem_id, l.id, l.binding_id, l.workitem_id,
       sn.name, sn.category,
       (SELECT COUNT(*) FROM workitem_comment c
         WHERE c.tenant_id = w.tenant_id AND c.workitem_id = w.id),
       (SELECT COUNT(*) FROM dispatch d
         WHERE d.tenant_id = w.tenant_id AND d.workitem_id = w.id AND d.is_deleted = 0),
       (SELECT COUNT(*) FROM dispatch d
         WHERE d.tenant_id = w.tenant_id AND d.workitem_id = w.id AND d.is_deleted = 0
           AND d.status IN ('PACKAGING','DISPATCHED','ACKED','RUNNING')),
       (SELECT COUNT(*) FROM external_comment_link ecl
          JOIN workitem_comment c ON c.id = ecl.workitem_comment_id AND c.tenant_id = ecl.tenant_id
         WHERE c.tenant_id = w.tenant_id AND c.workitem_id = w.id)
FROM external_workitem_link l
JOIN workitem w ON w.id = l.workitem_id AND w.tenant_id = l.tenant_id AND w.is_deleted = 0
LEFT JOIN status_node sn ON sn.id = w.status_node_id AND sn.tenant_id = w.tenant_id
WHERE EXISTS (
  SELECT 1 FROM external_workitem_link l2
  JOIN workitem w2 ON w2.id = l2.workitem_id AND w2.tenant_id = l2.tenant_id AND w2.is_deleted = 0
  WHERE l2.tenant_id = l.tenant_id AND l2.provider = l.provider
    AND l2.external_workitem_id = l.external_workitem_id AND l2.id <> l.id
);

-- 3. 孤儿清单：工单已删除/不存在但映射仍在，会占用新的租户级唯一身份。
DROP TABLE IF EXISTS external_workitem_identity_orphans_v089;
CREATE TABLE external_workitem_identity_orphans_v089 (
  tenant_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(32) NOT NULL,
  external_workitem_id VARCHAR(64) NOT NULL,
  link_id BIGINT UNSIGNED NOT NULL,
  binding_id BIGINT UNSIGNED NOT NULL,
  workitem_id BIGINT UNSIGNED NOT NULL,
  workitem_missing TINYINT NOT NULL,
  PRIMARY KEY (tenant_id, provider, external_workitem_id, link_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V089 待人工处理的孤儿外部工单映射';

INSERT INTO external_workitem_identity_orphans_v089
(tenant_id, provider, external_workitem_id, link_id, binding_id, workitem_id, workitem_missing)
SELECT l.tenant_id, l.provider, l.external_workitem_id, l.id, l.binding_id, l.workitem_id,
       CASE WHEN w.id IS NULL THEN 1 ELSE 0 END
FROM external_workitem_link l
LEFT JOIN workitem w ON w.id = l.workitem_id AND w.tenant_id = l.tenant_id
WHERE w.id IS NULL OR w.is_deleted = 1;

-- 4. 未处理项阻断：向单行 guard 表再次插入 1 会触发主键冲突并中断脚本。
--    报错表名 v089_blocked_* 即原因；清单表保留供人工核查。
DROP TABLE IF EXISTS v089_blocked_by_duplicate_links;
CREATE TABLE v089_blocked_by_duplicate_links (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO v089_blocked_by_duplicate_links VALUES (1);
INSERT INTO v089_blocked_by_duplicate_links
SELECT 1 FROM external_workitem_identity_conflicts_v089 LIMIT 1;
DROP TABLE v089_blocked_by_duplicate_links;

DROP TABLE IF EXISTS v089_blocked_by_orphan_links;
CREATE TABLE v089_blocked_by_orphan_links (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO v089_blocked_by_orphan_links VALUES (1);
INSERT INTO v089_blocked_by_orphan_links
SELECT 1 FROM external_workitem_identity_orphans_v089 LIMIT 1;
DROP TABLE v089_blocked_by_orphan_links;

-- 5. 存量无冲突后，添加租户级唯一键。
ALTER TABLE external_workitem_link
  ADD UNIQUE KEY uk_external_workitem_identity (tenant_id, provider, external_workitem_id);

-- 回滚（先删唯一键再恢复备份；恢复前确认备份表是本次执行前快照）：
-- ALTER TABLE external_workitem_link DROP INDEX uk_external_workitem_identity;
-- DELETE FROM external_workitem_link;
-- INSERT INTO external_workitem_link SELECT * FROM external_workitem_link_backup_v089;
