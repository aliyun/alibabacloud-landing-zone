-- Optional free-text note written by the submitter when a version enters review.
-- Additive and nullable: old binaries keep working because they never reference the column.
ALTER TABLE `agent_version`
  ADD COLUMN `submit_note` VARCHAR(512) DEFAULT NULL COMMENT '提交人填写的变更说明（选填）' AFTER `identity_json`;
