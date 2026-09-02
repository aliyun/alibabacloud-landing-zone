export const workTypeMap: Record<string, { color: string; label: string }> = {
  REQ: { color: 'blue', label: '需求' },
  TASK: { color: 'green', label: '任务' },
  BUG: { color: 'red', label: '缺陷' },
};

export const priorityMap: Record<number, { color: string; label: string }> = {
  1: { color: '#ff4d4f', label: 'P1' },
  2: { color: '#fa8c16', label: 'P2' },
  3: { color: '#1890ff', label: 'P3' },
  4: { color: '#8c8c8c', label: 'P4' },
};

export interface StatusColumn {
  key: string;
  title: string;
  color: string;
}

export interface WorkitemStatusInput {
  statusName: string | null;
  pendingDecision?: boolean | null;
}

export const STATUS_COLUMNS: StatusColumn[] = [
  { key: 'NEW', title: '待处理', color: '#d9d9d9' },
  { key: 'IN_PROGRESS', title: '执行中', color: '#1890ff' },
  { key: 'PENDING_DECISION', title: '待决策', color: '#fa8c16' },
  { key: 'DONE', title: '已完成', color: '#52c41a' },
];

export function classifyStatus(statusName: string | null): string {
  if (!statusName) return 'NEW';
  const s = statusName.toUpperCase();
  if (s.includes('完成') || s.includes('关闭') || s.includes('DONE') || s.includes('CLOSED') || s.includes('FIXED') || s.includes('PUBLISHED') || s.includes('发布')) return 'DONE';
  if (s.includes('执行') || s.includes('开发') || s.includes('PROGRESS') || s.includes('RUNNING') || s.includes('验证')) return 'IN_PROGRESS';
  if (s.includes('决策') || s.includes('DECISION') || s.includes('审核') || s.includes('REVIEW') || s.includes('阻塞')) return 'PENDING_DECISION';
  return 'NEW';
}

export function classifyWorkitemStatus(item: WorkitemStatusInput): string {
  const status = classifyStatus(item.statusName);
  if (status === 'DONE') {
    return 'DONE';
  }
  if (item.pendingDecision) {
    return 'PENDING_DECISION';
  }
  return status;
}
