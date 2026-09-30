-- 对外只读分享：SDLC/Agent 或管理员可将工单产物标记为对外可读，
-- 外部协作用户（如 Aone 工单评论读者）通过 workitem 级分享令牌无登录只读访问。
ALTER TABLE `workitem`
    ADD COLUMN `external_share_token` VARCHAR(64) DEFAULT NULL
        COMMENT '对外只读分享令牌（全局唯一，首次暴露产物时生成；软删工单后失效）' AFTER `tags`,
    ADD UNIQUE KEY `uk_workitem_external_share_token` (`external_share_token`);

ALTER TABLE `artifact`
    ADD COLUMN `external_exposed` TINYINT NOT NULL DEFAULT 0
        COMMENT '是否对外暴露（由 SDLC/Agent 经 MCP expose 工具决定，仅结论类产物）' AFTER `meta_json`,
    ADD COLUMN `external_share_ref` VARCHAR(512) DEFAULT NULL
        COMMENT '对外分享快照 ref（首次暴露时固化的内容版本；同 key 重传只更新 live oss_ref，不影响此快照）' AFTER `external_exposed`,
    ADD KEY `idx_artifact_exposed` (`tenant_id`, `workitem_id`, `external_exposed`);
