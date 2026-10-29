-- 工单执行控制留痕：dispatch_recovery 增加当前/最后一次控制事件指针。
-- 控制事件本身保存在 workitem_event.detail_json，指针只用于把异步确认关联到当时生效的
-- 不可变控制意图；不保存可回写的请求人，也不改变 dispatch.modifier_id 的既有语义。
-- 执行顺序：先应用本迁移，再发布读写 control_event_id 的新代码。
-- 幂等性：ALTER 为一次性 DDL；重复执行前先探测列是否已存在。

ALTER TABLE dispatch_recovery
  ADD COLUMN control_event_id BIGINT NOT NULL DEFAULT 0
  COMMENT 'workitem_event.id of the current/last execution control intent';
