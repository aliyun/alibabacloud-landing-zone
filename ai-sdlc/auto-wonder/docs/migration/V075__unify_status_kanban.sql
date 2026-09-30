-- 统一 AW 工单状态管理与看板分类口径 — 存量数据一次性迁移（工单 #55395 规格 3.5 / 3.6）。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。
-- 不会自动执行迁移：请在发布代码前手动执行，并按以下顺序操作。
--
-- 背景：
--   Aone 导入曾为每个绑定按来源状态创建「AONE <项目> <工单类型> 状态」专属模板
--   （节点 code 前缀 aone_，节点类别由关键词/顺序推断，明细存 external_status_mapping）。
--   新口径：首次导入统一进入默认模板初始节点；Aone 状态仅作只读展示；
--   看板分类只认 status_node.category；类别此后仅由管理员显式配置。
--
-- 执行顺序（满足「全量备份 + 类别修正 + dry-run + 迁移报告 + 观察期回退」）：
--   第 1 步  A 节全量备份（本脚本执行即可）。
--   第 2 步  B 节类别修正（幂等，只改 status_node.category；先修正，dry-run 才反映迁移真实目标）。
--   第 3 步  C 节 dry-run 查询，人工核对迁移报告与复核清单，确认后再执行 D 节。
--   第 4 步  D 节实际迁移（工单迁移 → 审计 → 停用 Aone 专属模板）。
--   第 5 步  观察期内发现问题时，按 E 节回退。
-- 本脚本可重复执行：备份表 IF NOT EXISTS 保留首次快照；UPDATE/INSERT 均有幂等条件。

-- =============================================================================
-- A. 全量备份（观察期回退依据；仅首次执行时生成快照）
-- =============================================================================
CREATE TABLE IF NOT EXISTS `bak_v057_status_template` AS SELECT * FROM `status_template`;
CREATE TABLE IF NOT EXISTS `bak_v057_status_node` AS SELECT * FROM `status_node`;
CREATE TABLE IF NOT EXISTS `bak_v057_workitem_status` AS
SELECT w.`id`, w.`tenant_id`, w.`work_type`, w.`template_id`, w.`status_node_id`, w.`version`, w.`gmt_modified`
FROM `workitem` w
WHERE w.`template_id` IN (
    SELECT t.`id` FROM `status_template` t
    WHERE EXISTS (SELECT 1 FROM `status_node` n WHERE n.`template_id` = t.`id` AND n.`code` LIKE 'aone\_%')
);

-- =============================================================================
-- B. 模板类别修正（规格 3.6，一次性；幂等，只改 status_node.category）
-- =============================================================================
-- 显式语义词典精确匹配；无法匹配的节点保持现值，已列在 C4 复核清单。
-- 词典仅做精确（含大小写归一）匹配，不按关键词包含或节点顺序推断。
-- 必须先于 C 节 dry-run 执行：迁移报告与实际迁移都按修正后的类别选定目标节点。
UPDATE `status_node` SET `category` = 'CANCELED'
WHERE LOWER(`name`) IN ('已取消','已作废','取消','canceled','cancelled',
                       'won''tfix','won''t fix','invalid','duplicate','rejected');
UPDATE `status_node` SET `category` = 'DONE'
WHERE LOWER(`name`) IN ('已发布','已完成','已关闭','已解决','已修复','完成',
                        'fixed','done','closed','resolved','released');
UPDATE `status_node` SET `category` = 'INIT'
WHERE LOWER(`name`) IN ('新建','待办','待处理','待修复','open','new','todo','to do','backlog');
UPDATE `status_node` SET `category` = 'IN_PROGRESS'
WHERE LOWER(`name`) IN ('开发中','进行中','修复中','验证中','处理中','评审中','测试中',
                        'in progress','developing','fixing','verifying','doing','wip');

-- =============================================================================
-- C. dry-run：迁移报告与复核清单（只读，可反复执行）
-- =============================================================================
-- C1. 将被迁移/停用的 Aone 专属模板清单。
SELECT t.`id`, t.`tenant_id`, t.`work_type`, t.`name`
FROM `status_template` t
WHERE t.`is_deleted` = 0
  AND EXISTS (SELECT 1 FROM `status_node` n WHERE n.`template_id` = t.`id` AND n.`code` LIKE 'aone\_%');

-- C2. 迁移报告：每张 Aone 模板上的工单将落到默认模板的哪个节点。
--     规则（规格 3.5）：名称精确匹配优先（NAME_MATCH），否则类别等价映射
--     （CATEGORY_MATCH：INIT→新建类、IN_PROGRESS→开发中类、DONE→已发布类、CANCELED→已取消类，
--     取默认模板内该类别的首个节点）；两者都失败则进入复核清单（REVIEW，不迁移）。
SELECT w.`id` AS workitem_id, w.`tenant_id`, w.`work_type`,
       w.`template_id` AS old_template_id, cur.`name` AS old_node_name, cur.`category` AS old_node_category,
       def.`id` AS new_template_id, COALESCE(nm.`id`, cm.`id`) AS new_node_id,
       COALESCE(nm.`name`, cm.`name`) AS new_node_name,
       CASE WHEN nm.`id` IS NOT NULL THEN 'NAME_MATCH'
            WHEN cm.`id` IS NOT NULL THEN 'CATEGORY_MATCH'
            ELSE 'REVIEW' END AS `rule`
FROM `workitem` w
JOIN `status_template` aone ON aone.`id` = w.`template_id`
    AND aone.`is_deleted` = 0
    AND EXISTS (SELECT 1 FROM `status_node` an WHERE an.`template_id` = aone.`id` AND an.`code` LIKE 'aone\_%')
JOIN `status_node` cur ON cur.`id` = w.`status_node_id` AND cur.`template_id` = w.`template_id`
JOIN `status_template` def ON def.`tenant_id` = w.`tenant_id` AND def.`work_type` = w.`work_type`
    AND def.`is_default` = 1 AND def.`is_deleted` = 0
LEFT JOIN `status_node` nm ON nm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`name` = cur.`name`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
LEFT JOIN `status_node` cm ON cm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`category` = cur.`category`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
WHERE w.`is_deleted` = 0;

-- C3. 复核清单（一）：无法按名称或类别映射的工单（例如 TASK/BUG 默认模板没有 CANCELED 节点）。
--      这些工单保持现状，由管理员在模板管理里显式处置后重新执行本脚本。
SELECT w.`id` AS workitem_id, w.`tenant_id`, w.`work_type`, cur.`name` AS old_node_name, cur.`category` AS old_node_category
FROM `workitem` w
JOIN `status_template` aone ON aone.`id` = w.`template_id`
    AND aone.`is_deleted` = 0
    AND EXISTS (SELECT 1 FROM `status_node` an WHERE an.`template_id` = aone.`id` AND an.`code` LIKE 'aone\_%')
JOIN `status_node` cur ON cur.`id` = w.`status_node_id` AND cur.`template_id` = w.`template_id`
LEFT JOIN `status_template` def ON def.`tenant_id` = w.`tenant_id` AND def.`work_type` = w.`work_type`
    AND def.`is_default` = 1 AND def.`is_deleted` = 0
LEFT JOIN `status_node` nm ON nm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`name` = cur.`name`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
LEFT JOIN `status_node` cm ON cm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`category` = cur.`category`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
WHERE w.`is_deleted` = 0
  AND (def.`id` IS NULL OR (nm.`id` IS NULL AND cm.`id` IS NULL));

-- C4. 复核清单（二）：名称不在语义词典内、类别保持现值的节点（规格 3.6）。
--      此后类别只由管理员显式配置；B 节的词典修正不会覆盖它们。
SELECT n.`id`, n.`tenant_id`, n.`template_id`, n.`name`, n.`category`
FROM `status_node` n
WHERE LOWER(n.`name`) NOT IN (
    '新建','待办','待处理','待修复','open','new','todo','to do','backlog',
    '开发中','进行中','修复中','验证中','处理中','评审中','测试中',
    'in progress','developing','fixing','verifying','doing','wip',
    '已发布','已完成','已关闭','已解决','已修复','完成','fixed','done','closed','resolved','released',
    '已取消','已作废','取消','canceled','cancelled','won''tfix','won''t fix','invalid','duplicate','rejected'
);

-- =============================================================================
-- D. 实际迁移
-- =============================================================================
-- D1. Aone 工单迁移（规格 3.5，一次性）：迁移到同工单类型的默认模板。
--     名称精确匹配优先，否则类别等价映射；两者都失败的工单不动（见 C3）。
--     仅更新仍在 Aone 专属模板上的工单，重复执行为空操作；version+1 使并发的乐观锁写入失效。
UPDATE `workitem` w
JOIN `status_template` aone ON aone.`id` = w.`template_id`
    AND aone.`is_deleted` = 0
    AND EXISTS (SELECT 1 FROM `status_node` an WHERE an.`template_id` = aone.`id` AND an.`code` LIKE 'aone\_%')
JOIN `status_node` cur ON cur.`id` = w.`status_node_id` AND cur.`template_id` = w.`template_id`
JOIN `status_template` def ON def.`tenant_id` = w.`tenant_id` AND def.`work_type` = w.`work_type`
    AND def.`is_default` = 1 AND def.`is_deleted` = 0
LEFT JOIN `status_node` nm ON nm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`name` = cur.`name`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
LEFT JOIN `status_node` cm ON cm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = def.`id` AND n.`category` = cur.`category`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
SET w.`template_id` = def.`id`,
    w.`status_node_id` = COALESCE(nm.`id`, cm.`id`),
    w.`version` = w.`version` + 1
WHERE w.`is_deleted` = 0
  AND COALESCE(nm.`id`, cm.`id`) IS NOT NULL;

-- D2. 迁移审计：为本次实际发生迁移的工单补 STATUS_CHANGE 事件（SYSTEM 来源，迁移原因入 detail_json）。
--     以 A 节备份快照判定「已迁移」，并用事件去重保证重复执行不产生重复审计。
INSERT INTO `workitem_event` (`tenant_id`, `workitem_id`, `event_type`, `from_val`, `to_val`,
                              `actor_type`, `actor_ref`, `detail_json`)
SELECT b.`tenant_id`, b.`id`, 'STATUS_CHANGE', oldn.`name`, newn.`name`, 'SYSTEM', NULL,
       JSON_OBJECT('source', 'MIGRATION', 'reason', '统一状态口径存量迁移（V057）',
                   'oldTemplateId', b.`template_id`, 'newTemplateId', w.`template_id`,
                   'rule', CASE WHEN nm.`id` IS NOT NULL THEN 'NAME_MATCH' ELSE 'CATEGORY_MATCH' END)
FROM `bak_v057_workitem_status` b
JOIN `workitem` w ON w.`id` = b.`id` AND w.`template_id` <> b.`template_id`
JOIN `status_node` oldn ON oldn.`id` = b.`status_node_id`
JOIN `status_node` newn ON newn.`id` = w.`status_node_id`
JOIN `status_node` cur ON cur.`id` = b.`status_node_id`
LEFT JOIN `status_node` nm ON nm.`id` = (
    SELECT n.`id` FROM `status_node` n
    WHERE n.`template_id` = w.`template_id` AND n.`name` = cur.`name`
    ORDER BY n.`sort`, n.`id` LIMIT 1)
WHERE NOT EXISTS (
    SELECT 1 FROM `workitem_event` e
    WHERE e.`tenant_id` = b.`tenant_id` AND e.`workitem_id` = b.`id`
      AND e.`event_type` = 'STATUS_CHANGE'
      AND JSON_UNQUOTE(JSON_EXTRACT(e.`detail_json`, '$.source')) = 'MIGRATION');

-- D3. Aone 专属模板停用（软删除；节点与迁移边保留供回退与只读查询）。
--     仅停用已无未删除工单引用的模板：仍有工单因无法映射留在 Aone 模板（见 C3）时，
--     模板保持有效，待管理员处置后重跑本脚本再停用。
UPDATE `status_template` t
SET t.`is_deleted` = 1
WHERE t.`is_deleted` = 0
  AND EXISTS (SELECT 1 FROM `status_node` n WHERE n.`template_id` = t.`id` AND n.`code` LIKE 'aone\_%')
  AND NOT EXISTS (SELECT 1 FROM `workitem` w
                  WHERE w.`template_id` = t.`id` AND w.`is_deleted` = 0);

-- D4. external_status_mapping 自 V057 起不再被任何代码读写（对应 DAO 已随
--     ExternalStatusBootstrapService 一并删除）。观察期内保留表与数据供回退核对，
--     观察期结束后可由 DBA 执行：DROP TABLE external_status_mapping;
--     （本脚本不主动 DROP，保持回退能力。）

-- =============================================================================
-- E. 观察期回退（仅发现问题且经确认后执行；执行前先备份当 前状态）
-- =============================================================================
-- UPDATE `workitem` w
-- JOIN `bak_v057_workitem_status` b ON b.`id` = w.`id`
-- SET w.`template_id` = b.`template_id`, w.`status_node_id` = b.`status_node_id`,
--     w.`version` = w.`version` + 1;
-- UPDATE `status_node` n
-- JOIN `bak_v057_status_node` b ON b.`id` = n.`id`
-- SET n.`category` = b.`category`;
-- UPDATE `status_template` t
-- JOIN `bak_v057_status_template` b ON b.`id` = t.`id`
-- SET t.`is_deleted` = b.`is_deleted`;
-- 回退后可按需删除迁移审计事件：
-- DELETE e FROM `workitem_event` e
-- WHERE e.`event_type` = 'STATUS_CHANGE'
--   AND JSON_UNQUOTE(JSON_EXTRACT(e.`detail_json`, '$.source')) = 'MIGRATION';
