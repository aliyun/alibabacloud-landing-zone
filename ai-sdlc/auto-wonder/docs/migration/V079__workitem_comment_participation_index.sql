-- V061: 「我评论过的」参与查询支持索引（工单 #55705）
-- 列表按 (tenant_id, author_type, author_ref, workitem_id) 判定「本人成功提交过工单评论」，
-- 并按本人在该工单下最近一次评论时间排序/回填。复合索引让 EXISTS 判定与 MAX(gmt_create)
-- 取数都走索引，不再按工单逐行回表扫描 workitem_comment。
-- 刻意不含 source_type：legacy 方言的 workitem_comment 无该列（source_type 由 V037 引入），
-- 不含 source_type 的索引在两种方言下都可创建并可被复用；source-aware 查询的
-- source_type='WORKITEM' 仅在同作者少量行上做残差过滤，代价可忽略。
CREATE INDEX idx_comment_author_time
  ON workitem_comment (tenant_id, author_type, author_ref, workitem_id, gmt_create);
