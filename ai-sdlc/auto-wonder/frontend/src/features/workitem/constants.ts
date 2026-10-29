import { i18n } from '@/shared/i18n';

export const workTypeMap: Record<string, { color: string; label: string }> = {
  REQ: { color: 'blue', get label() { return i18n.t('workitem:workType.requirement'); } },
  TASK: { color: 'green', get label() { return i18n.t('workitem:workType.task'); } },
  BUG: { color: 'red', get label() { return i18n.t('workitem:workType.bug'); } },
};

/**
 * 优先级展示映射，与创建表单 P0～P3（紧急/高/中/低）语义一致：
 * 列表、看板、详情共用，存储值直接映射中文标签，不再单独显示数字。
 */
export const priorityMap: Record<number, { color: string; label: string }> = {
  0: { color: 'red', get label() { return i18n.t('workitem:priority.urgent'); } },
  1: { color: 'orange', get label() { return i18n.t('workitem:priority.high'); } },
  2: { color: 'blue', get label() { return i18n.t('workitem:priority.medium'); } },
  3: { color: 'default', get label() { return i18n.t('workitem:priority.low'); } },
};

export const UNKNOWN_PRIORITY: { color: string; label: string } = {
  color: 'default',
  get label() { return i18n.t('workitem:priority.unknown'); },
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

/** 默认看板的四个列；CANCELED 只作为筛选值，不进入默认看板（规格 3.1 优先级 0）。 */
export const STATUS_COLUMNS: StatusColumn[] = [
  { key: 'NEW', get title() { return i18n.t('workitem:status.new'); }, color: 'var(--aw-border)' },
  { key: 'IN_PROGRESS', get title() { return i18n.t('workitem:status.inProgress'); }, color: 'var(--aw-accent-text)' },
  { key: 'PENDING_DECISION', get title() { return i18n.t('workitem:status.pendingDecision'); }, color: 'var(--aw-warning)' },
  { key: 'DONE', get title() { return i18n.t('workitem:status.done'); }, color: 'var(--aw-success)' },
];

/** 已取消列仅在状态筛选显式选中「已取消」时展示。 */
export const CANCELED_STATUS_COLUMN: StatusColumn = {
  key: 'CANCELED', get title() { return i18n.t('workitem:status.canceled'); }, color: 'var(--aw-muted)',
};

/** 状态筛选下拉：四个看板列 + 已取消，用于查看默认看板隐藏的已取消工单。 */
export const STATUS_FILTER_COLUMNS: StatusColumn[] = [...STATUS_COLUMNS, CANCELED_STATUS_COLUMN];

/** 读取服务端下发的看板分类；缺失时按待处理兜底（规格 3.1：状态名称不参与判断）。 */
export function statusCategoryOf(item?: WorkitemStatusInput | null): string {
  if (!item || !item.statusCategory) return 'NEW';
  return item.statusCategory;
}
