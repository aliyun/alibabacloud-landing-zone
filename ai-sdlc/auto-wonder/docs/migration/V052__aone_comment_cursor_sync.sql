-- Aone comments are synchronized independently from workitem catalog/search calls.
ALTER TABLE `external_workitem_link`
  ADD COLUMN `comment_sync_cursor` VARCHAR(128) NOT NULL DEFAULT '0'
    COMMENT '已同步的最大 Aone commentId' AFTER `last_error`;

-- Existing imported/outbound comment links have already been handled. Start each linked workitem
-- at its greatest known numeric Aone commentId so rollout cannot re-trigger historical mentions.
UPDATE `external_workitem_link` l
LEFT JOIN (
  SELECT `tenant_id`, `binding_id`, `external_workitem_id`,
         CAST(MAX(CAST(`external_comment_id` AS UNSIGNED)) AS CHAR) AS `comment_cursor`
  FROM `external_comment_link`
  WHERE `provider` = 'AONE' AND `external_comment_id` REGEXP '^[0-9]+$'
  GROUP BY `tenant_id`, `binding_id`, `external_workitem_id`
) c ON c.`tenant_id` = l.`tenant_id`
   AND c.`binding_id` = l.`binding_id`
   AND c.`external_workitem_id` = l.`external_workitem_id`
SET l.`comment_sync_cursor` = COALESCE(c.`comment_cursor`, '0')
WHERE l.`provider` = 'AONE';
