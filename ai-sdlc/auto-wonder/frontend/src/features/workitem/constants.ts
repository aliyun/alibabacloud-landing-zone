export const workTypeMap: Record<string, { color: string; label: string }> = {
  REQ: { color: 'blue', label: '需求' },
  TASK: { color: 'green', label: '任务' },
  BUG: { color: 'red', label: '缺陷' },
};

/**
 * 优先级展示映射，与创建表单 P0～P3（紧急/高/中/低）语义一致：
 * 列表、看板、详情共用，存储值直接映射中文标签，不再单独显示数字。
 */
export const priorityMap: Record<number, { color: string; label: string }> = {
  0: { color: 'red', label: '紧急' },
  1: { color: 'orange', label: '高' },
  2: { color: 'blue', label: '中' },
  3: { color: 'default', label: '低' },
};

export const UNKNOWN_PRIORITY: { color: string; label: string } = {
  color: 'default',
  label: '未知优先级',
};

/** 未知优先级值显示「未知优先级」，不映射为低优先级、不改数据。 */
export function getPriorityMeta(priority: number): { color: string; label: string } {
  return priorityMap[priority] ?? UNKNOWN_PRIORITY;
}

export interface StatusColumn {
  key: string;
  title: string;
  color: string;
}

export interface WorkitemStatusInput {
  /** 服务端按统一口径（规格 3.1）下发的看板分类。 */
  statusCategory?: string | null;
}

export const STATUS_COLUMNS: StatusColumn[] = [
  { key: 'NEW', title: '待处理', color: 'var(--aw-border)' },
  { key: 'IN_PROGRESS', title: '执行中', color: 'var(--aw-accent-text)' },
  { key: 'PENDING_DECISION', title: '待决策', color: 'var(--aw-warning)' },
  { key: 'DONE', title: '已完成', color: 'var(--aw-success)' },
];

/** 读取服务端下发的看板分类；缺失时按待处理兜底（规格 3.1：状态名称不参与判断）。 */
export function statusCategoryOf(item?: WorkitemStatusInput | null): string {
  if (!item || !item.statusCategory) return 'NEW';
  return item.statusCategory;
}
